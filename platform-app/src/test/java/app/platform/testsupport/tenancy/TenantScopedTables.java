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

    /** The memberships of users in organizations (Sprint 4). Each probe row needs a user, which it creates. */
    public static final TenantScopedTable MEMBERSHIP = new TenantScopedTable("membership", (connection, tenant) -> {
        UUID user = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into platform_user (id, email, display_name, created_by, updated_by) "
                        + "values (?, ?, 'Probe', ?, ?)",
                user, "probe-" + user + "@example.test", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        TenantFixtures.update(connection,
                "insert into membership (tenant_id, user_id, created_by, updated_by) values (?, ?, ?, ?)",
                tenant, user, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
    });

    /** The invitations of organizations (Sprint 5). */
    public static final TenantScopedTable INVITATION = new TenantScopedTable("invitation",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into invitation (tenant_id, email, expires_at, created_by, updated_by) "
                            + "values (?, ?, now() + interval '1 day', ?, ?)",
                    tenant, "probe-" + UUID.randomUUID() + "@example.test", ActorId.SYSTEM.value(),
                    ActorId.SYSTEM.value()));

    /**
     * The licence pools of organizations (Sprint 6). There is one pool per licence type and organization, so every
     * probe row first makes a licence type of its own in the (platform-level) catalogue.
     */
    public static final TenantScopedTable LICENCE_POOL = new TenantScopedTable("licence_pool",
            (connection, tenant) -> {
                UUID type = probeLicenceType(connection);
                TenantFixtures.update(connection,
                        "insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, updated_by) "
                                + "values (?, ?, 3, ?, ?)",
                        tenant, type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /**
     * The licences held by members (Sprint 6). A probe row needs a licence type, a pool, a user and a membership, which
     * it creates.
     */
    public static final TenantScopedTable LICENCE_ASSIGNMENT = new TenantScopedTable("licence_assignment",
            (connection, tenant) -> {
                UUID user = UUID.randomUUID();
                UUID membership = UUID.randomUUID();
                UUID type = probeLicenceType(connection);
                TenantFixtures.update(connection,
                        "insert into platform_user (id, email, display_name, created_by, updated_by) "
                                + "values (?, ?, 'Probe', ?, ?)",
                        user, "probe-" + user + "@example.test", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into membership (id, tenant_id, user_id, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        membership, tenant, user, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, updated_by) "
                                + "values (?, ?, 3, ?, ?)",
                        tenant, type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into licence_assignment (tenant_id, membership_id, licence_type_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?)",
                        tenant, membership, type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The support-access requests and grants of organizations (Sprint 6). The probe needs a platform person. */
    public static final TenantScopedTable SUPPORT_ACCESS_GRANT = new TenantScopedTable("support_access_grant",
            (connection, tenant) -> {
                UUID person = UUID.randomUUID();
                TenantFixtures.update(connection,
                        "insert into platform_user (id, email, display_name, created_by, updated_by) "
                                + "values (?, ?, 'Probe', ?, ?)",
                        person, "probe-" + person + "@example.test", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into support_access_grant (tenant_id, requested_by, reason, requested_minutes, "
                                + "request_expires_at, created_by, updated_by) "
                                + "values (?, ?, 'probe', 30, now() + interval '1 day', ?, ?)",
                        tenant, person, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** Every tenant-scoped table of the platform. Extend this list in the sprint that adds a table. */
    public static final List<TenantScopedTable> ALL = List.of(OUTBOX_EVENT, PROCESSED_EVENT, MEMBERSHIP, INVITATION,
            LICENCE_POOL, LICENCE_ASSIGNMENT, SUPPORT_ACCESS_GRANT);

    private TenantScopedTables() {
    }

    /** A licence type of its own in the catalogue (platform-level), so that probe pools never collide. */
    private static UUID probeLicenceType(java.sql.Connection connection) throws java.sql.SQLException {
        UUID id = UUID.randomUUID();
        String key = "p" + id.toString().replace("-", "").substring(0, 12);
        TenantFixtures.update(connection,
                "insert into licence_type (id, key, name, created_by, updated_by) values (?, ?, 'Probe', ?, ?)",
                id, key, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return id;
    }
}
