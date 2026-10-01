/**
 * Application module: tenant-created applications and navigation.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Application",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security"
        })
package app.platform.application;

import org.springframework.modulith.ApplicationModule;
