/**
 * Identity module: users, credentials, authentication, memberships, invitations.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {
            "sharedkernel",
            "tenant"
        })
package app.platform.identity;

import org.springframework.modulith.ApplicationModule;
