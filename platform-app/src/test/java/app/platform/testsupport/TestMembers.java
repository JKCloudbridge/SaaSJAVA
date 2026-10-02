package app.platform.testsupport;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Makes people members of organizations for tests, the way the application would have, but without going through an
 * invitation: rows in the tenant-scoped tables, written as the application role with the tenant set.
 *
 * <p>Since Sprint 7 an administrator is a member who holds the system administrator profile and an admin licence (the
 * pool grows by one when it has no free licence, as it does for a first administrator); a plain member holds the member
 * profile and no licence, so they have no abilities until a test gives them some. The organization gets its two system
 * profiles when its first member is made.
 */
public final class TestMembers {

    private TestMembers() {
    }

    /** Makes the user an active member of the tenant; returns the membership. */
    public static UUID add(TenantId tenant, UUID userId, boolean administrator) {
        UUID id = UUID.randomUUID();
        try {
            TenantFixtures.asTenant(tenant, connection -> {
                TenantFixtures.update(connection,
                        "insert into membership (id, tenant_id, user_id, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        id, tenant.value(), userId, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                UUID profile = systemProfile(connection, tenant, administrator ? "administrator" : "member");
                TenantFixtures.update(connection,
                        "insert into member_access (tenant_id, membership_id, profile_id, created_by, updated_by) "
                                + "values (?, ?, ?, ?, ?)",
                        tenant.value(), id, profile, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                if (administrator) {
                    giveLicence(connection, tenant, id, "admin");
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not add a test member", e);
        }
        return id;
    }

    /** Gives the member a licence of the type for their profile; the pool grows by one when none is free. */
    public static void giveLicence(TenantId tenant, UUID membership, String licenceType) {
        try {
            TenantFixtures.asTenant(tenant, connection -> {
                giveLicence(connection, tenant, membership, licenceType);
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not give a test member a licence", e);
        }
    }

    /** Makes the user a member of the organization whose host the test signs in on (no-op for the platform host). */
    public static void addForHost(String host, UUID userId) {
        if (TestSignIn.PLATFORM_HOST.equals(host)) {
            return;
        }
        String slug = host.substring(0, host.indexOf('.'));
        try {
            TenantId tenant = new TenantId(IdentityDb.value(UUID.class, "select id from tenant where slug = ?", slug));
            if (membershipOf(tenant, userId) == null) {
                add(tenant, userId, false);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not find the test organization", e);
        }
    }

    /** The membership of the user in the tenant, or null. */
    public static UUID membershipOf(TenantId tenant, UUID userId) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement(
                    "select id from membership where user_id = ? and deleted_at is null")) {
                statement.setObject(1, userId);
                try (var rs = statement.executeQuery()) {
                    return rs.next() ? rs.getObject(1, UUID.class) : null;
                }
            }
        });
    }

    /** The system profile of the organization ({@code administrator} or {@code member}), made when missing. */
    private static UUID systemProfile(Connection connection, TenantId tenant, String key) throws SQLException {
        UUID found = profileOf(connection, key);
        if (found != null) {
            return found;
        }
        TenantFixtures.update(connection,
                "insert into profile (tenant_id, name, description, licence_type_id, abilities, system_key, "
                        + "full_access, is_default, created_by, updated_by) "
                        + "select ?, 'Organization administrator', 'Every ability.', lt.id, '{}', 'administrator', "
                        + "true, false, ?, ? from licence_type lt where lt.key = 'admin' and lt.deleted_at is null",
                tenant.value(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        TenantFixtures.update(connection,
                "insert into profile (tenant_id, name, description, licence_type_id, abilities, system_key, "
                        + "full_access, is_default, created_by, updated_by) "
                        + "select ?, 'Member', 'The default profile.', lt.id, '{}', 'member', false, true, ?, ? "
                        + "from licence_type lt where lt.key = 'user' and lt.deleted_at is null",
                tenant.value(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        return profileOf(connection, key);
    }

    private static UUID profileOf(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from profile where system_key = ? and deleted_at is null")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private static void giveLicence(Connection connection, TenantId tenant, UUID membership, String licenceType)
            throws SQLException {
        UUID type;
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from licence_type where key = ? and deleted_at is null")) {
            statement.setString(1, licenceType);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                type = rs.getObject(1, UUID.class);
            }
        }
        long quantity = -1;
        UUID pool = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, quantity from licence_pool where licence_type_id = ? and deleted_at is null for update")) {
            statement.setObject(1, type);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    pool = rs.getObject(1, UUID.class);
                    quantity = rs.getLong(2);
                }
            }
        }
        long used = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from licence_assignment where licence_type_id = ? and deleted_at is null")) {
            statement.setObject(1, type);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                used = rs.getLong(1);
            }
        }
        if (pool == null) {
            TenantFixtures.update(connection,
                    "insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, updated_by) "
                            + "values (?, ?, 1, ?, ?)",
                    tenant.value(), type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        } else if (used >= quantity) {
            TenantFixtures.update(connection,
                    "update licence_pool set quantity = quantity + 1, version = version + 1, updated_by = ? "
                            + "where id = ?",
                    ActorId.SYSTEM.value(), pool);
        }
        TenantFixtures.update(connection,
                "insert into licence_assignment (tenant_id, membership_id, licence_type_id, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?)",
                tenant.value(), membership, type, ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
    }
}
