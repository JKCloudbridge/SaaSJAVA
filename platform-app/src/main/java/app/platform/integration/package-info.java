/**
 * Integration module: the connector framework and adapters; the only module that knows external systems (Sprint 26,
 * ADR-0207 to ADR-0211).
 *
 * <p>The public API is {@link app.platform.integration.Integrations}: which switched-on connections listen for an
 * event and what a switched-on connection may touch. Everything else is internal. The adapter seam is
 * {@code app.platform.integration.adapter}, which only this module may reference (an architecture test), so a core
 * module never names an external system; the sample connector is in {@code app.platform.integration.adapter.rest}.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests. Sprint 26 added
 * {@code licensing} (the plan feature) and {@code observability} (error tracking); the module still does not depend on
 * {@code data}, because nothing sends or receives a record before Sprint 28.
 */
@ApplicationModule(
        displayName = "Integration",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security",
            "data",
            "licensing",
            "observability"
        })
package app.platform.integration;

import org.springframework.modulith.ApplicationModule;
