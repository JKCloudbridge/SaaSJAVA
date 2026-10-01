/**
 * Audit module: append-only audit records of security, configuration and data changes.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Audit",
        allowedDependencies = {
            "sharedkernel"
        })
package app.platform.audit;

import org.springframework.modulith.ApplicationModule;
