package app.platform.testsupport.tenancy;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.TestDatabase;
import app.platform.tenant.TenantStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Creates tenants for tests and runs database work the way the application does: as the unprivileged application
 * role, in a transaction, with the tenant in the transaction-local setting (ADR-0015). Reused by every later
 * sprint's isolation tests.
 */
public final class TenantFixtures {

    /** A tenant created for a test. */
    public record TestTenant(TenantId id, String slug) {

        /** The host name that addresses this tenant under the test platform domain. */
        public String host() {
            return slug + "." + PLATFORM_DOMAIN;
        }
    }

    /** The platform domain of the test profile. */
    public static final String PLATFORM_DOMAIN = "platform.example.test";

    private TenantFixtures() {
    }

    /** Creates an open (ACTIVE) tenant with a unique slug. */
    public static TestTenant createActiveTenant() {
        return createTenant(TenantStatus.ACTIVE);
    }

    /** Creates a tenant that has been moved to the given status along the legal transitions. */
    public static TestTenant createTenant(TenantStatus target) {
        String slug = "tenant-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TenantId id = new TenantId(UUID.randomUUID());
        List<TenantStatus> path = switch (target) {
            case PROVISIONING -> List.of();
            case ACTIVE -> List.of(TenantStatus.ACTIVE);
            case SUSPENDED -> List.of(TenantStatus.ACTIVE, TenantStatus.SUSPENDED);
            case DEACTIVATED -> List.of(TenantStatus.DEACTIVATED);
        };
        try (Connection owner = TestDatabase.ownerConnection()) {
            try (PreparedStatement insert = owner.prepareStatement(
                    "insert into tenant (id, slug, display_name, created_by, updated_by) values (?, ?, ?, ?, ?)")) {
                insert.setObject(1, id.value());
                insert.setString(2, slug);
                insert.setString(3, "Test " + slug);
                insert.setObject(4, ActorId.SYSTEM.value());
                insert.setObject(5, ActorId.SYSTEM.value());
                insert.executeUpdate();
            }
            for (TenantStatus step : path) {
                try (PreparedStatement update = owner.prepareStatement(
                        "update tenant set status = ?, updated_by = ?, version = version + 1 where id = ?")) {
                    update.setString(1, step.name());
                    update.setObject(2, ActorId.SYSTEM.value());
                    update.setObject(3, id.value());
                    update.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create a test tenant", e);
        }
        return new TestTenant(id, slug);
    }

    /** Puts the tenant's identifier into the transaction-local setting of the connection's current transaction. */
    public static void setTenant(Connection connection, TenantId tenant) throws SQLException {
        setting(connection, "app.current_tenant", tenant.toString());
    }

    /** Sets any transaction-local setting; used to put garbage into the tenant setting on purpose. */
    public static void setting(Connection connection, String name, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, true)")) {
            statement.setString(1, name);
            statement.setString(2, value);
            statement.execute();
        }
    }

    /**
     * Runs the work as the application role in one transaction with the tenant set, then commits.
     *
     * @return what the work returned
     */
    public static <T> T asTenant(TenantId tenant, SqlWork<T> work) throws SQLException {
        return inTransaction(tenant, work);
    }

    /** Runs the work as the application role in one transaction with no tenant set at all. */
    public static <T> T asNobody(SqlWork<T> work) throws SQLException {
        return inTransaction(null, work);
    }

    private static <T> T inTransaction(TenantId tenant, SqlWork<T> work) throws SQLException {
        try (Connection connection = TestDatabase.appConnection()) {
            connection.setAutoCommit(false);
            try {
                if (tenant != null) {
                    setTenant(connection, tenant);
                }
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Counts the rows a query returns (the query must select a single count). */
    public static long count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Runs a statement and returns the number of rows it changed. */
    public static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }
}
