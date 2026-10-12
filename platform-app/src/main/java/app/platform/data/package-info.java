/**
 * Data module: tenant business records (Sprint 14: the generic record store, its recycle bin and its endpoints; Sprint
 * 15: the write pipeline; Sprint 16: queries; Sprint 17: record-level security).
 *
 * <p>The public API of this package is {@link app.platform.data.Records} (what the platform may do with records),
 * {@link app.platform.data.StorageStrategy} (where records are kept; the generic JSON store is the one implementation,
 * a typed or relational store can be added behind it), and the values they exchange. Everything in sub-packages is
 * internal; only the storage implementation names the table. Other modules never read or write the record table.
 *
 * <p>Allowed outgoing dependencies are declared below and verified by the architecture tests.
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
