package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platform.testsupport.tenancy.TenantScopedTable;
import app.platform.testsupport.tenancy.TenantScopedTables;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The security version (migration V028, ADR-0053) on the real PostgreSQL: every table that decides what a member may do
 * has the trigger that raises the organization's counter in the same transaction, the set of such tables is exactly the
 * one written down here (adding a table that feeds a decision without adding it here, and to the migration, is a
 * deliberate act), and an organization sees only its own counter. Without these triggers the cache would serve answers
 * for ever, so this test is what keeps "nothing can forget the counter" true.
 */
@PlatformIntegrationTest
class SecurityVersionIT {

    /** The tables whose rows decide an answer of Permissions or Decisions (see the migration for what is left out). */
    private static final Set<String> TABLES = new TreeSet<>(Set.of("profile", "access_policy", "member_access",
            "member_access_policy", "member_grant", "public_group", "public_group_member",
            "public_group_access_policy", "object_permission", "field_permission", "licence_assignment",
            "membership", "object_definition", "field_definition"));

    static Stream<TenantScopedTable> decidingTables() {
        return TenantScopedTables.ALL.stream().filter(table -> TABLES.contains(table.name()));
    }

    private static long versionOf(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select coalesce((select version from security_version where deleted_at is null), -1)")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void exactlyTheDecidingTablesCarryTheCounterTriggerOnEveryKindOfChange() throws SQLException {
        Set<String> found = new TreeSet<>();
        try (Connection owner = TestDatabase.ownerConnection();
                Statement statement = owner.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select tgrelid::regclass::text, tgtype from pg_trigger "
                                + "where tgname like '%\\_security\\_version' and not tgisinternal")) {
            while (rs.next()) {
                found.add(rs.getString(1));
                // After (no "before" bit), per row, on insert, update and delete: 1 + 4 + 8 + 16.
                assertThat(rs.getInt(2)).as("kind of trigger on " + rs.getString(1)).isEqualTo(29);
            }
        }
        assertThat(found).isEqualTo(TABLES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decidingTables")
    void aChangeOfTheTableRaisesTheOrganizationsVersionInTheSameTransaction(TenantScopedTable table)
            throws SQLException {
        TestTenant tenant = TenantFixtures.createActiveTenant();

        TenantFixtures.asTenant(tenant.id(), connection -> {
            long before = versionOf(connection);
            table.inserter().insert(connection, tenant.id().value());
            assertThat(versionOf(connection)).as("version after a change of " + table.name())
                    .isGreaterThan(before);
            return null;
        });
    }

    @Test
    void theCounterIsCommittedWithTheChangeAndRolledBackWithIt() throws SQLException {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        long start = TenantFixtures.asTenant(tenant.id(), SecurityVersionIT::versionOf);
        List<TenantScopedTable> probe = List.of(TenantScopedTables.PROFILE);

        try {
            TenantFixtures.asTenant(tenant.id(), connection -> {
                probe.get(0).inserter().insert(connection, tenant.id().value());
                throw new IllegalStateException("roll back");
            });
        } catch (IllegalStateException expected) {
            // The transaction ended without a commit.
        }

        assertThat(TenantFixtures.asTenant(tenant.id(), SecurityVersionIT::versionOf))
                .as("a rolled back change leaves the version alone").isEqualTo(start);
        TenantFixtures.asTenant(tenant.id(), connection -> {
            probe.get(0).inserter().insert(connection, tenant.id().value());
            return null;
        });
        assertThat(TenantFixtures.asTenant(tenant.id(), SecurityVersionIT::versionOf))
                .as("a committed one raises it").isGreaterThan(start);
    }

    @Test
    void anOrganizationSeesItsOwnCounterAndNobodyElsesAndNoTenantNoneAtAll() throws SQLException {
        TestTenant first = TenantFixtures.createActiveTenant();
        TestTenant second = TenantFixtures.createActiveTenant();
        TenantFixtures.asTenant(first.id(), connection -> {
            TenantScopedTables.PROFILE.inserter().insert(connection, first.id().value());
            return null;
        });

        assertThat(TenantFixtures.asTenant(first.id(), SecurityVersionIT::versionOf)).isGreaterThanOrEqualTo(0);
        assertThat(TenantFixtures.asTenant(second.id(), SecurityVersionIT::versionOf))
                .as("the other organization has no counter of its own yet and cannot read the first one")
                .isEqualTo(-1);
        assertThat(TenantFixtures.asNobody(SecurityVersionIT::versionOf)).isEqualTo(-1);
    }
}
