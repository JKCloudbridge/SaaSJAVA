package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.platform.identity.AccountTokens;
import app.platform.identity.Invitations;
import app.platform.identity.Users;
import app.platform.notification.internal.MailComposer.Decision;
import app.platform.notification.internal.MailStore.ClaimedMail;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
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
 * The mail that tells a person they have been asked to decide (ADR-0152): facts in the queue, the words and the link to
 * the inbox built when it is sent, no title and no value of the record anywhere, names cleaned like every name an
 * administrator typed, and a decision is never made from the link.
 */
class ApprovalRequestMailTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private final Tenants tenants = mock(Tenants.class);
    private final MailComposer composer = MailComposerSupport.composer(mock(Users.class), mock(Invitations.class),
            mock(AccountTokens.class), new MailLinks(new MailProperties(true, "no-reply@example.test",
                    "https://platform.example.test:8443", java.time.Duration.ofSeconds(2), 10,
                    java.time.Duration.ofMinutes(2), 10, java.time.Duration.ofSeconds(30),
                    java.time.Duration.ofMinutes(15), java.time.Duration.ofHours(24), java.time.Duration.ofDays(7),
                    java.time.Duration.ofDays(30)), "platform.example.test"), tenants);

    private void organization(TenantStatus status, String name) {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, TenantSlug.of("tenant-a"), name,
                status, Instant.now(), 0)));
    }

    private static ClaimedMail mail(Map<String, String> variables) {
        return new ClaimedMail(UUID.randomUUID(), MailTemplate.APPROVAL_REQUEST, "user-a@example.test", null,
                variables, Instant.now(), 1, 1);
    }

    private static Map<String, String> facts(String process) {
        return Map.of("organization_id", TENANT.value().toString(), "object", "Order__c", "object_label", "Order",
                "record_id", UUID.randomUUID().toString(), "process_label", process);
    }

    @Test
    void theMessageLinksToTheInboxOnTheOrganizationsOwnHostAndNamesTheProcess() {
        organization(TenantStatus.ACTIVE, "Organization A");

        Decision.Send send = (Decision.Send) composer.decide(mail(facts("Large order")));

        assertThat(send.outcome()).isEqualTo("approval_request_sent");
        assertThat(send.message().to()).isEqualTo("user-a@example.test");
        assertThat(send.message().subject()).isEqualTo("A record needs your decision");
        assertThat(send.message().text()).contains("https://tenant-a.platform.example.test:8443/approvals")
                .contains("Organization A").contains("Large order").contains("Order").doesNotContain("{{")
                .doesNotContain("/records/");
        assertThat(send.message().html()).contains("<a href=\"https://tenant-a.platform.example.test:8443/approvals");
    }

    @Test
    void theNamesAreCleanedAndTheSubjectHoldsNone() {
        organization(TenantStatus.ACTIVE, "A https://evil.example/login name");

        Decision.Send send = (Decision.Send) composer.decide(mail(facts("Big http://evil.example\nsecond line")));

        assertThat(send.message().text()).doesNotContain("evil.example").doesNotContain("evil.example/");
        assertThat(send.message().subject()).doesNotContain("Organization").doesNotContain("Big");
    }

    @Test
    void nothingIsSentForAnOrganizationThatIsNotOpenOrIsGoneOrForARequestThatIsNotWhatThePlatformMade() {
        organization(TenantStatus.SUSPENDED, "Organization A");
        assertThat(composer.decide(mail(facts("Large")))).isEqualTo(new Decision.Suppress("organization_not_open"));

        when(tenants.findById(TENANT)).thenReturn(Optional.empty());
        assertThat(composer.decide(mail(facts("Large")))).isEqualTo(new Decision.Suppress("organization_not_open"));

        assertThat(composer.decide(mail(Map.of()))).isEqualTo(new Decision.Suppress("notice_unknown"));
        assertThat(composer.decide(mail(Map.of("organization_id", "nonsense"))))
                .isEqualTo(new Decision.Suppress("notice_unknown"));
    }

    @Test
    void theRequestHoldsFactsOnlyAndRefusesALinkATokenOrAValueInItsVariables() {
        MailRequest request = new MailRequest(MailTemplate.APPROVAL_REQUEST, "user-a@example.test", null,
                facts("Large"));

        assertThat(request.variables()).containsOnlyKeys("organization_id", "object", "object_label", "record_id",
                "process_label");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> request.with("link", "https://example.test"));
        assertThat(request.toString()).doesNotContain("user-a@example.test");
    }
}
