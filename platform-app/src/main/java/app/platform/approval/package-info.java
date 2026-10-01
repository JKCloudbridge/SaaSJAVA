/**
 * Approval module: approval definitions, instances and history.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Approval",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security",
            "data",
            "notification"
        })
package app.platform.approval;

import org.springframework.modulith.ApplicationModule;
