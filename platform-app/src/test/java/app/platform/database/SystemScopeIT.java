package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platform.testsupport.tenancy.SqlWork;
import app.platform.testsupport.tenancy.TenantProbeTables;
import app.platform.testsupport.tenancy.TenantScopedTable;
import app.platform.testsupport.tenancy.TenantScopedTables;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The system scope is the one deliberate way to work across tenants (ADR-0015). At the database it is a named,
 * transaction-local setting that only the policies of the outbox tables admit, and only for the kind of work the relay
 * does: read, update the outcome, and delete expired rows. It is not a bypass: it cannot write a row for a tenant, it
 * cannot move a row, and every other table ignores it.
 */
@PlatformIntegrationTest
class SystemScopeIT {

    private static TestTenant a;
    private static TestTenant b;

    @BeforeAll
    static void twoTenantsWithOneEventAndOneMarkerEach() throws SQLException {
        a = TenantFixtures.createActiveTenant();
        b = TenantFixtures.createActiveTenant();
        for (TestTenant tenant : List.of(a, b)) {
            TenantFixtures.asTenant(tenant.id(), connection -> {
                TenantScopedTables.OUTBOX_EVENT.inserter().insert(connection, tenant.id().value());
                TenantScopedTables.PROCESSED_EVENT.inserter().insert(connection, tenant.id().value());
                return null;
            });
        }
    }

    private static <T> T inScope(String scope, SqlWork<T> work) throws SQLException {
        try (Connection connection = TestDatabase.appConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setting(connection, "app.system_scope", scope);
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static long events(Connection connection, TestTenant tenant) throws SQLException {
        return TenantFixtures.count(connection, "select count(*) from outbox_event where tenant_id = ?",
                tenant.id().value());
    }

    @Test
    void theRelayScopeSeesTheOutboxOfEveryTenant() throws SQLException {
        long[] seen = inScope("outbox_relay", connection -> new long[] {events(connection, a), events(connection, b)});

        assertThat(seen[0]).isPositive();
        assertThat(seen[1]).isPositive();
    }

    @Test
    void withoutTheScopeOrWithAnotherNameNothingIsSeen() throws SQLException {
        for (String scope : new String[] {"", "other_scope", "OUTBOX_RELAY", "outbox_relay "}) {
            long[] seen = inScope(scope, connection -> new long[] {events(connection, a), events(connection, b)});

            assertThat(seen).as("scope '" + scope + "'").containsExactly(0, 0);
        }
    }

    @Test
    void theRelayScopeCanRecordAnOutcomeButNeverMoveARowToAnotherTenant() throws SQLException {
        int updated = inScope("outbox_relay", connection -> TenantFixtures.update(connection,
                "update outbox_event set attempts = attempts + 1, updated_by = ?, version = version + 1 "
                        + "where tenant_id = ?", ActorId.SYSTEM.value(), a.id().value()));
        assertThat(updated).isPositive();

        assertThatThrownBy(() -> inScope("outbox_relay", connection ->
                TenantFixtures.update(connection, "update outbox_event set tenant_id = ?, updated_by = ?, "
                        + "version = version + 1 where tenant_id = ?", b.id().value(), ActorId.SYSTEM.value(),
                        a.id().value())))
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isIn("23514", "42501"));
    }

    @Test
    void theRelayScopeCanNotInsertARowForATenant() {
        assertThatThrownBy(() -> inScope("outbox_relay", connection -> {
            TenantScopedTables.OUTBOX_EVENT.inserter().insert(connection, a.id().value());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> inScope("outbox_relay", connection -> {
            TenantScopedTables.PROCESSED_EVENT.inserter().insert(connection, a.id().value());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void theRelayScopeCanDeleteExpiredMarkersOfAnyTenantButNotChangeThem() throws SQLException {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        UUID eventId = UUID.randomUUID();
        TenantFixtures.asTenant(tenant.id(), connection -> TenantFixtures.update(connection,
                "insert into processed_event (tenant_id, consumer, event_id, created_by, updated_by) "
                        + "values (?, 'scope-probe', ?, ?, ?)", tenant.id().value(), eventId,
                ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

        int changed = inScope("outbox_relay", connection -> TenantFixtures.update(connection,
                "update processed_event set consumer = 'tampered', updated_by = ?, version = version + 1 "
                        + "where event_id = ?", ActorId.SYSTEM.value(), eventId));
        int deleted = inScope("outbox_relay", connection -> TenantFixtures.update(connection,
                "delete from processed_event where event_id = ?", eventId));

        assertThat(changed).as("update is not admitted for the scope on this table").isZero();
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    void everyOtherTableIgnoresTheScope() throws Exception {
        try (Connection owner = TestDatabase.ownerConnection()) {
            TenantScopedTable probe = TenantProbeTables.standard(owner, "scope_probe");
            try {
                TenantFixtures.asTenant(a.id(), connection -> {
                    probe.inserter().insert(connection, a.id().value());
                    return null;
                });

                long seen = inScope("outbox_relay", connection -> TenantFixtures.count(connection,
                        "select count(*) from scope_probe"));

                assertThat(seen).isZero();
            } finally {
                TenantProbeTables.drop(owner, "scope_probe");
            }
        }
    }
}
