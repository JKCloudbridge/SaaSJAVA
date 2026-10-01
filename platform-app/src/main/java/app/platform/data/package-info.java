/**
 * Data module: tenant business records, queries, record-level security.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Data",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security"
        })
package app.platform.data;

import org.springframework.modulith.ApplicationModule;
