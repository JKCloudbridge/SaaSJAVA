/**
 * Metadata module: object, field, layout and application definitions with versioning.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Metadata",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "security"
        })
package app.platform.metadata;

import org.springframework.modulith.ApplicationModule;
