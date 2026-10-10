/**
 * Metadata module: object and field definitions (Sprint 10), later layouts, applications and versioning.
 *
 * <p>What exists for an organization: the standard objects and fields the platform defines (read from files inside the
 * application, protected, ADR-0059) and the objects and fields the organization defines for itself (tenant-scoped
 * tables, ADR-0058). {@link app.platform.metadata.Metadata} is the one read contract; the security module reads the
 * same catalogue through its {@code ObjectCatalog} contract, which this module implements.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Metadata",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "security",
            "identity"
        })
package app.platform.metadata;

import org.springframework.modulith.ApplicationModule;
