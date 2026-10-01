package app.platform.testsupport.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The cross-tenant leak checks, run against one {@link TenantScopedTable} as the unprivileged application role on the
 * real database (ADR-0003, ADR-0015). Every later sprint reuses it: register the table in {@link TenantScopedTables}
 * and {@code TenantIsolationIT} runs all of this against it.
 *
 * <p>The checks are written as the mistakes they catch:
 * <ol>
 *   <li><strong>Forgot the filter.</strong> A query with no {@code where tenant_id = ...} must still return only the
 *       caller's own rows.</li>
 *   <li><strong>Forgot the tenant.</strong> A transaction without a tenant, or with an empty, malformed or unknown
 *       one, sees no rows (fail closed).</li>
 *   <li><strong>Writing across tenants.</strong> Updates and deletes of another tenant's rows touch nothing; a row
 *       for another tenant cannot be inserted; a row cannot be moved to another tenant.</li>
 *   <li><strong>No leak between transactions.</strong> The tenant set for one transaction is gone when the next one
 *       starts on the same connection.</li>
 *   <li><strong>The protection is switched on.</strong> Row level security is enabled and forced and the table has a
 *       policy.</li>
 * </ol>
 */
public final class CrossTenantLeakHarness {

    private static final int ROWS_OF_A = 2;
    private static final int ROWS_OF_B = 3;
    private static final Set<String> REFUSED = Set.of("42501", "23514");

    private CrossTenantLeakHarness() {
    }

    /** Fails with one line per leak found, if any. */
    public static void verify(TenantScopedTable table) {
        assertThat(findLeaks(table)).as("tenant isolation of " + table.name()).isEmpty();
    }

    /**
     * Runs every check.
     *
     * @return one sentence per way the table lets one tenant reach another's rows; empty when the table is safe
     */
    public static List<String> findLeaks(TenantScopedTable table) {
        List<String> leaks = new ArrayList<>();
        try {
            TestTenant a = TenantFixtures.createActiveTenant();
            TestTenant b = TenantFixtures.createActiveTenant();
            String name = table.name();
            try {
                seed(table, a, ROWS_OF_A);
                seed(table, b, ROWS_OF_B);
            } catch (SQLException e) {
                leaks.add("a tenant cannot insert its own rows (SQL state " + e.getSQLState() + ")");
                return leaks;
            }

            protectionIsOn(name, leaks);
            forgotTheFilter(name, a, ROWS_OF_A, leaks);
            forgotTheFilter(name, b, ROWS_OF_B, leaks);
            forgotTheTenant(name, leaks);
            writingAcrossTenants(table, a, b, leaks);
            noLeakBetweenTransactions(name, a, leaks);
        } catch (SQLException e) {
            leaks.add("the checks could not run: SQL state " + e.getSQLState());
        }
        return leaks;
    }

    // ---- setup ----

    private static void seed(TenantScopedTable table, TestTenant tenant, int rows) throws SQLException {
        TenantFixtures.asTenant(tenant.id(), connection -> {
            for (int i = 0; i < rows; i++) {
                table.inserter().insert(connection, tenant.id().value());
            }
            return null;
        });
    }

    // ---- the checks ----

    private static void protectionIsOn(String name, List<String> leaks) throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(
                        "select relrowsecurity, relforcerowsecurity, "
                                + "(select count(*) from pg_policy where polrelid = c.oid) "
                                + "from pg_class c where c.oid = to_regclass(?)")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    leaks.add("the table does not exist");
                    return;
                }
                if (!rs.getBoolean(1)) {
                    leaks.add("row level security is not enabled");
                }
                if (!rs.getBoolean(2)) {
                    leaks.add("row level security is not forced, so the owner role bypasses it");
                }
                if (rs.getInt(3) == 0) {
                    leaks.add("there is no policy");
                }
            }
        }
    }

    /** An unfiltered query run as the tenant must see its own rows and nobody else's. */
    private static void forgotTheFilter(String name, TestTenant tenant, int ownRows, List<String> leaks)
            throws SQLException {
        long[] seen = TenantFixtures.asTenant(tenant.id(), connection -> new long[] {
            TenantFixtures.count(connection, "select count(*) from " + name),
            TenantFixtures.count(connection, "select count(*) from " + name + " where tenant_id <> ?",
                    tenant.id().value()),
            TenantFixtures.count(connection, "select count(*) from " + name + " where tenant_id = ?",
                    tenant.id().value())});
        if (seen[1] != 0) {
            leaks.add("an unfiltered query as one tenant returned " + seen[1] + " row(s) of another tenant");
        }
        if (seen[2] != ownRows || seen[0] != ownRows) {
            leaks.add("an unfiltered query as a tenant with " + ownRows + " row(s) returned " + seen[0]
                    + " (its own: " + seen[2] + ")");
        }
    }

    private static void forgotTheTenant(String name, List<String> leaks) throws SQLException {
        long none = TenantFixtures.asNobody(
                connection -> TenantFixtures.count(connection, "select count(*) from " + name));
        if (none != 0) {
            leaks.add("with no tenant set a query returned " + none + " row(s) instead of none (fails open)");
        }
        for (String garbage : new String[] {"", "not-a-uuid", "00000000-0000-0000-0000-000000000000"}) {
            long seen = TenantFixtures.asNobody(connection -> {
                TenantFixtures.setting(connection, "app.current_tenant", garbage);
                return TenantFixtures.count(connection, "select count(*) from " + name);
            });
            if (seen != 0) {
                leaks.add("with the tenant setting '" + garbage + "' a query returned " + seen + " row(s)");
            }
        }
    }

    private static void writingAcrossTenants(TenantScopedTable table, TestTenant a, TestTenant b,
            List<String> leaks) throws SQLException {
        String name = table.name();
        // Another tenant's rows are invisible, so updates and deletes find nothing.
        int[] changed = TenantFixtures.asTenant(a.id(), connection -> new int[] {
            TenantFixtures.update(connection, "update " + name + " set updated_by = ?, version = version + 1 "
                    + "where tenant_id = ?", ActorId.SYSTEM.value(), b.id().value()),
            TenantFixtures.update(connection, "delete from " + name + " where tenant_id = ?", b.id().value())});
        if (changed[0] != 0) {
            leaks.add("a tenant could update " + changed[0] + " row(s) of another tenant");
        }
        if (changed[1] != 0) {
            leaks.add("a tenant could delete " + changed[1] + " row(s) of another tenant");
        }
        long untouched = TenantFixtures.asTenant(b.id(),
                connection -> TenantFixtures.count(connection, "select count(*) from " + name));
        if (untouched != ROWS_OF_B) {
            leaks.add("the other tenant's rows changed: " + untouched + " left of " + ROWS_OF_B);
        }

        // A row for another tenant cannot be inserted.
        if (!refused(a, connection -> {
            table.inserter().insert(connection, b.id().value());
            return null;
        })) {
            leaks.add("a tenant could insert a row that belongs to another tenant");
        }
        // A row cannot be handed over to another tenant.
        if (!refusedOrNothing(a, connection -> TenantFixtures.update(connection, "update " + name
                + " set tenant_id = ?, updated_by = ?, version = version + 1 where tenant_id = ?",
                b.id().value(), ActorId.SYSTEM.value(), a.id().value()))) {
            leaks.add("a tenant could move its rows to another tenant");
        }
    }

    private static void noLeakBetweenTransactions(String name, TestTenant a, List<String> leaks)
            throws SQLException {
        try (Connection connection = TestDatabase.appConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setTenant(connection, a.id());
            long first = TenantFixtures.count(connection, "select count(*) from " + name);
            connection.commit();
            // The next transaction on the same connection, with no tenant set.
            long second = TenantFixtures.count(connection, "select count(*) from " + name);
            connection.commit();
            if (first == 0) {
                leaks.add("the tenant could not read its own rows");
            }
            if (second != 0) {
                leaks.add("the tenant of a finished transaction was still in force on the next one: " + second
                        + " row(s)");
            }
        }
    }

    // ---- helpers ----

    /** True when the write was refused by the database; false when it went through (it is rolled back either way). */
    private static boolean refused(TestTenant tenant, SqlWork<?> work) throws SQLException {
        try (Connection connection = TestDatabase.appConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setTenant(connection, tenant.id());
                work.run(connection);
                return false;
            } catch (SQLException e) {
                return REFUSED.contains(e.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    /** True when the write was refused or changed nothing. */
    private static boolean refusedOrNothing(TestTenant tenant, SqlWork<Integer> work) throws SQLException {
        try (Connection connection = TestDatabase.appConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setTenant(connection, tenant.id());
                return work.run(connection) == 0;
            } catch (SQLException e) {
                return REFUSED.contains(e.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }
}
