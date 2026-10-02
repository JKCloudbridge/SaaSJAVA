package app.platform.testsupport;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Makes people members of organizations for tests, the way the application would have, but without going through an
 * invitation: a row in the tenant-scoped membership table, written as the application role with the tenant set.
 */
public final class TestMembers {

    private TestMembers() {
    }

    /** Makes the user an active member of the tenant; returns the membership. */
    public static UUID add(TenantId tenant, UUID userId, boolean administrator) {
        UUID id = UUID.randomUUID();
        try {
            TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                    "insert into membership (id, tenant_id, user_id, administrator, created_by, updated_by) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    id, tenant.value(), userId, administrator, ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not add a test member", e);
        }
        return id;
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
}
