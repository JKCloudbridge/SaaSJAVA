/**
 * Security module: profiles, roles, permission sets, groups and the authorization decision engine.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Security",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "identity"
        })
package app.platform.security;

import org.springframework.modulith.ApplicationModule;
