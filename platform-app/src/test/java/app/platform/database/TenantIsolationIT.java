package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.CrossTenantLeakHarness;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platform.testsupport.tenancy.TenantProbeTables;
import app.platform.testsupport.tenancy.TenantProbeTables.Defect;
import app.platform.testsupport.tenancy.TenantScopedTable;
import app.platform.testsupport.tenancy.TenantScopedTables;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tenant isolation at the database (ADR-0003, ADR-0015), proven on the real PostgreSQL as the unprivileged application
 * role: every registered tenant-scoped table is put through the cross-tenant leak harness, a table that is not
 * registered fails the build, and the harness itself is proven to fail on tables built wrongly.
 */
@PlatformIntegrationTest
class TenantIsolationIT {

    static Stream<TenantScopedTable> registeredTables() {
        return TenantScopedTables.ALL.stream();
    }

    // ---- every tenant-scoped table of the platform ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("registeredTables")
    void aTenantNeverReachesAnotherTenantsRowsInTheRegisteredTable(TenantScopedTable table) {
        CrossTenantLeakHarness.verify(table);
    }

    @Test
    void everyTableWithATenantColumnIsRegisteredForTheLeakChecks() throws SQLException {
        List<String> scoped = new ArrayList<>();
        try (Connection owner = TestDatabase.ownerConnection();
                Statement statement = owner.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select table_name from information_schema.columns where table_schema = 'public' "
                                + "and column_name = 'tenant_id' order by table_name")) {
            while (rs.next()) {
                scoped.add(rs.getString(1));
            }
        }

        assertThat(scoped).as("tenant-scoped tables that exist")
                .containsAll(TenantScopedTables.ALL.stream().map(TenantScopedTable::name).toList());
        assertThat(scoped).as("add every tenant-scoped table to TenantScopedTables")
                .allSatisfy(table -> assertThat(TenantScopedTables.ALL).extracting(TenantScopedTable::name)
                        .contains(table));
    }

    // ---- the harness is not vacuous ----

    @Test
    void aTableBuiltToThePatternPassesTheLeakChecks() throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection()) {
            TenantScopedTable table = TenantProbeTables.standard(owner, "probe_standard");
            try {
                assertThat(CrossTenantLeakHarness.findLeaks(table)).isEmpty();
            } finally {
                TenantProbeTables.drop(owner, "probe_standard");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Defect.class)
    void theLeakChecksFailOnATableBuiltWrong(Defect defect) throws SQLException {
        String name = "probe_" + defect.name().toLowerCase();
        try (Connection owner = TestDatabase.ownerConnection()) {
            TenantScopedTable table = TenantProbeTables.broken(owner, name, defect);
            try {
                List<String> leaks = CrossTenantLeakHarness.findLeaks(table);

                assertThat(leaks).as("leaks reported for " + defect).isNotEmpty();
                switch (defect) {
                    case NO_RLS -> assertThat(leaks).anyMatch(l -> l.contains("not enabled"))
                            .anyMatch(l -> l.contains("unfiltered query as one tenant returned"));
                    case NOT_FORCED -> assertThat(leaks).anyMatch(l -> l.contains("not forced"));
                    case OPEN_POLICY -> assertThat(leaks).anyMatch(l -> l.contains("another tenant"));
                    case FAILS_OPEN -> assertThat(leaks).anyMatch(l -> l.contains("fails open"));
                    case OPEN_INSERT -> assertThat(leaks).anyMatch(l -> l.contains("insert a row that belongs"));
                    case WRONG_COLUMN -> assertThat(leaks).anyMatch(l -> l.contains("cannot insert its own rows"));
                    default -> throw new AssertionError("unhandled defect " + defect);
                }
            } finally {
                TenantProbeTables.drop(owner, name);
            }
        }
    }

    // ---- forgot the filter, in the plainest form ----

    @Test
    void aDeliberatelyUnfilteredQueryStillCannotCrossTenants() throws SQLException {
        TestTenant a = TenantFixtures.createActiveTenant();
        TestTenant b = TenantFixtures.createActiveTenant();
        TenantFixtures.asTenant(a.id(), c -> {
            TenantScopedTables.OUTBOX_EVENT.inserter().insert(c, a.id().value());
            return null;
        });
        TenantFixtures.asTenant(b.id(), c -> {
            TenantScopedTables.OUTBOX_EVENT.inserter().insert(c, b.id().value());
            return null;
        });

        List<UUID> seenByA = TenantFixtures.asTenant(a.id(), c -> tenantsSeen(c));
        List<UUID> seenByB = TenantFixtures.asTenant(b.id(), c -> tenantsSeen(c));

        // No "where tenant_id = ..." anywhere: the database alone keeps the tenants apart.
        assertThat(seenByA).containsOnly(a.id().value());
        assertThat(seenByB).containsOnly(b.id().value());
    }

    @Test
    void withNoTenantSettingTheTenantPolicyMatchesNoRowsAtAll() throws SQLException {
        TestTenant a = TenantFixtures.createActiveTenant();
        TenantFixtures.asTenant(a.id(), c -> {
            TenantScopedTables.OUTBOX_EVENT.inserter().insert(c, a.id().value());
            return null;
        });

        assertThat(TenantFixtures.asNobody(TenantIsolationIT::tenantsSeen)).isEmpty();
    }

    // ---- FORCE ROW LEVEL SECURITY ----

    @Test
    void forcedRowSecurityBindsTheTableOwnerAndAnUnforcedTableDoesNot() throws SQLException {
        // The shared test owner is a superuser (the container's bootstrap role), who bypasses row level security
        // whatever is set; a deployment's owner is not. This uses a plain owner role to show what FORCE does.
        String password = UUID.randomUUID().toString();
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("drop schema if exists force_probe cascade");
            statement.execute("drop role if exists probe_owner");
            statement.execute("create role probe_owner login nosuperuser nobypassrls password '" + password + "'");
            statement.execute("create schema force_probe authorization probe_owner");
            statement.execute("grant usage on schema public to probe_owner");
            statement.execute("grant execute on function public.platform_current_tenant() to probe_owner");
        }
        try (Connection owner = DriverManager.getConnection(TestDatabase.jdbcUrl(), "probe_owner", password);
                Statement statement = owner.createStatement()) {
            for (String table : List.of("forced", "unforced")) {
                statement.execute("create table force_probe." + table + " (tenant_id uuid not null)");
                statement.execute("alter table force_probe." + table + " enable row level security");
                statement.execute("create policy isolation on force_probe." + table
                        + " using (tenant_id = (select public.platform_current_tenant()))");
                statement.execute("alter table force_probe." + table + " disable row level security");
                statement.execute("insert into force_probe." + table + " values (gen_random_uuid()), "
                        + "(gen_random_uuid())");
                statement.execute("alter table force_probe." + table + " enable row level security");
            }
            statement.execute("alter table force_probe.forced force row level security");

            assertThat(rows(statement, "force_probe.forced")).as("owner of a FORCED table, no tenant").isZero();
            assertThat(rows(statement, "force_probe.unforced")).as("owner of an unforced table").isEqualTo(2);
        } finally {
            try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
                statement.execute("drop schema if exists force_probe cascade");
                statement.execute("drop owned by probe_owner");
                statement.execute("drop role if exists probe_owner");
            }
        }
    }

    @Test
    void theRealTablesAreForcedSoTheOwnerIsBoundToo() throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection();
                Statement statement = owner.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                                + "where n.nspname = 'public' and c.relkind = 'r' and c.relrowsecurity "
                                + "and not c.relforcerowsecurity and c.relname <> 'audit_record'")) {
            // audit_record is the one deliberate exception (ADR-0054): a platform-level table whose read policy is by
            // audience, not forced because the owner (which migrates and runs the purge function) must see every row.
            assertThat(rs.next()).as("a table with RLS enabled but not forced").isFalse();
        }
    }

    // ---- helpers ----

    private static List<UUID> tenantsSeen(Connection connection) throws SQLException {
        List<UUID> tenants = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select tenant_id from outbox_event")) {
            while (rs.next()) {
                tenants.add(rs.getObject(1, UUID.class));
            }
        }
        return tenants;
    }

    private static long rows(Statement statement, String table) throws SQLException {
        try (ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
