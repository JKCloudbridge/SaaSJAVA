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
 * The mail a workflow asks for (ADR-0138): a record needs a person's attention. The queue holds facts, the relay
 * builds the words and the link to the record on the organization's own host when it sends, nothing of the record is
 * in either, and nothing is sent for an organization that is not open or a request that is not what the platform
 * made.
 */
class RecordNoticeMailTest {

    private static final UUID RECORD = UUID.randomUUID();
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
        return new ClaimedMail(UUID.randomUUID(), MailTemplate.RECORD_NOTICE, "user-a@example.test", null, variables,
                Instant.now(), 1, 1);
    }

    private static Map<String, String> facts() {
        return Map.of("organization_id", TENANT.value().toString(), "object", "Order__c", "object_label", "Order",
                "record_id", RECORD.toString());
    }

    @Test
    void theMessageLinksToTheRecordOnTheOrganizationsOwnHostWithTheSchemeAndPortOfThePlatform() {
        organization(TenantStatus.ACTIVE, "Organization A");

        Decision.Send send = (Decision.Send) composer.decide(mail(facts()));

        assertThat(send.outcome()).isEqualTo("notice_sent");
        assertThat(send.message().to()).isEqualTo("user-a@example.test");
        assertThat(send.message().subject()).isEqualTo("A record needs your attention");
        assertThat(send.message().text()).contains("https://tenant-a.platform.example.test:8443/records/Order__c/"
                + RECORD).contains("Organization A").contains("Order").doesNotContain("{{");
        assertThat(send.message().html()).contains("<a href=\"https://tenant-a.platform.example.test:8443/records/");
    }

    @Test
    void theNamesAreCleanedLikeEveryOtherNameFromAnAdministrator() {
        organization(TenantStatus.ACTIVE, "A https://evil.example/login name");

        Decision.Send send = (Decision.Send) composer.decide(mail(Map.of("organization_id", TENANT.value().toString(),
                "object", "Order__c", "object_label", "Order\nSecond line http://evil.example", "record_id",
                RECORD.toString())));

        assertThat(send.message().text()).doesNotContain("evil.example").doesNotContain("evil.example/")
                .doesNotContain("Order\nSecond");
    }

    @Test
    void nothingIsSentForAnOrganizationThatIsNotOpenOrIsGone() {
        organization(TenantStatus.SUSPENDED, "Organization A");
        assertThat(composer.decide(mail(facts()))).isEqualTo(new Decision.Suppress("organization_not_open"));

        when(tenants.findById(TENANT)).thenReturn(Optional.empty());
        assertThat(composer.decide(mail(facts()))).isEqualTo(new Decision.Suppress("organization_not_open"));
    }

    @Test
    void nothingIsSentForARequestThatIsNotWhatThePlatformMade() {
        organization(TenantStatus.ACTIVE, "Organization A");

        for (Map<String, String> bad : java.util.List.of(
                Map.<String, String>of(),
                Map.of("organization_id", "nonsense", "object", "Order__c", "record_id", RECORD.toString()),
                Map.of("organization_id", TENANT.value().toString(), "object", "../etc", "record_id",
                        RECORD.toString()),
                Map.of("organization_id", TENANT.value().toString(), "object", "Order__c", "record_id", "x"))) {
            assertThat(composer.decide(mail(bad))).as(bad.toString())
                    .isEqualTo(new Decision.Suppress("notice_unknown"));
        }
    }

    @Test
    void theRequestHoldsFactsOnlyAndRefusesALinkOrAValueInItsVariables() {
        MailRequest request = new MailRequest(MailTemplate.RECORD_NOTICE, "user-a@example.test", null, facts());

        assertThat(request.variables()).containsOnlyKeys("organization_id", "object", "object_label", "record_id");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> request.with("link", "https://example.test"));
        assertThat(request.toString()).doesNotContain("user-a@example.test");
    }
}
