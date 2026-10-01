/**
 * Platform Admin module: platform-level administration, separate from tenant administration.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Platform Admin",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "identity",
            "licensing",
            "security",
            "audit"
        })
package app.platform.platformadmin;

import org.springframework.modulith.ApplicationModule;
