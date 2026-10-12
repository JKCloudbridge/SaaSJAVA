/**
 * Data module: tenant business records (Sprint 14: the generic record store, its recycle bin and its endpoints; Sprint
 * 15: the write pipeline, field security on writes and reads, record types on records, auto-numbers; Sprint 16:
 * queries; Sprint 17: record-level security).
 *
 * <p>The public API of this package is {@link app.platform.data.Records} (what the platform may do with records),
 * {@link app.platform.data.StorageStrategy} (where records are kept; the generic JSON store is the one implementation,
 * a typed or relational store can be added behind it), and the values they exchange. Everything in sub-packages is
 * internal; only the storage implementation names the table. Other modules never read or write the record table.
 *
 * <p>Allowed outgoing dependencies are declared below and verified by the architecture tests. Since Sprint 15 the
 * module also asks {@code observability} to report a validation rule that cannot be evaluated (ADR-0094); it needs
 * nothing from {@code expression}, because the compiled rules and formulas arrive through the runtime contract of
 * {@code metadata} (ADR-0091).
 */
@ApplicationModule(
        displayName = "Data",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security",
            "observability"
        })
package app.platform.data;

import org.springframework.modulith.ApplicationModule;
