package app.platform.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The tenant lifecycle events of the outbox become audit records, once each (Sprint 2 carried this to Sprint 9,
 * ADR-0054): the real tenant service writes the real events, the real handler turns them into audit records, and
 * doing that twice for the same event still leaves one record. The record is the fact of the state change, with the
 * state before and after.
 *
 * <p>The handler is driven directly, on the real rows the tenant service wrote to the outbox, instead of waiting on
 * the background relay: that the relay itself delivers in the background, in order, with the right tenant context,
 * is {@code OutboxPollingIT}'s job, and a wait on a background poller sharing one JVM and one database with every
 * other integration test class that also runs a real relay has no reliable bound to wait on.
 */
@PlatformIntegrationTest
class TenantLifecycleAuditIT {

    @Autowired
    private Tenants tenants;

    @Autowired
    private TenantLifecycleAudit handler;

    private static long records(Tenant tenant, String type) throws SQLException {
        return IdentityDb.value(Long.class, "select count(*) from audit_record where context_tenant_id = ? "
                + "and event_type = ? and source = 'EVENT'", tenant.id().value(), type);
    }

    /** The one row the tenant service wrote to the outbox for this tenant and event type, as a handler would see it. */
    private static EventEnvelope outboxEnvelope(Tenant tenant, String type, int attempt) throws SQLException {
        UUID id = IdentityDb.value(UUID.class, "select id from outbox_event where tenant_id = ? and event_type = ?",
                tenant.id().value(), type);
        String payload = IdentityDb.value(String.class, "select payload::text from outbox_event where tenant_id = ? "
                + "and event_type = ?", tenant.id().value(), type);
        return new EventEnvelope(id, tenant.id(), null, null, type, payload, Instant.now(), attempt);
    }

    @Test
    void everyLifecycleChangeOfARealOrganizationIsRecordedOnceWithTheStatesBeforeAndAfter() throws SQLException {
        Tenant tenant = tenants.provision(TenantSlug.of("audit-" + UUID.randomUUID().toString().substring(0, 8)),
                "Tenant A", ActorId.SYSTEM);
        tenants.activate(tenant.id(), ActorId.SYSTEM);
        tenants.suspend(tenant.id(), ActorId.SYSTEM);
        tenants.reinstate(tenant.id(), ActorId.SYSTEM);

        handler.handle(outboxEnvelope(tenant, "tenant.provisioned", 1));
        handler.handle(outboxEnvelope(tenant, "tenant.activated", 1));
        handler.handle(outboxEnvelope(tenant, "tenant.suspended", 1));
        handler.handle(outboxEnvelope(tenant, "tenant.reinstated", 1));

        assertThat(records(tenant, "tenant.lifecycle.provisioned")).isEqualTo(1);
        assertThat(records(tenant, "tenant.lifecycle.activated")).isEqualTo(1);
        assertThat(records(tenant, "tenant.lifecycle.suspended")).isEqualTo(1);
        assertThat(records(tenant, "tenant.lifecycle.reinstated")).isEqualTo(1);

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
        EventEnvelope event = outboxEnvelope(tenant, "tenant.provisioned", 1);

        // The relay marks an event as handled in the transaction of the handler; if that mark were ever lost the event
        // would be delivered again, and the record must still be written once (the unique event identifier).
        handler.handle(event);
        handler.handle(event);

        assertThat(records(tenant, "tenant.lifecycle.provisioned")).isEqualTo(1);
    }
}
