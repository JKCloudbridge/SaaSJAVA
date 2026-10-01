/**
 * Integration module: connector framework and adapters; the only module that knows external systems.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Integration",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security",
            "data"
        })
package app.platform.integration;

import org.springframework.modulith.ApplicationModule;
