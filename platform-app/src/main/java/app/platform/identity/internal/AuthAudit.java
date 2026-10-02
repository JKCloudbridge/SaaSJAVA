package app.platform.identity.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the authentication audit records (story S3-SEC-22): one place that names the event types and decides what each
 * carries, so that a record can never hold secret material (the record type refuses secret-looking keys) and every
 * record names the tenant of the request when there is one (none on the platform host).
 *
 * <p>Failures carry their true internal reason; the caller of the API never sees it. An unknown identifier is recorded
 * as a hash, never as the typed text: a mistyped password in the address field must not end up in the trail.
 */
@Component
class AuthAudit {

    private final AuditRecorder recorder;
    private final TenantContexts contexts;

    AuthAudit(AuditRecorder recorder, TenantContexts contexts) {
        this.recorder = recorder;
        this.contexts = contexts;
    }

    void signInSucceeded(UUID userId, String provider, String source) {
        write(AuditRecord.of("auth.sign_in.succeeded", AuditOutcome.SUCCESS).forUser(userId)
                .with("provider", provider).with("source", source));
    }

    void signInFailed(UUID userIdOrNull, String identifier, String provider, String reason, String source) {
        write(AuditRecord.of("auth.sign_in.failed", AuditOutcome.FAILURE).forUser(userIdOrNull).because(reason)
                .with("provider", provider).with("source", source)
                .with("identifier_hash", Hashes.sha256Hex(identifier.strip().toLowerCase(java.util.Locale.ROOT))));
    }

    void rateLimited(String limit, String source) {
        write(AuditRecord.of("auth.sign_in.rate_limited", AuditOutcome.DENIED).because(limit).with("source", source));
    }

    void accountLocked(UUID userId, long lockSeconds, String source) {
        write(AuditRecord.of("auth.account.locked", AuditOutcome.DENIED).forUser(userId).because("too_many_failures")
                .with("lock_seconds", Long.toString(lockSeconds)).with("source", source));
    }

    void passwordHashUpgraded(UUID userId) {
        write(AuditRecord.of("auth.password.hash_upgraded", AuditOutcome.SUCCESS).forUser(userId));
    }

    void passwordChanged(UUID actorOrSubject, String how) {
        write(AuditRecord.of("auth.password.changed", AuditOutcome.SUCCESS).forUser(actorOrSubject).with("how", how));
    }

    void passwordChangeRefused(UUID userId, String reason) {
        write(AuditRecord.of("auth.password.change_refused", AuditOutcome.FAILURE).forUser(userId).because(reason));
    }

    void tokenIssued(UUID userId, String grant, String clientId) {
        write(AuditRecord.of("auth.token.issued", AuditOutcome.SUCCESS).forUser(userId)
                .with("grant", grant).with("client", clientId));
    }

    void tokenRefreshed(UUID userId) {
        write(AuditRecord.of("auth.token.refreshed", AuditOutcome.SUCCESS).forUser(userId));
    }

    void refreshReuseDetected(UUID userId) {
        write(AuditRecord.of("auth.refresh.reuse_detected", AuditOutcome.DENIED).forUser(userId)
                .because("rotated_token_replayed"));
    }

    void tokenRefused(UUID userId, String reason) {
        write(AuditRecord.of("auth.token.refused", AuditOutcome.DENIED).forUser(userId).because(reason));
    }

    void sessionRevoked(UUID userId, String reason, int count) {
        write(AuditRecord.of("auth.session.revoked", AuditOutcome.SUCCESS).forUser(userId).because(reason)
                .with("count", Integer.toString(count)));
    }

    void signedOut(UUID userId) {
        write(AuditRecord.of("auth.sign_out", AuditOutcome.SUCCESS).forUser(userId));
    }

    void signedOutEverywhere(UUID userId, UUID actor) {
        write(AuditRecord.of("auth.sign_out_all", AuditOutcome.SUCCESS).forUser(userId)
                .with("actor", actor == null ? "system" : actor.toString()));
    }

    void userStatusChanged(UUID userId, String from, String to, UUID actor) {
        write(AuditRecord.of("auth.user.status_changed", AuditOutcome.SUCCESS).forUser(userId)
                .with("from", from).with("to", to).with("actor", actor.toString()));
    }

    void userCreated(UUID userId, UUID actor) {
        write(AuditRecord.of("auth.user.created", AuditOutcome.SUCCESS).forUser(userId)
                .with("actor", actor.toString()));
    }

    /** A sign-up was requested. Identical for every address: only a hash of the address is kept. */
    void signUpRequested(String email, String source) {
        write(AuditRecord.of("auth.sign_up.requested", AuditOutcome.SUCCESS)
                .with("identifier_hash", Hashes.sha256Hex(email)).with("source", source));
    }

    void signUpCompleted(UUID userId) {
        write(AuditRecord.of("auth.sign_up.completed", AuditOutcome.SUCCESS).forUser(userId));
    }

    /** A link was refused: unknown, used, cancelled or expired (never says which to the caller). */
    void linkRefused(String purpose, String reason, String source) {
        write(AuditRecord.of("auth.link.refused", AuditOutcome.DENIED).because(reason)
                .with("purpose", purpose).with("source", source));
    }

    void tokenCreated(String purpose, UUID userIdOrNull) {
        write(AuditRecord.of("auth.link.created", AuditOutcome.SUCCESS).forUser(userIdOrNull)
                .with("purpose", purpose));
    }

    /** A password reset was requested. Identical for every address: only a hash of the address is kept. */
    void passwordResetRequested(String email, String source) {
        write(AuditRecord.of("auth.password_reset.requested", AuditOutcome.SUCCESS)
                .with("identifier_hash", Hashes.sha256Hex(email)).with("source", source));
    }

    void passwordResetCompleted(UUID userId) {
        write(AuditRecord.of("auth.password_reset.completed", AuditOutcome.SUCCESS).forUser(userId));
    }

    void organizationFounded(UUID userId, String slug) {
        write(AuditRecord.of("tenant.organization.founded", AuditOutcome.SUCCESS).forUser(userId)
                .with("slug", slug));
    }

    // ---- membership, invitations and switching (Sprint 5); the tenant of the record is the organization ----

    /** An invitation was requested. Identical for every address: only a hash of the address is kept. */
    void invitationRequested(UUID inviter, UUID invitationId, String email, UUID profile, boolean sent) {
        write(AuditRecord.of("membership.invitation.requested", AuditOutcome.SUCCESS).forUser(inviter)
                .with("invitation", invitationId.toString()).with("identifier_hash", Hashes.sha256Hex(email))
                .with("profile", profile.toString()).with("sent", Boolean.toString(sent)));
    }

    void invitationResent(UUID inviter, UUID invitationId) {
        write(AuditRecord.of("membership.invitation.resent", AuditOutcome.SUCCESS).forUser(inviter)
                .with("invitation", invitationId.toString()));
    }

    void invitationRevoked(UUID inviter, UUID invitationId) {
        write(AuditRecord.of("membership.invitation.revoked", AuditOutcome.SUCCESS).forUser(inviter)
                .with("invitation", invitationId.toString()));
    }

    void invitationAccepted(UUID user, UUID invitationId, UUID membershipId, boolean newAccount) {
        write(AuditRecord.of("membership.invitation.accepted", AuditOutcome.SUCCESS).forUser(user)
                .with("invitation", invitationId.toString()).with("membership", membershipId.toString())
                .with("new_account", Boolean.toString(newAccount)));
    }

    /** The first administrator accepted and so opened an organization that a platform administrator had set up. */
    void organizationOpened(UUID user) {
        write(AuditRecord.of("tenant.organization.opened", AuditOutcome.SUCCESS).forUser(user)
                .with("how", "first_administrator_accepted"));
    }

    void membershipDeactivated(UUID actor, UUID subject, UUID membershipId, int sessionsEnded) {
        write(AuditRecord.of("membership.deactivated", AuditOutcome.SUCCESS).forUser(subject)
                .with("actor", actor.toString()).with("membership", membershipId.toString())
                .with("sessions_ended", Integer.toString(sessionsEnded)));
    }

    void membershipReactivated(UUID actor, UUID subject, UUID membershipId) {
        write(AuditRecord.of("membership.reactivated", AuditOutcome.SUCCESS).forUser(subject)
                .with("actor", actor.toString()).with("membership", membershipId.toString()));
    }

    /** A member left the organization by themselves (Sprint 7). */
    void membershipLeft(UUID user, UUID membershipId, int sessionsEnded) {
        write(AuditRecord.of("membership.left", AuditOutcome.SUCCESS).forUser(user)
                .with("membership", membershipId.toString()).with("sessions_ended", Integer.toString(sessionsEnded)));
    }

    void licenceAssigned(UUID actor, UUID subject, UUID membershipId, String licenceType) {
        write(AuditRecord.of("membership.licence.assigned", AuditOutcome.SUCCESS).forUser(subject)
                .with("actor", actor.toString()).with("membership", membershipId.toString())
                .with("licence_type", licenceType));
    }

    void licenceReleased(UUID actor, UUID subject, UUID membershipId, String why) {
        write(AuditRecord.of("membership.licence.released", AuditOutcome.SUCCESS).forUser(subject).because(why)
                .with("actor", actor.toString()).with("membership", membershipId.toString()));
    }

    /** An administrative action was refused (not an administrator, last administrator, ...). */
    void membershipActionRefused(UUID actor, String action, String reason) {
        write(AuditRecord.of("membership.action.refused", AuditOutcome.DENIED).forUser(actor).because(reason)
                .with("action", action));
    }

    void switchRequested(UUID userId, UUID targetTenant) {
        write(AuditRecord.of("auth.organization.switch_requested", AuditOutcome.SUCCESS).forUser(userId)
                .with("target_tenant", targetTenant.toString()));
    }

    void switchRefused(UUID userId, String reason, String source) {
        write(AuditRecord.of("auth.organization.switch_refused", AuditOutcome.DENIED).forUser(userId).because(reason)
                .with("source", source));
    }

    void switched(UUID userId) {
        write(AuditRecord.of("auth.organization.switched", AuditOutcome.SUCCESS).forUser(userId));
    }

    void bindingRefused(UUID userId, String what) {
        write(AuditRecord.of("auth.token.refused", AuditOutcome.DENIED).forUser(userId).because("wrong_host")
                .with("what", what));
    }

    // ---- platform roles, first administrators and administrative sessions (Sprint 6, ADR-0030, ADR-0036) ----

    void platformRoleGranted(UUID actor, UUID subject, String role) {
        write(AuditRecord.of("platform.role.granted", AuditOutcome.SUCCESS).forUser(actor)
                .with("subject", subject.toString()).with("role", role).with("how", "console"));
    }

    void platformRoleRevoked(UUID actor, UUID subject, String role) {
        write(AuditRecord.of("platform.role.revoked", AuditOutcome.SUCCESS).forUser(actor)
                .with("subject", subject.toString()).with("role", role));
    }

    /** A caller without the needed platform role asked for a platform action. */
    void platformActionRefused(UUID user, List<String> required) {
        write(AuditRecord.of("platform.action.refused", AuditOutcome.DENIED).forUser(user)
                .because("no_platform_role").with("required", String.join(",", required)));
    }

    void platformActionRefusedWith(UUID actor, String action, String reason) {
        write(AuditRecord.of("platform.action.refused", AuditOutcome.DENIED).forUser(actor).because(reason)
                .with("action", action));
    }

    /** A person who holds platform roles signed in on the platform host: recorded loudly, on purpose. */
    void platformSignIn(UUID user, List<String> roles, String source) {
        write(AuditRecord.of("platform.sign_in.succeeded", AuditOutcome.SUCCESS).forUser(user)
                .with("roles", String.join(",", roles)).with("source", source));
    }

    /** A platform administrator invited (or re-invited) the first administrator of an organization. */
    void firstAdministratorInvited(UUID actor, UUID invitationId, String email) {
        write(AuditRecord.of("platform.organization.first_administrator.invited", AuditOutcome.SUCCESS).forUser(actor)
                .with("invitation", invitationId.toString()).with("identifier_hash", Hashes.sha256Hex(email)));
    }

    void firstAdministratorResent(UUID actor, UUID invitationId) {
        write(AuditRecord.of("platform.organization.first_administrator.resent", AuditOutcome.SUCCESS).forUser(actor)
                .with("invitation", invitationId.toString()));
    }

    void invitationsRevokedByPlatform(UUID actor, int count) {
        write(AuditRecord.of("platform.organization.invitations.revoked", AuditOutcome.SUCCESS).forUser(actor)
                .with("count", Integer.toString(count)));
    }

    /** Somebody with a platform role looked at the sessions of a person (the address is kept only as a hash). */
    void sessionsListed(UUID actor, String email) {
        write(AuditRecord.of("platform.sessions.listed", AuditOutcome.SUCCESS).forUser(actor)
                .with("identifier_hash", Hashes.sha256Hex(email)));
    }

    void signedOutEverywhereByPlatform(UUID actor, String email, int count) {
        write(AuditRecord.of("platform.sessions.signed_out_everywhere", AuditOutcome.SUCCESS).forUser(actor)
                .with("identifier_hash", Hashes.sha256Hex(email)).with("count", Integer.toString(count)));
    }

    /** Everyone of an organization was signed out, by a platform person or by the organization's administrator. */
    void organizationSignedOut(UUID actor, UUID organization, int count, boolean byPlatform) {
        write(AuditRecord.of("auth.organization.signed_out_all", AuditOutcome.SUCCESS).forUser(actor)
                .with("target_tenant", organization.toString()).with("count", Integer.toString(count))
                .with("by", byPlatform ? "platform" : "administrator"));
    }

    private void write(AuditRecord record) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        recorder.record(record.inTenant(tenant));
    }
}
