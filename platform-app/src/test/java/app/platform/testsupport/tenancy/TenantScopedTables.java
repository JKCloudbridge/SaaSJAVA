package app.platform.testsupport.tenancy;

import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.UUID;

/**
 * The register of every tenant-scoped table of the platform (a table with a {@code tenant_id} column).
 * Each entry is run through {@link CrossTenantLeakHarness} by {@code TenantIsolationIT}, and a second test fails when a
 * table with a
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

    /** The profiles of organizations (Sprint 7). A probe row needs a licence type of its own. */
    public static final TenantScopedTable PROFILE = new TenantScopedTable("profile", (connection, tenant) -> {
        UUID type = probeLicenceType(connection);
        TenantFixtures.update(connection,
                "insert into profile (tenant_id, name, licence_type_id, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?)",
                tenant, "probe-" + UUID.randomUUID(), type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
    });

    /** The access policies of organizations (Sprint 7). */
    public static final TenantScopedTable ACCESS_POLICY = new TenantScopedTable("access_policy",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into access_policy (tenant_id, name, created_by, updated_by) values (?, ?, ?, ?)",
                    tenant, "probe-" + UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The role hierarchies of organizations (Sprint 7). */
    public static final TenantScopedTable SECURITY_ROLE = new TenantScopedTable("security_role",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into security_role (tenant_id, name, created_by, updated_by) values (?, ?, ?, ?)",
                    tenant, "probe-" + UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The profile and role of a member (Sprint 7). A probe row needs a profile, a user and a membership. */
    public static final TenantScopedTable MEMBER_ACCESS = new TenantScopedTable("member_access",
            (connection, tenant) -> {
                UUID membership = probeMembership(connection, tenant);
                UUID profile = probeProfile(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into member_access (tenant_id, membership_id, profile_id, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        tenant, membership, profile, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The access policies assigned to members (Sprint 7). */
    public static final TenantScopedTable MEMBER_ACCESS_POLICY = new TenantScopedTable("member_access_policy",
            (connection, tenant) -> {
                UUID membership = probeMembership(connection, tenant);
                UUID policy = UUID.randomUUID();
                TenantFixtures.update(connection,
                        "insert into access_policy (id, tenant_id, name, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        policy, tenant, "probe-" + policy, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into member_access_policy (tenant_id, membership_id, access_policy_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?)",
                        tenant, membership, policy, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The abilities given to single members (Sprint 7). */
    public static final TenantScopedTable MEMBER_GRANT = new TenantScopedTable("member_grant",
            (connection, tenant) -> {
                UUID membership = probeMembership(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into member_grant (tenant_id, membership_id, ability, created_by, updated_by) "
                                + "values (?, ?, 'members.view', ?, ?)",
                        tenant, membership, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The public groups of organizations (Sprint 8). */
    public static final TenantScopedTable PUBLIC_GROUP = new TenantScopedTable("public_group",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into public_group (tenant_id, name, created_by, updated_by) values (?, ?, ?, ?)",
                    tenant, "probe-" + UUID.randomUUID(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** The people and nested groups in public groups (Sprint 8). A probe row needs a group and a membership. */
    public static final TenantScopedTable PUBLIC_GROUP_MEMBER = new TenantScopedTable("public_group_member",
            (connection, tenant) -> {
                UUID membership = probeMembership(connection, tenant);
                UUID group = probeGroup(connection, tenant);
                TenantFixtures.update(connection,
                        "insert into public_group_member (tenant_id, group_id, member_membership_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?)",
                        tenant, group, membership, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** The access policies given to public groups (Sprint 8). */
    public static final TenantScopedTable PUBLIC_GROUP_ACCESS_POLICY = new TenantScopedTable(
            "public_group_access_policy", (connection, tenant) -> {
                UUID group = probeGroup(connection, tenant);
                UUID policy = UUID.randomUUID();
                TenantFixtures.update(connection,
                        "insert into access_policy (id, tenant_id, name, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        policy, tenant, "probe-" + policy, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                TenantFixtures.update(connection,
                        "insert into public_group_access_policy (tenant_id, group_id, access_policy_id, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?)",
                        tenant, group, policy, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** What a profile allows on objects (Sprint 8). */
    public static final TenantScopedTable OBJECT_PERMISSION = new TenantScopedTable("object_permission",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into object_permission (tenant_id, profile_id, object_key, actions, created_by, "
                            + "updated_by) values (?, ?, 'object-a', array['read'], ?, ?)",
                    tenant, probeProfile(connection, tenant), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /** What a profile allows on fields (Sprint 8). */
    public static final TenantScopedTable FIELD_PERMISSION = new TenantScopedTable("field_permission",
            (connection, tenant) -> TenantFixtures.update(connection,
                    "insert into field_permission (tenant_id, profile_id, field_key, actions, created_by, "
                            + "updated_by) values (?, ?, 'object-a.field-a', array['read'], ?, ?)",
                    tenant, probeProfile(connection, tenant), ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

    /**
     * The security version of an organization (Sprint 9). There is one live row per organization and other probe rows
     * may have created it already, so each call retires the live one and adds a new one: every call adds one row, as
     * the harness expects.
     */
    public static final TenantScopedTable SECURITY_VERSION = new TenantScopedTable("security_version",
            (connection, tenant) -> {
                TenantFixtures.update(connection,
                        "update security_version set deleted_at = now(), deleted_by = ?, version = version + 1, "
                                + "updated_by = ? where tenant_id = ? and deleted_at is null",
                        ActorId.SYSTEM.value(), ActorId.SYSTEM.value(), tenant);
                TenantFixtures.update(connection,
                        "insert into security_version (tenant_id, created_by, updated_by) values (?, ?, ?)",
                        tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
            });

    /** Every tenant-scoped table of the platform. Extend this list in the sprint that adds a table. */
    public static final List<TenantScopedTable> ALL = List.of(OUTBOX_EVENT, PROCESSED_EVENT, MEMBERSHIP, INVITATION,
            LICENCE_POOL, LICENCE_ASSIGNMENT, SUPPORT_ACCESS_GRANT, PROFILE, ACCESS_POLICY, SECURITY_ROLE,
            MEMBER_ACCESS, MEMBER_ACCESS_POLICY, MEMBER_GRANT, PUBLIC_GROUP, PUBLIC_GROUP_MEMBER,
            PUBLIC_GROUP_ACCESS_POLICY, OBJECT_PERMISSION, FIELD_PERMISSION, SECURITY_VERSION);

    private TenantScopedTables() {
    }

    /** A user and an active membership of the tenant for a probe row; returns the membership. */
    private static UUID probeMembership(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into platform_user (id, email, display_name, created_by, updated_by) "
                        + "values (?, ?, 'Probe', ?, ?)",
                user, "probe-" + user + "@example.test", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        TenantFixtures.update(connection,
                "insert into membership (id, tenant_id, user_id, created_by, updated_by) values (?, ?, ?, ?, ?)",
                membership, tenant, user, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return membership;
    }

    /** A public group of the tenant for a probe row; returns it. */
    private static UUID probeGroup(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID group = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into public_group (id, tenant_id, name, created_by, updated_by) values (?, ?, ?, ?, ?)",
                group, tenant, "probe-" + group, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return group;
    }

    /** A profile of the tenant for a probe row; returns it. */
    private static UUID probeProfile(java.sql.Connection connection, UUID tenant) throws java.sql.SQLException {
        UUID profile = UUID.randomUUID();
        TenantFixtures.update(connection,
                "insert into profile (id, tenant_id, name, licence_type_id, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?, ?)",
                profile, tenant, "probe-" + profile, probeLicenceType(connection), ActorId.SYSTEM.value(),
                ActorId.SYSTEM.value());
        return profile;
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
