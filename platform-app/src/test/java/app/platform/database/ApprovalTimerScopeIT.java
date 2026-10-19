package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.SqlWork;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platform.testsupport.tenancy.TenantScopedTables;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The approval timer scope (ADR-0159, ADR-0158: an amendment of ADR-0015 in the manner of ADR-0135), at the database:
 * it finds and claims the timers of the active steps of every organization, and nothing else. It cannot insert a row
 * for an organization, cannot move a row, cannot remove one, and the tables of the requests, the steps, the tasks, the
 * history and every other table ignore it.
 */
@PlatformIntegrationTest
class ApprovalTimerScopeIT {

    private static final String SCOPE = "approval_timers";

    private static TestTenant a;
    private static TestTenant b;

    @BeforeAll
    static void twoTenantsWithATimerEach() throws SQLException {
        a = TenantFixtures.createActiveTenant();
        b = TenantFixtures.createActiveTenant();
        for (TestTenant tenant : List.of(a, b)) {
            TenantFixtures.asTenant(tenant.id(), connection -> {
                TenantScopedTables.APPROVAL_STEP_TIMER.inserter().insert(connection, tenant.id().value());
                TenantScopedTables.APPROVAL_STEP_INSTANCE.inserter().insert(connection, tenant.id().value());
                TenantScopedTables.APPROVAL_INSTANCE.inserter().insert(connection, tenant.id().value());
                TenantScopedTables.APPROVAL_REMINDER.inserter().insert(connection, tenant.id().value());
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

    private static long of(Connection connection, String table, TestTenant tenant) throws SQLException {
        return TenantFixtures.count(connection, "select count(*) from " + table + " where tenant_id = ?",
                tenant.id().value());
    }

    @Test
    void theTimerScopeSeesTheTimersOfEveryOrganization() throws SQLException {
        long[] seen = inScope(SCOPE, connection -> new long[] {of(connection, "approval_step_timer", a),
            of(connection, "approval_step_timer", b)});

        assertThat(seen).as("a timer of each organization").doesNotContain(0L);
    }

    @Test
    void withoutTheScopeOrWithAnotherNameNothingIsSeen() throws SQLException {
        String[] others = {"", "other_scope", "APPROVAL_TIMERS", "approval_timers ", "workflow_dispatch"};
        for (String scope : others) {
            long[] seen = inScope(scope, connection -> new long[] {of(connection, "approval_step_timer", a),
                of(connection, "approval_step_timer", b)});

            assertThat(seen).as("scope '" + scope + "'").containsExactly(0, 0);
        }
    }

    @Test
    void theScopeSeesNothingElseOfTheApprovalModule() throws SQLException {
        long[] seen = inScope(SCOPE, connection -> new long[] {of(connection, "approval_step_instance", a),
            of(connection, "approval_instance", a), of(connection, "approval_reminder", a),
            of(connection, "approval_assignment", a), of(connection, "approval_history", a),
            of(connection, "outbox_event", a)});

        assertThat(seen).as("the steps, the requests, the notes, the tasks, the history and the outbox ignore it")
                .containsExactly(0, 0, 0, 0, 0, 0);
    }

    @Test
    void theWorkflowDispatchScopeSeesNoTimers() throws SQLException {
        long[] seen = inScope("workflow_dispatch", connection -> new long[] {of(connection, "approval_step_timer", a)});

        assertThat(seen).containsExactly(0);
    }

    @Test
    void theScopeCanClaimATimerButNeverMoveARowToAnotherOrganization() throws SQLException {
        int claimed = inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "update approval_step_timer set next_timer_at = now() + interval '2 minutes', updated_by = ?, "
                        + "version = version + 1 where tenant_id = ?", ActorId.SYSTEM.value(), a.id().value()));

        assertThat(claimed).isPositive();
        assertThatThrownBy(() -> inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "update approval_step_timer set tenant_id = ?, updated_by = ?, version = version + 1 "
                        + "where tenant_id = ?", b.id().value(), ActorId.SYSTEM.value(), a.id().value())))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isIn("23514", "42501"));
    }

    @Test
    void theScopeCanNotInsertARowForAnOrganizationAndCanNotRemoveOne() throws SQLException {
        assertThatThrownBy(() -> inScope(SCOPE, connection -> {
            TenantScopedTables.APPROVAL_STEP_TIMER.inserter().insert(connection, a.id().value());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));

        int removed = inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "delete from approval_step_timer where tenant_id = ?", b.id().value()));
        assertThat(removed).as("the policy of the timer table admits no delete for a scope").isZero();
    }
}
