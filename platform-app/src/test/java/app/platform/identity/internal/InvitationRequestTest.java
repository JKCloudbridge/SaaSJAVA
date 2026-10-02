package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asking for an invitation does the same work for every address (ADR-0028, the rule of ADR-0023): a format check, the
 * limits, one invitation row, one queue row and one audit record, and never a look at users, memberships or tokens. The
 * test is the guard: a lookup added to the request path fails it (checked by adding one).
 */
class InvitationRequestTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID INVITATION = UUID.randomUUID();

    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final InvitationRepository invitations = mock(InvitationRepository.class);
    private final AccountTokenRepository tokens = mock(AccountTokenRepository.class);
    private final AccountLimiter limiter = mock(AccountLimiter.class);
    private final MailQueue mail = mock(MailQueue.class);
    private final AuthAudit audit = mock(AuthAudit.class);
    private final TenantContexts contexts = mock(TenantContexts.class);
    private final TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final Administration administration = new Administration(memberships, contexts, transaction, audit);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), java.time.ZoneOffset.UTC);
    private final IdentityProperties properties = new IdentityProperties(null, null, null, null, null, null, false,
            null, null, new IdentityProperties.Account(java.time.Duration.ofHours(24), java.time.Duration.ofMinutes(60),
            5, 10, java.time.Duration.ofHours(1), 5, java.time.Duration.ofHours(1), 20,
            java.time.Duration.ofMinutes(10), 3, 5, java.time.Duration.ofHours(24), java.time.Duration.ofDays(7), 20,
            10, java.time.Duration.ofSeconds(60)));
    private final InvitationService service = new InvitationService(administration, invitations, tokens, limiter,
            mail, audit, contexts, properties, clock);

    InvitationRequestTest() {
        TenantContext context = new TenantContext(new TenantId(TENANT), ADMIN, UUID.randomUUID());
        when(contexts.current()).thenReturn(java.util.Optional.of(context));
        when(contexts.require()).thenReturn(context);
        when(memberships.findOwn(ADMIN)).thenReturn(java.util.Optional.of(
                new MembershipRepository.Own(UUID.randomUUID(), ADMIN, "ACTIVE", true)));
        when(invitations.openOrRenew(any(), eq(false), any(), any())).thenReturn(new InvitationRepository.Invitation(
                INVITATION, "person-a@example.test", false, false, "OPEN", Instant.parse("2026-10-09T10:00:00Z"), 1,
                Instant.parse("2026-10-02T10:00:00Z")));
    }

    @Test
    void anInvitationRequestOnlyCountsStoresQueuesAndAudits() {
        service.invite("  Person-A@Example.TEST ", false);

        verify(limiter).admitInvitation(TENANT, ADMIN, "person-a@example.test");
        verify(invitations).openOrRenew(eq("person-a@example.test"), eq(false), any(), any());
        ArgumentCaptor<MailRequest> queued = ArgumentCaptor.forClass(MailRequest.class);
        verify(mail).enqueue(queued.capture());
        assertThat(queued.getValue().template()).isEqualTo(MailTemplate.INVITATION);
        assertThat(queued.getValue().email()).isEqualTo("person-a@example.test");
        assertThat(queued.getValue().userId()).as("no user is looked up or named").isNull();
        assertThat(queued.getValue().variables()).containsOnlyKeys("organization_id", "invitation_id")
                .containsEntry("organization_id", TENANT.toString())
                .containsEntry("invitation_id", INVITATION.toString());
        verify(audit).invitationRequested(eq(ADMIN), eq(INVITATION), eq("person-a@example.test"), eq(false));
        // Not one question about the person or the membership of the invited address, and no token.
        verify(memberships, never()).exists(any());
        verify(memberships, never()).list();
        verifyNoInteractions(tokens);
    }

    @Test
    void textThatCannotBeAnAddressIsRefusedBeforeAnythingIsCounted() {
        assertThatThrownBy(() -> service.invite("not an address", false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(limiter, mail, invitations);
    }

    @Test
    void aRefusedRequestStoresAndQueuesNothing() {
        org.mockito.Mockito.doThrow(ApiException.rateLimited(60)).when(limiter)
                .admitInvitation(eq(TENANT), eq(ADMIN), any());

        assertThatThrownBy(() -> service.invite("person-a@example.test", false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));

        verifyNoInteractions(invitations, mail);
    }

    @Test
    void aCallerWhoIsNotAnAdministratorIsRefusedBeforeAnythingIsCounted() {
        when(memberships.findOwn(ADMIN)).thenReturn(java.util.Optional.of(
                new MembershipRepository.Own(UUID.randomUUID(), ADMIN, "ACTIVE", false)));

        assertThatThrownBy(() -> service.invite("person-a@example.test", false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(limiter, invitations, mail);
        verify(audit).membershipActionRefused(ADMIN, "invitation.create", "not_an_administrator");
    }
}
