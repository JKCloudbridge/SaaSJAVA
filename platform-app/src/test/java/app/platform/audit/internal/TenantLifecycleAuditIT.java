package app.platform.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The tenant lifecycle events of the outbox become audit records, once each (Sprint 2 carried this to Sprint 9,
 * ADR-0054): through the real relay and the real tenant service, and again when the same event is delivered a second
 * time. The record is the fact of the state change, with the state before and after.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms"})
class TenantLifecycleAuditIT {

    @Autowired
    private Tenants tenants;

    @Autowired
    private TenantLifecycleAudit handler;

    private static long records(Tenant tenant, String type) throws SQLException {
        return IdentityDb.value(Long.class, "select count(*) from audit_record where context_tenant_id = ? "
                + "and event_type = ? and source = 'EVENT'", tenant.id().value(), type);
    }

    @Test
    void everyLifecycleChangeOfARealOrganizationIsRecordedOnceWithTheStatesBeforeAndAfter() throws SQLException {
        Tenant tenant = tenants.provision(TenantSlug.of("audit-" + UUID.randomUUID().toString().substring(0, 8)),
                "Tenant A", ActorId.SYSTEM);
        tenants.activate(tenant.id(), ActorId.SYSTEM);
        tenants.suspend(tenant.id(), ActorId.SYSTEM);
        tenants.reinstate(tenant.id(), ActorId.SYSTEM);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(records(tenant, "tenant.lifecycle.provisioned")).isEqualTo(1);
            assertThat(records(tenant, "tenant.lifecycle.activated")).isEqualTo(1);
            assertThat(records(tenant, "tenant.lifecycle.suspended")).isEqualTo(1);
            assertThat(records(tenant, "tenant.lifecycle.reinstated")).isEqualTo(1);
        });

        assertThat(IdentityDb.value(String.class, "select old_value || ' to ' || new_value from audit_record "
                + "where context_tenant_id = ? and event_type = 'tenant.lifecycle.suspended'", tenant.id().value()))
                .isEqualTo("ACTIVE to SUSPENDED");
        assertThat(IdentityDb.value(Boolean.class, "select audience_organization and audience_platform "
                + "from audit_record where context_tenant_id = ? and event_type = 'tenant.lifecycle.suspended'",
                tenant.id().value())).as("the organization and the platform may both read it").isTrue();
    }

    @Test
    void theSameEventDeliveredTwiceLeavesOneRecord() throws SQLException {
        Tenant tenant = tenants.provision(TenantSlug.of("twice-" + UUID.randomUUID().toString().substring(0, 8)),
                "Tenant B", ActorId.SYSTEM);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(records(tenant, "tenant.lifecycle.provisioned")).isEqualTo(1));
        UUID event = IdentityDb.value(UUID.class, "select source_event_id from audit_record "
                + "where context_tenant_id = ? and event_type = 'tenant.lifecycle.provisioned'", tenant.id().value());

        // The relay marks an event as handled in the transaction of the handler; if that mark were ever lost the event
        // would be delivered again, and the record must still be written once (the unique event identifier).
        EventEnvelope again = new EventEnvelope(event, tenant.id(), null, null, "tenant.provisioned",
                "{\"tenantId\":\"" + tenant.id() + "\",\"slug\":\"x\",\"to\":\"PROVISIONING\"}", Instant.now(), 2);
        handler.handle(again);
        handler.handle(again);

        assertThat(records(tenant, "tenant.lifecycle.provisioned")).isEqualTo(1);
    }
}
