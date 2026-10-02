/**
 * Licensing module: plans, licence types, pools and assignments, subscriptions and trials, feature entitlements
 * (Sprint 6, ADR-0031 to ADR-0034).
 *
 * <p>The public API is {@link app.platform.licensing.Licences} (an organization's pools and assignments),
 * {@link app.platform.licensing.Subscriptions}, {@link app.platform.licensing.Entitlements} (the read contract
 * other modules use) and {@link app.platform.licensing.Plans} (the catalogue), with their view types. The module
 * depends only on the tenant module: the identity module calls it (a licence is released in the transaction that
 * deactivates a member), never the other way round.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Licensing",
        allowedDependencies = {
            "sharedkernel",
            "tenant"
        })
package app.platform.licensing;

import org.springframework.modulith.ApplicationModule;
