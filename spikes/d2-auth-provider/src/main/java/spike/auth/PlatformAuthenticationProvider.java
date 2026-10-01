package spike.auth;

/**
 * The platform's own authentication-provider abstraction (decision D2).
 *
 * <p>Local credentials implement it today; federated OIDC, SAML and MFA step-up are further
 * implementations later. Spring Security and the authorization server only ever see the single
 * adapter in {@link PlatformProviderAdapter}.
 */
public interface PlatformAuthenticationProvider {

    String id();

    boolean supports(AuthenticationAttempt attempt);

    AuthenticationOutcome authenticate(AuthenticationAttempt attempt);
}
