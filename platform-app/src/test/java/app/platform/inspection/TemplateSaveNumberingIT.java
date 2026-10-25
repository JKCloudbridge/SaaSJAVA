package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Save-time numbering, persistent additions, permanent reservations and first-Approve protection (ADR-0198). */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateSaveNumberingIT extends TemplateSupport {
    private static final String VALUES = "/api/v1/metadata/objects/InspectionTemplate/fields/subCategory/values";

    private static Response patch(TestBrowser browser, String id, String fields) {
        long version = JsonPath.<Number>read(browser.get(RECORDS + "/" + id).body(), "$.data.version").longValue();
        return browser.request("PATCH", RECORDS + "/" + id,
                "{\"version\":" + version + ",\"fields\":" + fields + "}");
    }

    private static String pair(String sub) {
        return "{\"category\":\"QSP\",\"subCategory\":\"" + sub + "\"}";
    }

    private static void override(Setting setting) {
        Response saved = setting.admin().request("PUT", "/api/v1/numbering/document/formats",
                "{\"kind\":\"CATEGORY\",\"selectorValue\":\"QSP\",\"version\":0,"
                        + "\"format\":\"{field:category}/{field:subCategory}/{seq:1}\"}");
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
    }

    @Test
    void validationRollsBackItsAllocationAndUnauthorizedSavesIssueNothing() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person reviewer = approver(setting);
        String id = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
        Response denied = patch(author.browser(), id, "{\"category\":\"WI\",\"subCategory\":\"PRD\"}");
        assertThat(denied.status()).isGreaterThanOrEqualTo(400);
        assertThat(number(setting.admin(), id)).isNull();
        Response checked = reviewer.browser().postJson("/api/v1/objects/InspectionTemplate/records/validate",
                "{\"recordId\":\"" + id + "\",\"fields\":{\"category\":\"WI\",\"subCategory\":\"PRD\"}}");
        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isNull();
        assertThat(scalar(setting.organization(), "select count(*) from number_series_reservation")).isZero();
        Setting other = setting();
        assertThat(other.admin().request("PATCH", RECORDS + "/" + id,
                "{\"version\":0,\"fields\":{\"category\":\"WI\"}}").status()).isEqualTo(404);
        assertThat(new TestBrowser(port, setting.organization().host()).request("PATCH", RECORDS + "/" + id,
                "{\"version\":0,\"fields\":{\"category\":\"WI\"}}").status()).isEqualTo(401);
        assertThat(patch(reviewer.browser(), id, "{\"category\":\"WI\",\"subCategory\":\"PRD\"}")
                .status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/01");
    }

    @Test
    void addingSelectingSavingAndReusingAValueCountsEachPairAndRetainsSetupChoices() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person plant = approver(setting);
        String process = process(setting.admin(), "Review", step("Only", user(plant.userId())));
        override(setting);
        try (LogCapture logs = LogCapture.of("app.platform")) {
            String first = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
            fill(author.browser(), first, firstVersion(author.browser(), first));
            String request = submitted(author.browser(), first, process);
            Response added = plant.browser().postJson(VALUES,
                    "{\"value\":\"Hero\",\"label\":\"Hero\",\"controllingValues\":[\"QSP\"]}");
            assertThat(added.status()).as(added.body()).isEqualTo(200);
            String selected = JsonPath.read(added.body(), "$.data.value");
            assertThat(selected).isEqualTo("HERO");
            assertThat(JsonPath.<Boolean>read(added.body(), "$.data.added")).isTrue();
            Response saved = patch(plant.browser(), first, pair(selected));
            assertThat(saved.status()).as(saved.body()).isEqualTo(200);
            assertThat(JsonPath.<String>read(saved.body(), "$.data.fields.number")).isEqualTo("QSP/HERO/1");
            Response duplicate = plant.browser().postJson(VALUES,
                    "{\"value\":\"Hero\",\"controllingValues\":[\"QSP\"]}");
            assertThat(JsonPath.<Boolean>read(duplicate.body(), "$.data.added")).isFalse();
            assertThat(approve(plant.browser(), request).status()).isEqualTo(200);
            String second = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
            Response form = plant.browser().get("/api/v1/objects/InspectionTemplate/form?recordId=" + second);
            assertThat(form.status()).as(form.body()).isEqualTo(200);
            assertThat(JsonPath.<List<String>>read(form.body(),
                    "$.data.fields[?(@.apiName=='subCategory')].dependency.allowed.QSP[*]"))
                    .contains("HERO");
            assertThat(patch(plant.browser(), second, pair(selected)).status()).isEqualTo(200);
            assertThat(number(setting.admin(), second)).isEqualTo("QSP/HERO/2");
            assertThat(plant.browser().postJson(VALUES,
                    "{\"value\":\"OTHER\",\"controllingValues\":[\"QSP\"]}").status()).isEqualTo(200);
            String third = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
            assertThat(patch(plant.browser(), third, pair("OTHER")).status()).isEqualTo(200);
            assertThat(number(setting.admin(), third)).isEqualTo("QSP/OTHER/1");
            assertThat(logs.events()).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("QSP/HERO/1", "Hero"));
        }
        assertThat(audited(setting.organization(), "numbering.assigned")).isEqualTo(3);
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
        assertThat(patch(author.browser(), id, "{\"subCategory\":\"QLT\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/QLT/01");
        assertThat(patch(author.browser(), id, "{\"subCategory\":\"PRD\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/02");
        assertThat(patch(author.browser(), id, "{\"name\":\"" + newName() + "\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/02");
        String request = submitted(author.browser(), id, process);
        assertThat(reject(plant.browser(), request).status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/02");
        assertThat(patch(author.browser(), id, "{\"subCategory\":\"QLT\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/QLT/02");
        fill(author.browser(), id, newVersion(author.browser(), id));
        String again = submitted(author.browser(), id, process);
        assertThat(approve(plant.browser(), again).status()).isEqualTo(200);
        Response denied = patch(corporate.browser(), id, "{\"subCategory\":\"PRD\"}");
        assertThat(denied.status()).isEqualTo(400);
        assertThat(denied.body()).contains("The identity fields are fixed after the first approval.");
        assertThat(reject(corporate.browser(), again).status()).isEqualTo(200);
        assertThat(patch(author.browser(), id, "{\"subCategory\":\"PRD\"}").status()).isEqualTo(400);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/QLT/02");
        assertThat(scalar(setting.organization(), "select count(*) from number_series_reservation")).isEqualTo(4);
    }

    @Test
    void aBlankSubCategoryUsesTheDefaultAndCanBeSelectedClearedAndApproved() {
        Setting setting = setting();
        Person author = author(setting);
        Person reviewer = approver(setting);
        String id = templateWithSheet(author.browser(), "WI", null);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/1");
        assertThat(patch(author.browser(), id, "{\"subCategory\":\"PRD\"}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/PRD/01");
        assertThat(patch(author.browser(), id, "{\"subCategory\":null}").status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/2");
        String process = process(setting.admin(), "Review", step("Only", user(reviewer.userId())));
        String request = submitted(author.browser(), id, process);
        assertThat(approve(reviewer.browser(), request).status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("WI/2");
    }

    @Test
    void shippedCategoryCountsIgnoreSubCategoryAndApprovalAllowsAnEmptyChoice() {
        Setting setting = setting();
        Person author = author(setting);
        Person reviewer = approver(setting);
        assertThat(setting.admin().postJson(VALUES,
                "{\"value\":\"HERO\",\"controllingValues\":[\"QSP\"]}").status()).isEqualTo(200);
        String id = templateWithSheet(author.browser(), "QSP", null);
        assertThat(number(setting.admin(), id)).isEqualTo("QSP-01");
        String process = process(setting.admin(), "Review", step("Only", user(reviewer.userId())));
        String request = submitted(author.browser(), id, process);
        assertThat(approve(reviewer.browser(), request).status()).isEqualTo(200);
        assertThat(number(setting.admin(), id)).isEqualTo("QSP-01");
    }
}
