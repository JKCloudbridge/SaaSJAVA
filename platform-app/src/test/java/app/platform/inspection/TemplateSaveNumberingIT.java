package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Save-time numbering, parts typed freehand, the typed start of a new combination, permanent reservations and
 * first-Approve protection (ADR-0198, ADR-0206).
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateSaveNumberingIT extends TemplateSupport {

    private static Response patch(TestBrowser browser, String id, String fields) {
        long version = JsonPath.<Number>read(browser.get(RECORDS + "/" + id).body(), "$.data.version").longValue();
        return browser.request("PATCH", RECORDS + "/" + id,
                "{\"version\":" + version + ",\"fields\":" + fields + "}");
    }

    private static String pair(String partA) {
        return "{\"category\":\"WI\",\"partA\":\"" + partA + "\"}";
    }

    @Test
    void validationRollsBackItsAllocationAndUnauthorizedSavesIssueNothing() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person reviewer = approver(setting);
        String id = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
        Response denied = patch(author.browser(), id, "{\"category\":\"WI\",\"partA\":\"PRD\"}");
        assertThat(denied.status()).isGreaterThanOrEqualTo(400);
        assertThat(number(setting.admin(), id)).isNull();
        Response checked = reviewer.browser().postJson("/api/v1/objects/InspectionTemplate/records/validate",
                "{\"recordId\":\"" + id + "\",\"fields\":{\"category\":\"WI\",\"partA\":\"PRD\"}}");
        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isNull();
        assertThat(scalar(setting.organization(), "select count(*) from number_series_reservation")).isZero();
        Setting other = setting();
        assertThat(other.admin().request("PATCH", RECORDS + "/" + id,
                "{\"version\":0,\"fields\":{\"category\":\"WI\"}}").status()).isEqualTo(404);
        assertThat(new TestBrowser(port, setting.organization().host()).request("PATCH", RECORDS + "/" + id,
                "{\"version\":0,\"fields\":{\"category\":\"WI\"}}").status()).isEqualTo(401);
        assertThat(patch(reviewer.browser(), id, "{\"category\":\"WI\",\"partA\":\"PRD\"}")
                .status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/01");
    }

    @Test
    void partsTypedFreehandCountEachCombinationAndTheOldSpellingIsKept() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person plant = approver(setting);
        String process = process(setting.admin(), "Review", step("Only", user(plant.userId())));
        try (LogCapture logs = LogCapture.of("app.platform")) {
            String first = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
            fill(author.browser(), first, firstVersion(author.browser(), first));
            String request = submitted(author.browser(), first, process);
            Response lower = patch(plant.browser(), first, pair("hero"));
            assertThat(lower.status()).as("capital letters are the one rule").isEqualTo(400);
            assertThat(lower.body()).contains("capital letters").doesNotContain("hero");
            Response saved = patch(plant.browser(), first, pair("HERO"));
            assertThat(saved.status()).as(saved.body()).isEqualTo(200);
            assertThat(JsonPath.<String>read(saved.body(), "$.data.fields.number")).isEqualTo("WI/HERO/01");
            assertThat(approve(plant.browser(), request).status()).isEqualTo(200);
            String second = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
            assertThat(patch(plant.browser(), second, pair("HERO")).status()).isEqualTo(200);
            assertThat(number(setting.admin(), second)).isEqualTo("WI/HERO/02");
            String third = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
            assertThat(patch(plant.browser(), third, pair("OTHER")).status()).isEqualTo(200);
            assertThat(number(setting.admin(), third)).isEqualTo("WI/OTHER/01");
            assertThat(logs.events()).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("WI/HERO/01", "HERO"));
        }
        assertThat(audited(setting.organization(), "numbering.assigned")).isEqualTo(3);
    }

    @Test
    void theFirstSerialOfANewCombinationIsTypedAndTheNextOnesAreAutomatic() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person plant = approver(setting);
        String first = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
        String second = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
        String third = template(author.browser(), "{\"name\":\"" + newName() + "\"}");

        Response notDigits = patch(plant.browser(), first,
                "{\"category\":\"WI\",\"partA\":\"NEWP\",\"serial\":\"5a\"}");
        Response typed = patch(plant.browser(), first, "{\"category\":\"WI\",\"partA\":\"NEWP\",\"serial\":\"5\"}");
        Response automatic = patch(plant.browser(), second,
                "{\"category\":\"WI\",\"partA\":\"NEWP\",\"serial\":\"9\"}");
        Response other = patch(plant.browser(), third, "{\"category\":\"WI\",\"partA\":\"ELSE\"}");

        assertThat(notDigits.status()).as(notDigits.body()).isEqualTo(400);
        assertThat(notDigits.body()).contains("digits only");
        assertThat(typed.status()).as(typed.body()).isEqualTo(200);
        assertThat(number(setting.admin(), first)).as("the typed serial starts the combination")
                .isEqualTo("WI/NEWP/05");
        assertThat(automatic.status()).as(automatic.body()).isEqualTo(200);
        assertThat(number(setting.admin(), second)).as("a serial typed for a running combination is not used")
                .isEqualTo("WI/NEWP/06");
        assertThat(other.status()).isEqualTo(200);
        assertThat(number(setting.admin(), third)).as("without a typed serial a new combination starts at 1")
                .isEqualTo("WI/ELSE/01");
    }

    @Test
    void switchingBackConsumesTheNextCountAndFirstApproveAloneFixesTheIdentity() {
        Setting setting = setting();
        Person author = author(setting);
        Person plant = approver(setting);
        Person corporate = approver(setting);
        String process = process(setting.admin(), "TwoSteps", step("First", user(plant.userId())),
                step("Second", user(corporate.userId())));
        String id = templateWithSheet(author.browser(), "WI", "PRD");
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/01");
        assertThat(patch(author.browser(), id, "{\"partA\":\"QLT\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/QLT/01");
        assertThat(patch(author.browser(), id, "{\"partA\":\"PRD\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/02");
        assertThat(patch(author.browser(), id, "{\"name\":\"" + newName() + "\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/02");
        String request = submitted(author.browser(), id, process);
        assertThat(reject(plant.browser(), request).status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/02");
        assertThat(patch(author.browser(), id, "{\"partA\":\"QLT\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/QLT/02");
        fill(author.browser(), id, newVersion(author.browser(), id));
        String again = submitted(author.browser(), id, process);
        assertThat(approve(plant.browser(), again).status()).isEqualTo(200);
        Response denied = patch(corporate.browser(), id, "{\"partA\":\"PRD\"}");
        assertThat(denied.status()).isEqualTo(400);
        assertThat(denied.body()).contains("The identity fields are fixed after the first approval.");
        assertThat(reject(corporate.browser(), again).status()).isEqualTo(200);
        assertThat(patch(author.browser(), id, "{\"partA\":\"PRD\"}").status()).isEqualTo(400);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/QLT/02");
        assertThat(scalar(setting.organization(), "select count(*) from number_series_reservation")).isEqualTo(4);
    }

    @Test
    void aBlankPartAMeansNoNumberYetAndTheApprovalSaysWhatToFillIn() {
        Setting setting = setting();
        Person author = author(setting);
        Person reviewer = approver(setting);
        String id = templateWithSheet(author.browser(), "WI", null);
        assertThat(number(setting.admin(), id)).as("a format that reads Part A has no number without it").isNull();
        assertThat(patch(author.browser(), id, "{\"partA\":\"PRD\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/01");
        assertThat(patch(author.browser(), id, "{\"partA\":null}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).as("clearing a part clears the number").isNull();
        String process = process(setting.admin(), "Review", step("Only", user(reviewer.userId())));
        String request = submitted(author.browser(), id, process);

        Response refused = approve(reviewer.browser(), request);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("Part A");
        // A pending request locks the record, so the author fills the part in after the request is returned.
        assertThat(reject(reviewer.browser(), request).status()).isEqualTo(200);
        assertThat(patch(author.browser(), id, "{\"partA\":\"PRD\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).startsWith("WI/PRD/");
    }

    @Test
    void anIdentificationWithoutPartsIgnoresTheTypedOnesAndApprovalAllowsAnEmptyChoice() {
        Setting setting = setting();
        Person author = author(setting);
        Person reviewer = approver(setting);
        String id = templateWithSheet(author.browser(), "QSP", null);
        assertThat(number(setting.admin(), id)).isEqualTo("QSP-01");
        String process = process(setting.admin(), "Review", step("Only", user(reviewer.userId())));
        String request = submitted(author.browser(), id, process);
        assertThat(approve(reviewer.browser(), request).status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("QSP-01");
    }
}
