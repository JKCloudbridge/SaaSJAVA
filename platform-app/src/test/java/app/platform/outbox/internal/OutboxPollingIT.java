package app.platform.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * The relay as a deployment runs it: started with the application, polling in the background, with no help from the
 * test. The tenant lifecycle events written by the real tenant service are delivered to a handler with the context of
 * the tenant they belong to (end to end through every part of the sprint).
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms"})
class OutboxPollingIT {

    /** What the handler saw of one event. */
    record Handled(String type, UUID tenant, Optional<TenantContext> context, String databaseTenant) {
    }

    static final class TenantEvents implements EventHandler {

        final List<Handled> handled = new CopyOnWriteArrayList<>();
        private final TenantContexts contexts;
        private final JdbcClient jdbc;

        TenantEvents(TenantContexts contexts, JdbcClient jdbc) {
            this.contexts = contexts;
            this.jdbc = jdbc;
        }

        @Override
        public String consumerName() {
            return "test.tenant-events";
        }

        @Override
        public Set<String> eventTypes() {
            return Set.of("tenant.provisioned", "tenant.activated", "tenant.suspended", "tenant.reinstated",
                    "tenant.deactivated");
        }

        @Override
        public void handle(EventEnvelope event) {
            String databaseTenant = jdbc.sql("select coalesce(current_setting('app.current_tenant', true), '')")
                    .query(String.class).single();
            handled.add(new Handled(event.type(), event.tenantId().value(), contexts.current(), databaseTenant));
        }
    }

    @TestConfiguration
    static class Handlers {

        @Bean
        TenantEvents tenantEvents(TenantContexts contexts, JdbcClient jdbc) {
            return new TenantEvents(contexts, jdbc);
        }
    }

    @Autowired
    private TenantEvents tenantEvents;
    @Autowired
    private Tenants tenants;
    @Autowired
    private OutboxRelay relay;

    @Test
    void theRelayStartsWithTheApplicationAndDeliversTheLifecycleEventsOfARealTenantInOrderWithItsContext() {
        Tenant tenant = tenants.provision(TenantSlug.of("polling-" + UUID.randomUUID().toString().substring(0, 8)),
                "Tenant A", ActorId.SYSTEM);
        tenants.activate(tenant.id(), ActorId.SYSTEM);
        tenants.suspend(tenant.id(), ActorId.SYSTEM);

        assertThat(relay.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handledFor(tenant)).hasSize(3));

        List<Handled> mine = handledFor(tenant);
        assertThat(mine).extracting(Handled::type)
                .containsExactlyInAnyOrder("tenant.provisioned", "tenant.activated", "tenant.suspended");
        assertThat(mine).allSatisfy(handled -> {
            assertThat(handled.context()).contains(TenantContext.of(tenant.id()));
            assertThat(handled.databaseTenant()).isEqualTo(tenant.id().toString());
        });
    }

    @Test
    void eachEventIsHandledOnceEvenThoughTheRelayKeepsPolling() throws Exception {
        Tenant tenant = tenants.provision(TenantSlug.of("once-" + UUID.randomUUID().toString().substring(0, 8)),
                "Tenant B", ActorId.SYSTEM);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handledFor(tenant)).hasSize(1));

        Thread.sleep(500);

        assertThat(handledFor(tenant)).hasSize(1);
    }

    @Test
    void theRelayStopsAndStartsWithTheApplicationLifecycle() {
        relay.stop();
        assertThat(relay.isRunning()).isFalse();

        relay.start();
        assertThat(relay.isRunning()).isTrue();

        Tenant tenant = tenants.provision(TenantSlug.of("restart-" + UUID.randomUUID().toString().substring(0, 8)),
                "Tenant C", ActorId.SYSTEM);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handledFor(tenant)).hasSize(1));
    }

    private List<Handled> handledFor(Tenant tenant) {
        return tenantEvents.handled.stream().filter(handled -> handled.tenant().equals(tenant.id().value())).toList();
    }
}
