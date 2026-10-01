/**
 * Outbox module: the transactional outbox, the polling relay and the idempotent consumer base (decision D3,
 * ADR-0016). Infrastructure, like {@code web} and {@code observability}.
 *
 * <p>Other modules do not depend on it. They announce events through
 * {@link app.platform.sharedkernel.events.EventPublisher} and react through
 * {@link app.platform.sharedkernel.events.EventHandler} beans, both in the shared kernel; this module supplies the
 * publisher and finds the handlers. Everything here is internal. Allowed outgoing dependencies are declared below and
 * verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Outbox",
        allowedDependencies = {
            "sharedkernel", "tenant", "observability"
        })
package app.platform.outbox;

import org.springframework.modulith.ApplicationModule;
