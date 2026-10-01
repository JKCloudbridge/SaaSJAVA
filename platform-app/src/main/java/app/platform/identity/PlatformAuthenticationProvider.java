package app.platform.identity;

/**
 * One way of proving identity (ADR-0005). The local password provider is the only implementation in Sprint 3.
 *
 * <p><strong>Extension point.</strong> An external identity provider (OIDC, SAML) or a second factor is another
 * implementation of this interface, plus a new permitted {@link AuthenticationAttempt} type. It does not change the
 * adapter that connects providers to Spring Security, the token issuing, the lock or the audit records, because those
 * only see {@link AuthenticationOutcome}. Not implemented in this sprint.
 *
 * <p>Implementations must (a) do the same amount of work on every path, so that timing does not reveal whether an
 * account exists, is locked or disabled (story S3-SEC-06); (b) never put password material into an outcome, an
 * exception message or a log line; (c) report the true reason in {@link AuthenticationOutcome.Rejected} and leave
 * the uniform answer to the caller.
 */
public interface PlatformAuthenticationProvider {

    /** A stable identifier, written to the audit record, for example {@code local}. */
    String id();

    /** Whether this provider handles the kind of attempt. */
    boolean supports(AuthenticationAttempt attempt);

    /** Decides the attempt. */
    AuthenticationOutcome authenticate(AuthenticationAttempt attempt);
}
