package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.platform.identity.AccountTokens;
import app.platform.identity.Invitations;
import app.platform.identity.Users;
import app.platform.metadata.NotificationTemplate;
import app.platform.metadata.NotificationTemplates;
import app.platform.notification.internal.MailComposer.Decision;
import app.platform.notification.internal.MailStore.ClaimedMail;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mails of an organization and of the end of a trial after Sprint 25 (ADR-0155, ADR-0156, ADR-0162): the words an
 * administrator wrote replace the platform's own for the body only, the subject and the link stay the platform's, a
 * person who switched a category off by mail is sent nothing, the end of a trial reaches the administrators of an
 * organization that may already be suspended, and none of it can carry a value of a record or a link of someone's own.
 */
class MailNoticesTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();

    private final Tenants tenants = mock(Tenants.class);
    private final NotificationTemplates templates = mock(NotificationTemplates.class);
    private final NotificationPreferences preferences = mock(NotificationPreferences.class);
    private final MailComposer composer = MailComposerSupport.composer(mock(Users.class), mock(Invitations.class),
            mock(AccountTokens.class), new MailLinks(new MailProperties(true, "no-reply@example.test",
                    "https://platform.example.test:8443", java.time.Duration.ofSeconds(2), 10,
                    java.time.Duration.ofMinutes(2), 10, java.time.Duration.ofSeconds(30),
                    java.time.Duration.ofMinutes(15), java.time.Duration.ofHours(24), java.time.Duration.ofDays(7),
                    java.time.Duration.ofDays(30)), "platform.example.test"), tenants, templates, preferences);

    private void organization(TenantStatus status) {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, TenantSlug.of("tenant-a"),
                "Organization A", status, Instant.now(), 0)));
    }

    private static ClaimedMail mail(MailTemplate template, UUID user, Map<String, String> variables) {
        return new ClaimedMail(UUID.randomUUID(), template, "user-a@example.test", user, variables, Instant.now(),
                1, 1);
    }

    private static Map<String, String> noticeFacts() {
        return Map.of("organization_id", TENANT.value().toString(), "object", "Order__c", "object_label", "Order",
                "record_id", RECORD.toString(), "workflow_label", "Remind the owner");
    }

    private static Map<String, String> approvalFacts() {
        return Map.of("organization_id", TENANT.value().toString(), "object", "Order__c", "object_label", "Order",
                "record_id", RECORD.toString(), "process_label", "Large order", "step_label", "Manager review");
    }

    @Test
    void theAdministratorsWordsReplaceTheBodyAndTheSubjectAndTheLinkStayThePlatforms() {
        organization(TenantStatus.ACTIVE);
        when(preferences.wantsMail(PERSON, NoticeKind.Category.WORKFLOWS)).thenReturn(true);
        when(templates.find(NoticeKind.WORKFLOW_NOTICE)).thenReturn(Optional.of(new NotificationTemplate(
                NoticeKind.WORKFLOW_NOTICE, "Look", "{workflow} says look at an {object} of {organization}.", 0)));

        Decision.Send send = (Decision.Send) composer.decide(mail(MailTemplate.RECORD_NOTICE, PERSON, noticeFacts()));

        assertThat(send.message().subject()).isEqualTo("A record needs your attention");
        assertThat(send.message().text()).contains("Remind the owner says look at an Order of Organization A.")
                .contains("https://tenant-a.platform.example.test:8443/records/Order__c/" + RECORD)
                .doesNotContain("{");
        assertThat(send.message().html()).contains("Remind the owner says look at an Order of Organization A.");
    }

    @Test
    void withNoTextOfTheirOwnThePlatformsTextIsUsed() {
        organization(TenantStatus.ACTIVE);
        when(preferences.wantsMail(any(), any())).thenReturn(true);
        when(templates.find(any())).thenReturn(Optional.empty());

        Decision.Send send = (Decision.Send) composer.decide(mail(MailTemplate.APPROVAL_REQUEST, PERSON,
                approvalFacts()));

        assertThat(send.outcome()).isEqualTo("approval_request_sent");
        assertThat(send.message().text()).contains("waiting for your decision").contains("Large order")
                .contains("/approvals");
    }

    @Test
    void aLabelInsertedIntoTheirTextIsCleanedLikeEveryOtherName() {
        organization(TenantStatus.ACTIVE);
        when(preferences.wantsMail(any(), any())).thenReturn(true);
        when(templates.find(NoticeKind.APPROVAL_REMINDER)).thenReturn(Optional.of(new NotificationTemplate(
                NoticeKind.APPROVAL_REMINDER, "Still waiting", "Still waiting on {process}.", 0)));

        Map<String, String> facts = new java.util.HashMap<>(approvalFacts());
        facts.put("process_label", "Evil https://evil.example/login\nSecond line");
        Decision.Send send = (Decision.Send) composer.decide(mail(MailTemplate.APPROVAL_REMINDER, PERSON, facts));

        assertThat(send.outcome()).isEqualTo("approval_reminder_sent");
        assertThat(send.message().text()).doesNotContain("evil.example/").doesNotContain("https://evil");
    }

    @Test
    void aPersonWhoSwitchedTheCategoryOffByMailIsSentNothing() {
        organization(TenantStatus.ACTIVE);
        when(preferences.wantsMail(PERSON, NoticeKind.Category.APPROVALS)).thenReturn(false);

        assertThat(composer.decide(mail(MailTemplate.APPROVAL_REQUEST, PERSON, approvalFacts())))
                .isEqualTo(new Decision.Suppress("preference_off"));
        assertThat(composer.decide(mail(MailTemplate.APPROVAL_REMINDER, PERSON, approvalFacts())))
                .isEqualTo(new Decision.Suppress("preference_off"));
    }

    @Test
    void theChoiceIsAskedForTheCategoryOfTheMail() {
        organization(TenantStatus.ACTIVE);
        when(preferences.wantsMail(eq(PERSON), eq(NoticeKind.Category.WORKFLOWS))).thenReturn(false);
        when(preferences.wantsMail(eq(PERSON), eq(NoticeKind.Category.APPROVALS))).thenReturn(true);
        when(templates.find(any())).thenReturn(Optional.empty());

        assertThat(composer.decide(mail(MailTemplate.RECORD_NOTICE, PERSON, noticeFacts())))
                .isEqualTo(new Decision.Suppress("preference_off"));
        assertThat(composer.decide(mail(MailTemplate.APPROVAL_REQUEST, PERSON, approvalFacts())))
                .isInstanceOf(Decision.Send.class);
    }

    @Test
    void theTrialNoticeIsSentToTheAdministratorsOfAnOrganizationThatMayBeSuspendedAndAskedNothing() {
        organization(TenantStatus.SUSPENDED);

        Decision.Send suspended = (Decision.Send) composer.decide(mail(MailTemplate.TRIAL_NOTICE, PERSON,
                Map.of("organization_id", TENANT.value().toString(), "stage", "suspended", "days", "1")));
        Decision.Send ending = (Decision.Send) composer.decide(mail(MailTemplate.TRIAL_NOTICE, PERSON,
                Map.of("organization_id", TENANT.value().toString(), "stage", "first", "days", "7")));

        assertThat(suspended.message().subject()).isEqualTo("Your organization is suspended");
        assertThat(suspended.message().text()).contains("Organization A").contains("Nothing was deleted");
        assertThat(ending.message().subject()).isEqualTo("The trial of your organization is ending");
        assertThat(ending.message().text()).contains("in 7 days").contains("nothing is deleted");
        org.mockito.Mockito.verifyNoInteractions(preferences);
    }

    @Test
    void theDaysOfATrialNoticeAreNumbersAndNothingElse() {
        organization(TenantStatus.ACTIVE);

        Decision.Send send = (Decision.Send) composer.decide(mail(MailTemplate.TRIAL_NOTICE, PERSON,
                Map.of("organization_id", TENANT.value().toString(), "stage", "final", "days", "<b>x</b>")));

        assertThat(send.message().text()).contains("in a few days").doesNotContain("<b>");
    }

    @Test
    void aTrialNoticeForAnOrganizationThatIsGoneOrClosedIsNotSent() {
        organization(TenantStatus.DEACTIVATED);
        assertThat(composer.decide(mail(MailTemplate.TRIAL_NOTICE, PERSON,
                Map.of("organization_id", TENANT.value().toString(), "stage", "first", "days", "7"))))
                .isEqualTo(new Decision.Suppress("organization_not_open"));

        assertThat(composer.decide(mail(MailTemplate.TRIAL_NOTICE, PERSON, Map.of("stage", "first"))))
                .isEqualTo(new Decision.Suppress("notice_unknown"));
    }

    @Test
    void aMailOfAnOrganizationThatIsNotOpenIsNotSentWhateverThePerson() {
        organization(TenantStatus.SUSPENDED);

        assertThat(composer.decide(mail(MailTemplate.APPROVAL_REMINDER, PERSON, approvalFacts())))
                .isEqualTo(new Decision.Suppress("organization_not_open"));
    }
}
