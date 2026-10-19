package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Reminders, escalation and the overdue mark of approvals (ADR-0159), driven by the timer itself ({@code pollOnce})
 * with the passing of time made in the rows: each event happens once however often the timer looks, a step that was
 * decided or a request that ended is never reminded, the fallback approver becomes one more task, the people asked are
 * told in the application unless they switched that off, and a process with no timing has no timer at all.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalReminderIT extends ApprovalSupport {

    @Autowired
    private ApprovalTimer timer;

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person approver,
            Person fallback, String process, String request) {
    }

    /** A process that reminds after 1 hour and escalates after 3, with a fallback approver. */
    private Setup setup() {
        return setup(1, 3);
    }

    private Setup setup(Integer remindAfter, Integer escalateAfter) {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        Person fallback = person(organization, admin);
        String base = createRequest("Expense", null, false, user(fallback.userId()), null,
                step("One", "ANY", user(approver.userId())));
        String timing = (remindAfter == null ? "" : ",\"remindAfterHours\":" + remindAfter)
                + (escalateAfter == null ? "" : ",\"escalateAfterHours\":" + escalateAfter);
        String process = processExists(admin, base.substring(0, base.length() - 1) + timing + "}");
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"),
                process);
        return new Setup(organization, admin, submitter, approver, fallback, process, request);
    }

    /** Lets time pass for the active steps and the timers that watch them. */
    private static void pass(Organization organization, int hours) {
        run(organization, "update approval_step_instance set activated_at = activated_at - make_interval(hours => ?), "
                + "version = version + 1 where status = 'ACTIVE'", hours);
        run(organization, "update approval_step_timer set next_timer_at = now() - interval '1 minute', "
                + "version = version + 1 where next_timer_at is not null");
    }

    private static long count(Organization organization, String history) {
        return scalar(organization, "select count(*) from approval_history where kind = ?", history);
    }

    private static long notices(Organization organization, String kind) {
        return scalar(organization, "select count(*) from notification where kind = ?", kind);
    }

    @Test
    void aStepThatHasWaitedLongEnoughRemindsTheOpenApproversOnceHoweverOftenTheTimerLooks() {
        Setup setup = setup();
        assertThat(scalar(setup.organization(), "select count(*) from approval_step_timer")).isEqualTo(1);

        timer.pollOnce();
        assertThat(count(setup.organization(), "REMINDER_SENT")).as("an hour has not passed").isZero();

        pass(setup.organization(), 2);
        timer.pollOnce();
        timer.pollOnce();
        timer.pollOnce();

        assertThat(count(setup.organization(), "REMINDER_SENT")).isEqualTo(1);
        assertThat(notices(setup.organization(), "APPROVAL_REMINDER")).isEqualTo(1);
        assertThat(scalar(setup.organization(), "select count(*) from notification where kind = 'APPROVAL_REMINDER' "
                + "and recipient_id = ?", UUID.fromString(setup.approver().userId()))).isEqualTo(1);
        assertThat(historyOf(setup.admin(), setup.request())).contains("REMINDER_SENT");
        assertThat(audited(setup.organization(), "approval.timer.reminded")).isEqualTo(1);
    }

    @Test
    void theEscalationAsksTheFallbackApproverOnceAndTheyBecomeOneMoreTask() {
        Setup setup = setup();
        assertThat(openRequests(setup.fallback().browser())).isEmpty();

        pass(setup.organization(), 4);
        timer.pollOnce();
        timer.pollOnce();

        assertThat(count(setup.organization(), "ESCALATED")).isEqualTo(1);
        assertThat(openRequests(setup.fallback().browser())).containsExactly(setup.request());
        assertThat(openRequests(setup.approver().browser())).as("the first approver keeps theirs")
                .containsExactly(setup.request());
        assertThat(notices(setup.organization(), "APPROVAL_TASK")).as("the submit and the escalation").isEqualTo(2);
    }

    @Test
    void whenTheRemindersAreUsedUpTheRequestIsMarkedOverdueOnceAndTheAdministratorIsShown() {
        Setup setup = setup();

        pass(setup.organization(), 10);
        timer.pollOnce();
        pass(setup.organization(), 10);
        timer.pollOnce();

        assertThat(count(setup.organization(), "OVERDUE_MARKED")).isEqualTo(1);
        assertThat(count(setup.organization(), "ATTENTION_RAISED")).isEqualTo(1);
        Response shown = instance(setup.admin(), setup.request());
        assertThat(optional(shown.body(), "$.data.summary.attention")).isEqualTo("overdue");
        assertThat(scalar(setup.organization(), "select count(*) from approval_step_timer where overdue_at is not null "
                + "and ended_at is null")).isEqualTo(1);
    }

    @Test
    void aDecidedStepIsNeverRemindedAndItsTimerStops() {
        Setup setup = setup();
        assertThat(approve(setup.approver().browser(), setup.request()).status()).isEqualTo(200);

        pass(setup.organization(), 10);
        timer.pollOnce();

        assertThat(count(setup.organization(), "REMINDER_SENT")).isZero();
        assertThat(count(setup.organization(), "ESCALATED")).isZero();
        assertThat(notices(setup.organization(), "APPROVAL_REMINDER")).isZero();
        assertThat(scalar(setup.organization(), "select count(*) from approval_step_timer where next_timer_at is "
                + "not null")).isZero();
    }

    @Test
    void aRequestThatWasRecalledIsNeverRemindedEither() {
        Setup setup = setup();
        assertThat(decide(setup.submitter().browser(), setup.request(), "recall", "changed my mind").status())
                .isEqualTo(200);

        pass(setup.organization(), 10);
        timer.pollOnce();

        assertThat(count(setup.organization(), "REMINDER_SENT")).isZero();
        assertThat(statusOf(setup.admin(), setup.request())).isEqualTo("RECALLED");
    }

    @Test
    void aPersonWhoSwitchedTheNoticesOffGetsNoNoticeAndTheReminderStillHappens() {
        Setup setup = setup();
        Response off = setup.approver().browser().request("PUT", "/api/v1/notification-preferences",
                "{\"choices\":[{\"category\":\"APPROVALS\",\"channel\":\"IN_APP\",\"enabled\":false}]}");
        assertThat(off.status()).as(off.body()).isEqualTo(200);

        pass(setup.organization(), 2);
        timer.pollOnce();

        assertThat(count(setup.organization(), "REMINDER_SENT")).as("the reminder is part of the request").isEqualTo(1);
        assertThat(notices(setup.organization(), "APPROVAL_REMINDER")).isZero();
    }

    @Test
    void aProcessWithNoTimingHasNoTimerAndNothingIsEverSent() {
        Setup setup = setup(null, null);

        pass(setup.organization(), 100);
        timer.pollOnce();

        assertThat(scalar(setup.organization(), "select count(*) from approval_step_timer")).isZero();
        assertThat(count(setup.organization(), "REMINDER_SENT")).isZero();
        assertThat(count(setup.organization(), "OVERDUE_MARKED")).isZero();
    }

    @Test
    void aProcessThatOnlyEscalatesIsMarkedOverdueAtTheEscalation() {
        Setup setup = setup(null, 2);

        pass(setup.organization(), 3);
        timer.pollOnce();

        assertThat(count(setup.organization(), "REMINDER_SENT")).isZero();
        assertThat(count(setup.organization(), "ESCALATED")).isEqualTo(1);
        assertThat(count(setup.organization(), "OVERDUE_MARKED")).isEqualTo(1);
    }

    @Test
    void theTimerOfOneOrganizationNeverTouchesAnotherAndHandlesBothWhenBothAreDue() {
        Setup mine = setup();
        Setup theirs = setup();

        pass(mine.organization(), 2);
        timer.pollOnce();

        assertThat(count(mine.organization(), "REMINDER_SENT")).isEqualTo(1);
        assertThat(count(theirs.organization(), "REMINDER_SENT")).as("their time has not come").isZero();
        assertThat(notices(theirs.organization(), "APPROVAL_REMINDER")).isZero();

        pass(theirs.organization(), 2);
        timer.pollOnce();

        assertThat(count(theirs.organization(), "REMINDER_SENT")).isEqualTo(1);
        assertThat(count(mine.organization(), "REMINDER_SENT")).as("still the one").isEqualTo(1);
    }
}
