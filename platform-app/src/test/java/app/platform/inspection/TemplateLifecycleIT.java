package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole life of a template (FA4, ADR-0174, ADR-0176, ADR-0188), through the real API and the real database: a
 * creator drafts and submits, approvers approve step by step and the first approval gives the number, an approver
 * publishes it to a department, the people of that department read it and nobody else does, a second review leaves the
 * version in use alone, and a rejected, withdrawn or cancelled review leaves the number and the version in use as they
 * were. */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateLifecycleIT extends TemplateSupport {

    private record Run(Setting setting, Person author, Person first, Person second, Person reader, Person outsider,
            String process, String department) {
    }

    private Run run() {
        Setting setting = setting();
        Person author = author(setting);
        Person first = approver(setting);
        Person second = approver(setting);
        Person reader = reader(setting);
        Person outsider = outsider(setting);
        String process = process(setting.admin(), "Templates", step("Review", user(first.userId())),
                step("Approval", user(second.userId())));
        String group = group(setting.admin(), "department-" + System.nanoTime(), reader);
        department(setting.admin(), "PLANT1", "PRD", group);
        return new Run(setting, author, first, second, reader, outsider, process, group);
    }

    // ---- the whole path ----

    @Test
    void aTemplateIsDraftedSubmittedNumberedApprovedPublishedAndReadOnlyByTheDepartment() {
        Run run = run();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");

        // Before anything: the author sees it, the reader and the outsider do not (the template is private).
        assertThat(listed(run.author().browser())).contains(template);
        assertThat(listed(run.reader().browser())).doesNotContain(template);
        assertThat(version(run.reader().browser(), template, 1).status()).isEqualTo(404);
        assertThat(state(run.author().browser(), template)).isEqualTo("DRAFT");

        String request = submitted(run.author().browser(), template, run.process());
        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW");
        assertThat(number(run.setting().admin(), template)).as("no number before an approval").isNull();

        // The first approver's own Approve gives the number, once.
        assertThat(approve(run.first().browser(), request).status()).isEqualTo(200);
        assertThat(number(run.setting().admin(), template)).isEqualTo("WI/PRD/01");
        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW");
        assertThat(approve(run.second().browser(), request).status()).isEqualTo(200);
        assertThat(number(run.setting().admin(), template)).isEqualTo("WI/PRD/01");

        // Approved is not published: the version waits and nobody outside the approvers and the author reads it.
        assertThat(statuses(run.author().browser(), template)).containsExactly("APPROVED");
        assertThat(state(run.second().browser(), template)).isEqualTo("APPROVED");
        assertThat(listed(run.reader().browser())).doesNotContain(template);
        assertThat(version(run.reader().browser(), template, 1).status()).isEqualTo(404);

        // An approver publishes it to a department.
        Response published = publish(run.second().browser(), template, null, "PLANT1/PRD");
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), template)).containsExactly("ACTIVE");
        assertThat(JsonPath.<String>read(published.body(), "$.data.lifecycle.state")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<List<String>>read(published.body(), "$.data.audience[*].department"))
                .containsExactly("PRD");

        // The people of that department read it in the list and open the version in use; the outsider does not.
        assertThat(listed(run.reader().browser())).contains(template);
        assertThat(version(run.reader().browser(), template, 1).status()).isEqualTo(200);
        assertThat(run.outsider().browser().get(TEMPLATES).status())
                .as("not even the list of an object they have no right on")
                .isEqualTo(404);
        assertThat(version(run.outsider().browser(), template, 1).status()).isEqualTo(404);
        assertThat(lifecycle(run.outsider().browser(), template).status()).isEqualTo(404);
        // The reader reads and changes nothing: no draft, no save.
        assertThat(run.reader().browser().postJson(versions(template), "{}").status()).isEqualTo(403);
    }

    // ---- the number ----

    @Test
    void aTemplateRejectedAtTheFirstStepTakesNoNumberButOneRejectedLaterKeepsIt() {
        Run run = run();
        String early = templateWithSheet(run.author().browser(), "WI", "PRD");
        String requestEarly = submitted(run.author().browser(), early, run.process());

        assertThat(reject(run.first().browser(), requestEarly).status()).isEqualTo(200);

        assertThat(number(run.setting().admin(), early)).isNull();
        assertThat(statuses(run.author().browser(), early)).containsExactly("REJECTED");

        String late = templateWithSheet(run.author().browser(), "WI", "PRD");
        String requestLate = submitted(run.author().browser(), late, run.process());
        assertThat(approve(run.first().browser(), requestLate).status()).isEqualTo(200);
        assertThat(reject(run.second().browser(), requestLate).status()).isEqualTo(200);

        assertThat(number(run.setting().admin(), late)).as("the first rejection took no number, so this is the first")
                .isEqualTo("WI/PRD/01");
        assertThat(statuses(run.author().browser(), late)).containsExactly("REJECTED");
        // The next template goes on from it: a number is never given twice.
        String next = templateWithSheet(run.author().browser(), "WI", "PRD");
        String requestNext = submitted(run.author().browser(), next, run.process());
        assertThat(approve(run.first().browser(), requestNext).status()).isEqualTo(200);
        assertThat(number(run.setting().admin(), next)).isEqualTo("WI/PRD/02");
    }

    @Test
    void theSecondVersionOfATemplateKeepsItsNumberAndTakesNoNewOne() {
        Run run = run();
        String template = templateWithSheet(run.author().browser(), "SOP", "PRD");
        String first = submitted(run.author().browser(), template, run.process());
        assertThat(approve(run.first().browser(), first).status()).isEqualTo(200);
        assertThat(approve(run.second().browser(), first).status()).isEqualTo(200);
        assertThat(publish(run.second().browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(number(run.setting().admin(), template)).isEqualTo("SOP/PRD/01");

        int next = newVersion(run.author().browser(), template);
        assertThat(fill(run.author().browser(), template, next).status()).isEqualTo(200);
        String again = submitted(run.author().browser(), template, run.process());
        assertThat(approve(run.first().browser(), again).status()).isEqualTo(200);

        assertThat(number(run.setting().admin(), template)).isEqualTo("SOP/PRD/01");
        assertThat(JsonPath.<Number>read(run.setting().admin().get(RECORDS + "/" + template).body(), "$.data.version")
                .longValue()).isPositive();
    }

    // ---- a second review while the first version is in use ----

    @Test
    void whileANewVersionIsInReviewTheVersionInUseStaysReadableAndPublishingReplacesItAtOnce() {
        Run run = run();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");
        String first = submitted(run.author().browser(), template, run.process());
        approve(run.first().browser(), first);
        approve(run.second().browser(), first);
        assertThat(publish(run.second().browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);

        int two = newVersion(run.author().browser(), template);
        assertThat(fill(run.author().browser(), template, two).status()).isEqualTo(200);
        String request = submitted(run.author().browser(), template, run.process());

        // The reader still reads version 1 while version 2 is in review; the draft and the review are not theirs.
        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW", "ACTIVE");
        assertThat(version(run.reader().browser(), template, 1).status()).isEqualTo(200);
        assertThat(version(run.reader().browser(), template, 2).status()).isEqualTo(404);
        Response readerList = run.reader().browser().get(versions(template));
        assertThat(JsonPath.<List<String>>read(readerList.body(), "$.data.versions[*].status"))
                .containsExactly("ACTIVE");
        assertThat(state(run.second().browser(), template)).isEqualTo("ACTIVE");

        // Approving does not publish: version 1 stays in use until an approver publishes version 2.
        approve(run.first().browser(), request);
        approve(run.second().browser(), request);
        assertThat(statuses(run.author().browser(), template)).containsExactly("APPROVED", "ACTIVE");
        assertThat(version(run.reader().browser(), template, 2).status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(lifecycle(run.second().browser(), template).body(),
                "$.data.lifecycle.state")).isEqualTo("ACTIVE");

        Response again = publish(run.second().browser(), template, null, "PLANT1/PRD");
        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), template)).containsExactly("ACTIVE", "SUPERSEDED");
        assertThat(version(run.reader().browser(), template, 2).status()).isEqualTo(200);
        assertThat(version(run.reader().browser(), template, 1).status()).as("history stays readable").isEqualTo(200);
        assertThat(publications(run.setting().organization(), template, true)).isEqualTo(1);
    }

    @Test
    void anApproverCanPublishWhileAnotherVersionIsInReview() {
        Run run = run();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");
        String first = submitted(run.author().browser(), template, run.process());
        approve(run.first().browser(), first);
        approve(run.second().browser(), first);
        int two = newVersion(run.author().browser(), template);
        fill(run.author().browser(), template, two);
        submitted(run.author().browser(), template, run.process());

        Response published = publish(run.second().browser(), template, 1, "PLANT1/PRD");

        assertThat(published.status()).as("shares are allowed on a locked record: " + published.body()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW", "ACTIVE");
        assertThat(listed(run.reader().browser())).contains(template);
    }

    @Test
    void aSecondVersionCannotBeSubmittedWhileTheFirstIsInReviewButADraftCanWaitMeanwhile() {
        Run run = run();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");
        String pending = submitted(run.author().browser(), template, run.process());
        // A draft can be started and kept while the first version is reviewed...
        int two = newVersion(run.author().browser(), template);
        assertThat(fill(run.author().browser(), template, two).status()).isEqualTo(200);

        // ...but only one request may be pending for the record: the author who asks again is shown the request that
        // is pending (the draft does not go anywhere), and somebody else is told that the record is being approved.
        Response again = submit(run.author().browser(), template, run.process());
        Response other = submit(run.setting().admin(), template, run.process());

        assertThat(JsonPath.<String>read(again.body(), "$.data.summary.id")).isEqualTo(pending);
        assertThat(other.status()).isEqualTo(409);
        assertThat(message(other)).contains("already being approved");
        assertThat(statuses(run.author().browser(), template)).containsExactly("DRAFT", "IN_REVIEW");
    }

    // ---- an ended review leaves the version in use alone ----

    @Test
    void aWithdrawnRequestAndACancelledOneLeaveThePreviousVersionInUse() {
        Run run = run();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");
        String first = submitted(run.author().browser(), template, run.process());
        approve(run.first().browser(), first);
        approve(run.second().browser(), first);
        publish(run.second().browser(), template, null, "PLANT1/PRD");

        int two = newVersion(run.author().browser(), template);
        fill(run.author().browser(), template, two);
        String recalled = submitted(run.author().browser(), template, run.process());
        assertThat(recall(run.author().browser(), recalled).status()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), template)).containsExactly("WITHDRAWN", "ACTIVE");

        int three = newVersion(run.author().browser(), template);
        fill(run.author().browser(), template, three);
        String cancelled = submitted(run.author().browser(), template, run.process());
        assertThat(cancel(run.setting().admin(), cancelled).status()).isEqualTo(200);

        assertThat(statuses(run.author().browser(), template)).containsExactly("WITHDRAWN", "WITHDRAWN", "ACTIVE");
        assertThat(version(run.reader().browser(), template, 1).status()).isEqualTo(200);
        assertThat(number(run.setting().admin(), template)).isEqualTo("WI/PRD/01");
    }

    // ---- what submitting needs ----

    @Test
    void anEmptyDraftIsRefusedAtSubmitAndATemplateWithoutACategoryAtTheFirstApprovalWithWords() {
        Run run = run();
        String empty = template(run.author().browser(), "WI", "PRD");
        firstVersion(run.author().browser(), empty);

        Response blank = submit(run.author().browser(), empty, run.process());

        assertThat(blank.status()).isEqualTo(409);
        assertThat(message(blank)).contains("still empty");
        assertThat(statuses(run.author().browser(), empty)).containsExactly("DRAFT");

        String noCategory = template(run.author().browser(), "{\"name\":\"" + newName() + "\"}");
        int number = firstVersion(run.author().browser(), noCategory);
        fill(run.author().browser(), noCategory, number);
        String request = submitted(run.author().browser(), noCategory, run.process());
        Response uncategorised = approve(run.first().browser(), request);

        assertThat(uncategorised.status()).isEqualTo(409);
        assertThat(message(uncategorised)).contains("Choose the identity category and sub-category (Edit)");
        assertThat(statuses(run.author().browser(), noCategory)).containsExactly("IN_REVIEW");
        assertThat(statusOf(run.first().browser(), request)).isEqualTo("PENDING");
        assertThat(number(run.setting().admin(), noCategory)).isNull();
    }

    @Test
    void aTemplateWithNoSheetAtAllCannotBeSubmitted() {
        Run run = run();
        String template = template(run.author().browser(), "WI", "PRD");

        Response response = submit(run.author().browser(), template, run.process());

        assertThat(response.status()).isEqualTo(409);
        assertThat(message(response)).contains("no draft");
    }
}
