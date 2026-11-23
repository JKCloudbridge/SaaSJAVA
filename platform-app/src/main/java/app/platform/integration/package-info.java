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
 * {@code licensing} (the plan feature) and {@code observability} (error tracking). Sprint 28 uses the edges to
 * {@code data} (the platform's own reads and writes of records), {@code metadata} (the fields of an object) and the new
 * edge to {@code expression} (the transformations of a mapping): mapping, the links between a record and what it is on
 * the other side, the outbound sends and the public receiver of signed changes (ADR-0216 to ADR-0224).
 *
 * <p>Sprint 32 adds the webhooks (ADR-0274 to ADR-0279): standing instructions of an organization to be told, at an
 * address of its own, with a small signed message of identifiers, when records change. They are not connectors: a
 * subscription is a tenant row of its own, its deliveries are queued work like the sends of Sprint 29 (the same pure
 * classes), and the call goes through the one outbound client. No module edge was added.
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
            "expression",
            "observability"
        })
package app.platform.integration;

import org.springframework.modulith.ApplicationModule;
