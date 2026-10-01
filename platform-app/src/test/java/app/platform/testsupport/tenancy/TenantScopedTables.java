package app.platform.testsupport.tenancy;

import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.UUID;

/**
 * The register of every tenant-scoped table of the platform (a table with a {@code tenant_id} column).
 * Each entry is run
 * through {@link CrossTenantLeakHarness} by {@code TenantIsolationIT}, and a second test fails when a table with a
 * {@code tenant_id} column exists that is not listed here, so a new table cannot skip its isolation test.
 *
 * <p><strong>Adding a table:</strong> add one {@link TenantScopedTable} below (the table name and how to insert one
 * valid row for a given tenant) and run {@code ./mvnw verify}. See {@code docs/tenant-isolation-testing.md}.
 */
public final class TenantScopedTables {

    /** The outbox (Sprint 2). */
    public static final TenantScopedTable OUTBOX_EVENT = new TenantScopedTable("outbox_event",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into outbox_event (tenant_id, event_type, payload, created_by, updated_by) "
                            + "values (?, 'probe.created', '{}'::jsonb, ?, ?)",
                    tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The idempotency markers of consumers (Sprint 2). */
    public static final TenantScopedTable PROCESSED_EVENT = new TenantScopedTable("processed_event",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into processed_event (tenant_id, consumer, event_id, created_by, updated_by) "
                            + "values (?, 'probe', ?, ?, ?)",
                    tenant, UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** Every tenant-scoped table of the platform. Extend this list in the sprint that adds a table. */
    public static final List<TenantScopedTable> ALL = List.of(OUTBOX_EVENT, PROCESSED_EVENT);

    private TenantScopedTables() {
    }
}
