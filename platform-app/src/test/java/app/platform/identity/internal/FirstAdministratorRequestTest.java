package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.platform.identity.Users;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A platform administrator's invitation of an organization's first administrator does the same work for every address
 * (ADR-0037, the rule of ADR-0023 and ADR-0028): a format check, the limits, one invitation row, one queue row and one
 * audit record, and never a look at users or memberships. What is mailed is decided later, at send time. The test is
 * the guard: a lookup of the user added to the request path fails it (checked by adding one).
 */
class FirstAdministratorRequestTest {

    private static final TenantId ORGANIZATION = new TenantId(UUID.randomUUID());
    private static final UUID PLATFORM_ADMIN = UUID.randomUUID();
    private static final UUID INVITATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private final InvitationRepository invitations = mock(InvitationRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final AccountTokenRepository tokens = mock(AccountTokenRepository.class);
    private final Users users = mock(Users.class);
    private final Tenants tenants = mock(Tenants.class);
    private final TenantContexts contexts = mock(TenantContexts.class);
    private final TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final AccountLimiter limiter = mock(AccountLimiter.class);
    private final MailQueue mail = mock(MailQueue.class);
    private final AuthAudit audit = mock(AuthAudit.class);
    private final Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
    private final IdentityProperties properties = new IdentityProperties(null, null, null, null, null, null, false,
            null, null, new IdentityProperties.Account(Duration.ofHours(24), Duration.ofMinutes(60), 5, 10,
            Duration.ofHours(1), 5, Duration.ofHours(1), 20, Duration.ofMinutes(10), 3, 5, Duration.ofHours(24),
            Duration.ofDays(7), 20, 10, Duration.ofSeconds(60)));
    private final DefaultInvitations service = new DefaultInvitations(invitations, memberships, tokens, users, tenants,
            contexts, transaction, limiter, mail, audit, properties, clock);

    FirstAdministratorRequestTest() {
        when(tenants.findById(ORGANIZATION)).thenReturn(Optional.of(new Tenant(ORGANIZATION,
                new TenantSlug("client-a"), "Client A", TenantStatus.PROVISIONING, NOW, 0)));
        when(contexts.call(any(TenantContext.class), any())).thenAnswer(call ->
                ((Supplier<?>) call.getArgument(1)).get());
        when(invitations.openOrRenew(any(), eq(true), eq(true), eq(true), any(), any())).thenReturn(
                new InvitationRepository.Invitation(INVITATION, "person-a@example.test", true, true, "OPEN",
                        NOW.plus(Duration.ofDays(7)), 1, NOW, true));
    }

    @Test
    void theRequestOnlyCountsStoresQueuesAndAuditsAndNeverLooksAtAnAccount() {
        service.inviteFirstAdministrator(ORGANIZATION, "  Person-A@Example.TEST ", PLATFORM_ADMIN);

        verify(limiter).admitInvitation(ORGANIZATION.value(), PLATFORM_ADMIN, "person-a@example.test");
        verify(invitations).openOrRenew(eq("person-a@example.test"), eq(true), eq(true), eq(true), any(), any());
        ArgumentCaptor<MailRequest> queued = ArgumentCaptor.forClass(MailRequest.class);
        verify(mail).enqueue(queued.capture());
        assertThat(queued.getValue().template()).isEqualTo(MailTemplate.INVITATION);
        assertThat(queued.getValue().userId()).as("no user is looked up or named").isNull();
        assertThat(queued.getValue().variables()).containsOnlyKeys("organization_id", "invitation_id");
        verify(audit).firstAdministratorInvited(eq(PLATFORM_ADMIN), eq(INVITATION), eq("person-a@example.test"));
        // Not one question about the person, the memberships or a token.
        verifyNoInteractions(users, memberships, tokens);
    }

    @Test
    void textThatCannotBeAnAddressIsRefusedBeforeAnythingIsCounted() {
        assertThatThrownBy(() -> service.inviteFirstAdministrator(ORGANIZATION, "not an address", PLATFORM_ADMIN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(limiter, mail, invitations, users);
    }

    @Test
    void aClosedOrUnknownOrganizationTakesNoFirstAdministrator() {
        TenantId closed = new TenantId(UUID.randomUUID());
        when(tenants.findById(closed)).thenReturn(Optional.of(new Tenant(closed, new TenantSlug("closed-a"),
                "Closed A", TenantStatus.SUSPENDED, NOW, 0)));

        assertThatThrownBy(() -> service.inviteFirstAdministrator(closed, "person-a@example.test", PLATFORM_ADMIN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.inviteFirstAdministrator(new TenantId(UUID.randomUUID()),
                "person-a@example.test", PLATFORM_ADMIN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(limiter, mail, invitations, users);
    }

    @Test
    void aRefusedRequestStoresAndQueuesNothing() {
        org.mockito.Mockito.doThrow(ApiException.rateLimited(60)).when(limiter)
                .admitInvitation(eq(ORGANIZATION.value()), eq(PLATFORM_ADMIN), any());

        assertThatThrownBy(() -> service.inviteFirstAdministrator(ORGANIZATION, "person-a@example.test",
                PLATFORM_ADMIN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));

        verifyNoInteractions(invitations, mail);
    }
}
