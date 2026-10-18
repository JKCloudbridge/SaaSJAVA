package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Telling the people who were asked (ADR-0152): one closed template, queued in the transaction that made the task, so
 * once
 * per task however often a step is claimed; facts only, no title, no value and no comment; a person already mailed in
 * the
 * last minute is not mailed again; a delegate is mailed like the delegator. The relay is switched off so the queue can
 * be looked at.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false",
    "platform.notification.enabled=false"})
class ApprovalMailIT extends ApprovalSupport {

    private static final String TITLE = "Quarterly figures SECRET-TITLE";
    private static final String COMMENT = "Please look at SECRET-COMMENT";

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person manager,
            String process) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person manager = person(organization, admin);
        String process = processExists(admin, "Expense", null, step("Manager", "ANY", user(manager.userId())));
        return new Setup(organization, admin, person(organization, admin), manager, process);
    }

    @Test
    void aTaskQueuesOneMailWithFactsOnlyToTheApprover() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"" + TITLE + "\"}");

        submit(setup.submitter().browser(), record, setup.process(), COMMENT);

        List<String> mails = queuedMails(setup.manager().member().person().email().toLowerCase());
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0)).contains(setup.organization().id().value().toString()).contains("ObjectA__c")
                .contains(record).contains("Expense").doesNotContain("SECRET-TITLE").doesNotContain("SECRET-COMMENT");
        assertThat(owner("select count(*) from mail_queue where template = 'APPROVAL_REQUEST' and email = ?",
                setup.submitter().member().person().email().toLowerCase()).get(0)).as("the submitter is not asked")
                .isEqualTo("0");
    }

    @Test
    void aPersonMailedAMomentAgoIsNotMailedAgainForTheNextTaskButTheInboxShowsBoth() {
        Setup setup = setup();
        String first = submitted(setup.submitter().browser(), record(setup.submitter().browser(),
                "{\"fieldA__c\":\"one\"}"), setup.process());
        String second = submitted(setup.submitter().browser(), record(setup.submitter().browser(),
                "{\"fieldA__c\":\"two\"}"), setup.process());

        assertThat(queuedMails(setup.manager().member().person().email().toLowerCase())).hasSize(1);
        assertThat(openRequests(setup.manager().browser())).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void aDelegateIsMailedLikeTheDelegator() {
        Setup setup = setup();
        Person delegate = person(setup.organization(), setup.admin());
        setup.manager().browser().postJson(DELEGATIONS, "{\"delegateId\":\"" + delegate.userId() + "\",\"startsOn\":\""
                + today() + "\",\"endsOn\":\"" + inDays(1) + "\",\"timeZone\":\"UTC\"}");

        submitted(setup.submitter().browser(), record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}"),
                setup.process());

        assertThat(queuedMails(setup.manager().member().person().email().toLowerCase())).hasSize(1);
        assertThat(queuedMails(delegate.member().person().email().toLowerCase())).hasSize(1);
    }

    @Test
    void aRefusedSubmitQueuesNoMail() {
        Setup setup = setup();
        Person third = person(setup.organization(), setup.admin());
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        submitted(setup.submitter().browser(), record, setup.process());

        submit(third.browser(), record, setup.process(), null);

        assertThat(queuedMails(setup.manager().member().person().email().toLowerCase()))
                .as("only the first request asked anybody").hasSize(1);
    }
}
