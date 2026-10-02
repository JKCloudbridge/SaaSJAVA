package app.platform.identity.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
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

    void bindingRefused(UUID userId, String what) {
        write(AuditRecord.of("auth.token.refused", AuditOutcome.DENIED).forUser(userId).because("wrong_host")
                .with("what", what));
    }

    private void write(AuditRecord record) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        recorder.record(record.inTenant(tenant));
    }
}
