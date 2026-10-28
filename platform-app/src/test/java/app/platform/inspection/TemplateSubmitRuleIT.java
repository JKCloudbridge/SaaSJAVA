package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Whether a template can be sent for approval is one rule of the server (FA10, ADR-0204): the lifecycle view says it
 * (so the screen can grey Submit out) and the approval hook asks it again (so a send that cannot work is refused in the
 * same words). For every state of a template the two must agree: when the view says it can be sent the send is
 * accepted, and when it says it cannot the send is refused with the sentence the view gave.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateSubmitRuleIT extends FillSupport {

    // The fixed sentences of the rule, written out so that a change of a word is a decision (TemplateSendRule).
    private static final String NO_DRAFT = "Nothing to send yet: the template has no first Revision in draft.";
    private static final String BEING_APPROVED = "It is already being approved.";
    private static final String PUBLISHED = "A published template changes only through a change request.";
    private static final String RETIRED = "This template is retired and cannot be sent for approval.";

    private static boolean canSubmit(TestBrowser browser, String template) {
        Response response = lifecycle(browser, template);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.data.canSubmit");
    }

    private static String note(TestBrowser browser, String template) {
        return JsonPath.read(lifecycle(browser, template).body(), "$.data.submitNote");
    }

    /** A template with a process, a person who decides it and the author who sends it. */
    private record Case(Setting setting, Person author, Person approver, String process) {
    }

    private Case sending() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        String process = process(setting.admin(), "Send" + System.nanoTime(), step("one", user(approver.userId())));
        return new Case(setting, author, approver, process);
    }

    @Test
    void aFirstRevisionInDraftCanBeSentAndTheSendIsAccepted() {
        Case run = sending();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");

        assertThat(canSubmit(run.author().browser(), template)).isTrue();
        assertThat(lifecycle(run.author().browser(), template).body()).as("no sentence when it can be sent")
                .doesNotContain("\"submitNote\":\"");
        assertThat(submit(run.author().browser(), template, run.process()).status()).isEqualTo(201);
    }

    @Test
    void aTemplateWithNoFirstRevisionInDraftCannotBeSentAndIsRefusedInTheSameWords() {
        Case run = sending();
        String template = template(run.author().browser(), "WI", "PRD");

        assertThat(canSubmit(run.author().browser(), template)).isFalse();
        Response refused = submit(run.author().browser(), template, run.process());
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(message(refused)).isEqualTo(note(run.author().browser(), template))
                .isEqualTo(NO_DRAFT);
    }

    @Test
    void aTemplateInReviewCannotBeSentAgain() {
        Case run = sending();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");
        submitted(run.author().browser(), template, run.process());

        assertThat(canSubmit(run.author().browser(), template)).isFalse();
        assertThat(note(run.author().browser(), template)).isEqualTo(BEING_APPROVED);
        // The approval engine answers a repeat send of a record that already has a pending request; the template still has
        // exactly one version in review (the engine, not this rule, keeps one request per record).
        submit(run.author().browser(), template, run.process());
        assertThat(statuses(run.author().browser(), template)).containsExactly("IN_REVIEW");
    }

    @Test
    void aReturnedTemplateHasNothingToSendUntilItHasANewDraftAndThenCanBeSent() {
        Case run = sending();
        String template = templateWithSheet(run.author().browser(), "WI", "PRD");
        String request = submitted(run.author().browser(), template, run.process());
        assertThat(reject(run.approver().browser(), request).status()).isEqualTo(200);

        assertThat(canSubmit(run.author().browser(), template)).as("returned, no draft").isFalse();
        assertThat(note(run.author().browser(), template)).isEqualTo(NO_DRAFT);

        int draft = newVersion(run.author().browser(), template);
        assertThat(fill(run.author().browser(), template, draft).status()).isEqualTo(200);
        assertThat(canSubmit(run.author().browser(), template)).as("a new draft was made").isTrue();
        assertThat(submit(run.author().browser(), template, run.process()).status()).isEqualTo(201);
    }

    @Test
    void aPublishedTemplateCannotBeSentAndIsRefusedInTheSameWords() {
        World world = world();

        assertThat(canSubmit(world.approver().browser(), world.template())).isFalse();
        assertThat(note(world.approver().browser(), world.template())).isEqualTo(PUBLISHED);
        Response refused = submit(world.author().browser(), world.template(), world.templateProcess());
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(message(refused)).isEqualTo(PUBLISHED);
    }

    @Test
    void aRetiredTemplateCannotBeSentAndIsRefusedInTheSameWords() {
        World world = world();
        assertThat(retire(world.approver().browser(), world.template()).status()).isEqualTo(200);

        assertThat(canSubmit(world.approver().browser(), world.template())).isFalse();
        Response refused = submit(world.author().browser(), world.template(), world.templateProcess());
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(message(refused)).isEqualTo(note(world.approver().browser(), world.template()))
                .isEqualTo(RETIRED);
    }

    @Test
    void anotherOrganizationAndANotSignedInCallerDoNotSeeTheField() {
        World world = world();
        Setting elsewhere = setting();

        assertThat(lifecycle(approver(elsewhere).browser(), world.template()).status()).isEqualTo(404);
        assertThat(lifecycle(new TestBrowser(port, world.setting().organization().host()), world.template()).status())
                .isEqualTo(401);
    }
}
