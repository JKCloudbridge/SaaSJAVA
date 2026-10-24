package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The two fields the author fills in and the identity pair the approvers decide (FA6A, ADR-0196): an author with the
 * Author policy and nothing more chooses a Level and a Document that goes with it, cannot set the category or the
 * sub-category, and sends the template without them; the first approval is refused in words while the pair is empty,
 * and an approver sets it in review (the override of the lock, ADR-0148) and approves, which gives the number. The
 * person asked to approve first is the one who numbers, whatever the process (here: two steps).
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateIdentityIT extends TemplateSupport {

    private record Run(Setting setting, Person author, Person plant, Person corporate, String process) {
    }

    private Run run() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person plant = approver(setting);
        Person corporate = approver(setting);
        String process = process(setting.admin(), "TwoSteps", step("Plant", user(plant.userId())),
                step("Corporate", user(corporate.userId())));
        return new Run(setting, author, plant, corporate, process);
    }

    private static String withSheet(TestBrowser author, String fieldsJson) {
        String template = template(author, fieldsJson);
        fill(author, template, firstVersion(author, template));
        return template;
    }

    private static Response patch(TestBrowser browser, String template, String fields) {
        long version = JsonPath.<Number>read(browser.get(RECORDS + "/" + template).body(), "$.data.version")
                .longValue();
        return browser.request("PATCH", RECORDS + "/" + template, "{\"fields\":" + fields + ",\"version\":" + version
                + "}");
    }

    @Test
    void anAuthorChoosesALevelAndADocumentThatGoesWithItAndNothingElseOfTheIdentity() {
        Run run = run();
        TestBrowser author = run.author().browser();

        Response good = author.postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName()
                + "\",\"level\":\"L2\",\"documentType\":\"DOCC\"}}");
        Response wrongPair = author.postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName()
                + "\",\"level\":\"L2\",\"documentType\":\"DOCA\"}}");
        Response identity = author.postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName()
                + "\",\"category\":\"WI\"}}");

        assertThat(good.status()).as(good.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(good.body(), "$.data.fields.level")).isEqualTo("L2");
        assertThat(JsonPath.<String>read(good.body(), "$.data.fields.documentType")).isEqualTo("DOCC");
        assertThat(wrongPair.status()).as("Doc A does not go with Level 2").isGreaterThanOrEqualTo(400);
        assertThat(identity.status()).as("the identity pair is the approvers'").isGreaterThanOrEqualTo(400);
        assertThat(identity.body()).doesNotContain("\"WI\"");
    }

    @Test
    void theTemplateIsSentWithoutTheIdentityPairAndTheFirstApprovalIsRefusedUntilAnApproverSetsIt() {
        Run run = run();
        String template = withSheet(run.author().browser(), "{\"name\":\"" + newName()
                + "\",\"level\":\"L1\",\"documentType\":\"DOCA\"}");

        String request = submitted(run.author().browser(), template, run.process());

        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW");
        Response authorEdit = patch(run.author().browser(), template, "{\"name\":\"" + newName() + "\"}");
        assertThat(authorEdit.status()).as("the author cannot change it while it is in review").isEqualTo(409);
        assertThat(code(authorEdit)).isEqualTo("RECORD_LOCKED");

        Response refused = approve(run.plant().browser(), request);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(message(refused))
                .isEqualTo("Choose the identity category and sub-category (Edit), then approve again.");
        assertThat(statusOf(run.plant().browser(), request)).isEqualTo("PENDING");
        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW");
        assertThat(number(run.setting().admin(), template)).isNull();

        Response set = patch(run.plant().browser(), template, "{\"category\":\"WI\",\"subCategory\":\"PRD\"}");
        assertThat(set.status()).as("an approver changes the header of a template in review: " + set.body())
                .isEqualTo(200);
        assertThat(approve(run.plant().browser(), request).status()).isEqualTo(200);

        assertThat(number(run.setting().admin(), template)).isEqualTo("WI/PRD/01");
        assertThat(statusOf(run.plant().browser(), request)).as("the second step is still to decide")
                .isEqualTo("PENDING");
        assertThat(approve(run.corporate().browser(), request).status()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), template)).containsExactly("APPROVED");
        assertThat(number(run.setting().admin(), template)).as("the number never changes").isEqualTo("WI/PRD/01");
    }

    @Test
    void anAuthorWithoutTheOverrideAbilityAndAReaderCannotSetTheIdentityPairInReview() {
        Run run = run();
        Person reader = reader(run.setting());
        String template = withSheet(run.author().browser(), "{\"name\":\"" + newName() + "\"}");
        submitted(run.author().browser(), template, run.process());

        Response byAuthor = patch(run.author().browser(), template, "{\"category\":\"WI\"}");
        Response byReader = reader.browser().request("PATCH", RECORDS + "/" + template,
                "{\"fields\":{\"category\":\"WI\"},\"version\":0}");

        assertThat(byAuthor.status()).isGreaterThanOrEqualTo(400);
        assertThat(byReader.status()).isGreaterThanOrEqualTo(400);
        assertThat(field(run.setting().admin(), template, "category")).isNull();
    }

    @Test
    void aProcessOfOneStepNumbersAtTheFirstApprovalToo() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person only = approver(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(only.userId())));
        String template = withSheet(author.browser(), "{\"name\":\"" + newName() + "\"}");
        String request = submitted(author.browser(), template, process);

        assertThat(approve(only.browser(), request).status()).isEqualTo(409);
        assertThat(patch(only.browser(), template, "{\"category\":\"IMS\"}").status()).isEqualTo(200);
        assertThat(approve(only.browser(), request).status()).isEqualTo(200);

        assertThat(number(setting.admin(), template)).isEqualTo("IMS/01");
    }
}
