/**
 * Licensing module: plans, licence pools, feature entitlements.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Licensing",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "identity"
        })
package app.platform.licensing;

import org.springframework.modulith.ApplicationModule;
