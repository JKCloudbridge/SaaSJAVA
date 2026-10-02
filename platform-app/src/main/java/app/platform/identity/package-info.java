/**
 * Identity module: users, credentials, sign-in, sessions and tokens (Sprint 3), memberships and invitations (Sprint 5).
 * Since Sprint 7 it asks the security module what a member may do and what a new member gets (ADR-0039).
 *
 * <p>The public API is {@link app.platform.identity.Users} (create users, change their state and password, end their
 * sessions), the user types, and the authentication-provider abstraction
 * ({@link app.platform.identity.PlatformAuthenticationProvider} with its attempt and outcome types), which is the
 * extension point for external identity providers and a second factor. Passwords, tokens, the authorization server and
 * the security filter chains are internal (ADR-0019 to ADR-0022).
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "licensing",
            "security"
        })
package app.platform.identity;

import org.springframework.modulith.ApplicationModule;
