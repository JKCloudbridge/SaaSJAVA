package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMembers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A version in review (FA4, ADR-0174 sections 3 and 4, decision 13): only the people who approve can change it, each
 * change is counted and audited by size and fingerprint and never by content, the author who submitted it cannot, and a
 * person asked to approve who may not read the template can read that one version and nothing else.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ReviewEditIT extends TemplateSupport {

    private record Run(Setting setting, Person author, Person approver, Person reader, String template,
            String request) {
    }

    private Run inReview() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        Person reader = reader(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        String template = templateWithSheet(author.browser(), "WI", "PRD");
        String request = submitted(author.browser(), template, process);
        return new Run(setting, author, approver, reader, template, request);
    }

    private static Response correct(Person person, String template, String label) {
        Response read = version(person.browser(), template, 1);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return saveGrid(person.browser(), template, 1, rowVersion(read), withContent(gridOf(read), label));
    }

    @Test
    void anApproverCorrectsTheGridInReviewAndItIsCountedAndAuditedWithoutItsContent() {
        Run run = inReview();
        String before = JsonPath.read(version(run.approver().browser(), run.template(), 1).body(),
                "$.data.version.gridHash");

        Response first = correct(run.approver(), run.template(), "Secret wording one");
        Response second = correct(run.approver(), run.template(), "Secret wording two");

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(first.body(), "$.data.status")).isEqualTo("IN_REVIEW");
        assertThat(JsonPath.<Integer>read(first.body(), "$.data.reviewEdits")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(second.body(), "$.data.reviewEdits")).isEqualTo(2);
        assertThat(JsonPath.<String>read(second.body(), "$.data.gridHash")).isNotEqualTo(before);
        assertThat(version(run.approver().browser(), run.template(), 1).body()).contains("Secret wording two");
        assertThat(audited(run.setting().organization(), "sheets.version.review_edited")).isEqualTo(2);
        assertThat(audited(run.setting().organization(), "approval.lock.overridden")).isEqualTo(2);
        assertThat(owner("select string_agg(attributes::text, ' ') from audit_record where event_type = "
                + "'sheets.version.review_edited' and context_tenant_id = ?",
                run.setting().organization().id().value()).get(0)).as("by counts and hash, never the text")
                .doesNotContain("Secret wording").contains("review_edits").contains("hash");
        // The review goes on and ends as before; afterwards the version is fixed for everybody.
        assertThat(approve(run.approver().browser(), run.request()).status()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), run.template())).containsExactly("APPROVED");
        Response late = correct(run.approver(), run.template(), "Too late");
        assertThat(late.status()).isEqualTo(409);
    }

    @Test
    void theAuthorWhoSubmittedItAndEveryoneWithoutTheAbilityAreRefusedAndNothingChanges() {
        Run run = inReview();
        String hash = JsonPath.read(version(run.author().browser(), run.template(), 1).body(),
                "$.data.version.gridHash");

        Response author = correct(run.author(), run.template(), "By the author");

        assertThat(author.status()).isEqualTo(403);
        assertThat(message(author)).isEqualTo("A version that is in review can be changed only by the people who "
                + "approve it.");
        assertThat(JsonPath.<String>read(version(run.approver().browser(), run.template(), 1).body(),
                "$.data.version.gridHash")).isEqualTo(hash);
        assertThat(audited(run.setting().organization(), "sheets.version.review_edited")).isZero();
        Response read = version(run.reader().browser(), run.template(), 1);
        assertThat(read.status()).as("the reader never sees a version in review").isEqualTo(404);
        assertThat(saveGrid(run.reader().browser(), run.template(), 1, 0, "{}").status()).isEqualTo(404);
    }

    @Test
    void theAdministratorWhoHoldsTheOverrideAbilityMayCorrectToo() {
        Run run = inReview();
        Response read = version(run.setting().admin(), run.template(), 1);

        Response saved = saveGrid(run.setting().admin(), run.template(), 1, rowVersion(read),
                withContent(gridOf(read), "By the administrator"));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(saved.body(), "$.data.reviewEdits")).isEqualTo(1);
    }

    @Test
    void aCorrectionAgainstAStaleRowVersionIsTheOrdinaryConflictAndCountsNothing() {
        Run run = inReview();
        Response read = version(run.approver().browser(), run.template(), 1);
        long stale = rowVersion(read);
        assertThat(correct(run.approver(), run.template(), "Alpha").status()).isEqualTo(200);

        Response again = saveGrid(run.approver().browser(), run.template(), 1, stale,
                withContent(gridOf(read), "Beta"));

        assertThat(again.status()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(audited(run.setting().organization(), "sheets.version.review_edited")).isEqualTo(1);
    }

    @Test
    void savingTheGridAsItIsIsNotACorrectionAndIsNeitherCountedNorAudited() {
        Run run = inReview();
        Response read = version(run.approver().browser(), run.template(), 1);

        Response same = saveGrid(run.approver().browser(), run.template(), 1, rowVersion(read), gridOf(read));

        assertThat(same.status()).as(same.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(same.body(), "$.data.reviewEdits")).isZero();
        assertThat(JsonPath.<Number>read(same.body(), "$.data.version").longValue()).isEqualTo(rowVersion(read));
        assertThat(audited(run.setting().organization(), "sheets.version.review_edited")).isZero();
        assertThat(audited(run.setting().organization(), "approval.lock.overridden")).isZero();
    }

    @Test
    void aDraftBesideAVersionInReviewIsTheAuthorsToKeepWorkingOn() {
        Run run = inReview();
        int two = newVersion(run.author().browser(), run.template());

        Response saved = fill(run.author().browser(), run.template(), two);

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(saved.body(), "$.data.reviewEdits")).isZero();
        assertThat(statuses(run.author().browser(), run.template())).containsExactly("DRAFT", "IN_REVIEW");
        assertThat(audited(run.setting().organization(), "sheets.version.review_edited")).isZero();
    }

    // ---- a person asked to approve who may not read the template ----

    @Test
    void aPersonAskedToApproveReadsTheVersionTheyAreAskedAboutAndNothingElseAndOnlyWhileTheTaskIsOpen() {
        Setting setting = setting();
        Person author = author(setting);
        Person asked = outsider(setting);
        TestMembers.giveLicence(setting.organization().id(), asked.member().membership(), "user");
        String process = process(setting.admin(), "Asked", step("Only", user(asked.userId())));
        String template = templateWithSheet(author.browser(), "WI", "PRD");
        String request = submitted(author.browser(), template, process);

        Response list = asked.browser().get(versions(template));
        Response one = version(asked.browser(), template, 1);

        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data.versions[*].status")).containsExactly("IN_REVIEW");
        assertThat(JsonPath.<Boolean>read(list.body(), "$.data.canChange")).isFalse();
        assertThat(one.status()).as(one.body()).isEqualTo(200);
        // It is that one version they may read: not the record, not another version, and they change nothing.
        assertThat(asked.browser().get(RECORDS + "/" + template).status()).as("an approval grants no access to the "
                + "record").isEqualTo(404);
        assertThat(version(asked.browser(), template, 2).status()).isEqualTo(404);
        assertThat(saveGrid(asked.browser(), template, 1, rowVersion(one), gridOf(one)).status()).isEqualTo(404);
        assertThat(asked.browser().postJson(versions(template), "{}").status()).isEqualTo(404);
        assertThat(lifecycle(asked.browser(), template).status()).isEqualTo(404);

        // Once they have decided there is no open task, and the version is theirs no more.
        assertThat(approve(asked.browser(), request).status()).isEqualTo(200);
        assertThat(version(asked.browser(), template, 1).status()).isEqualTo(404);
        assertThat(asked.browser().get(versions(template)).status()).isEqualTo(404);
    }

    @Test
    void aPersonNotAskedToApproveAndAPersonOfAnotherOrganizationReadNothingOfTheVersionInReview() {
        Run run = inReview();
        Person bystander = outsider(run.setting());
        Run other = inReview();

        assertThat(version(bystander.browser(), run.template(), 1).status()).isEqualTo(404);
        assertThat(bystander.browser().get(versions(run.template())).status()).isEqualTo(404);
        assertThat(version(other.approver().browser(), run.template(), 1).status())
                .as("an approver of another organization is asked about something else").isEqualTo(404);
    }
}
