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
 * The workflow dispatch scope (ADR-0135, an amendment of ADR-0015), at the database: it finds and claims the executions
 * and the schedules of every organization and records the outcome of a claim, and nothing else. It cannot insert a row
 * for an organization, cannot move a row, cannot delete a schedule, and the tables of the definitions, the steps, the
 * outbox and every other table ignore it.
 */
@PlatformIntegrationTest
class WorkflowDispatchScopeIT {

    private static final String SCOPE = "workflow_dispatch";

    private static TestTenant a;
    private static TestTenant b;

    @BeforeAll
    static void twoTenantsWithAnExecutionAndAScheduleEach() throws SQLException {
        a = TenantFixtures.createActiveTenant();
        b = TenantFixtures.createActiveTenant();
        for (TestTenant tenant : List.of(a, b)) {
            TenantFixtures.asTenant(tenant.id(), connection -> {
                TenantScopedTables.WORKFLOW_EXECUTION.inserter().insert(connection, tenant.id().value());
                TenantScopedTables.WORKFLOW_SCHEDULE.inserter().insert(connection, tenant.id().value());
                TenantScopedTables.WORKFLOW_STEP.inserter().insert(connection, tenant.id().value());
                TenantScopedTables.OUTBOX_EVENT.inserter().insert(connection, tenant.id().value());
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
    void theDispatchScopeSeesTheExecutionsAndTheSchedulesOfEveryOrganization() throws SQLException {
        long[] seen = inScope(SCOPE, connection -> new long[] {of(connection, "workflow_execution", a),
            of(connection, "workflow_execution", b), of(connection, "workflow_schedule", a),
            of(connection, "workflow_schedule", b)});

        assertThat(seen).as("an execution and a schedule of each organization").doesNotContain(0L);
    }

    @Test
    void withoutTheScopeOrWithAnotherNameNothingIsSeen() throws SQLException {
        String[] others = {"", "other_scope", "WORKFLOW_DISPATCH", "workflow_dispatch ", "outbox_relay"};
        for (String scope : others) {
            long[] seen = inScope(scope, connection -> new long[] {of(connection, "workflow_execution", a),
                of(connection, "workflow_schedule", b)});

            assertThat(seen).as("scope '" + scope + "'").containsExactly(0, 0);
        }
    }

    @Test
    void theScopeSeesNothingElseOfTheWorkflowModuleOrTheOutbox() throws SQLException {
        long[] seen = inScope(SCOPE, connection -> new long[] {of(connection, "workflow_step", a),
            of(connection, "workflow", a), of(connection, "workflow_version", a), of(connection, "outbox_event", a),
            of(connection, "processed_event", a)});

        assertThat(seen).as("the steps, the definitions, the versions and the outbox ignore this scope")
                .containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    void theOutboxScopeSeesNeitherExecutionsNorSchedules() throws SQLException {
        long[] seen = inScope("outbox_relay", connection -> new long[] {of(connection, "workflow_execution", a),
            of(connection, "workflow_schedule", a)});

        assertThat(seen).containsExactly(0, 0);
    }

    @Test
    void theScopeCanClaimAnExecutionAndAScheduleButNeverMoveARowToAnotherOrganization() throws SQLException {
        int claimedExecutions = inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "update workflow_execution set attempts = attempts + 1, updated_by = ?, version = version + 1 "
                        + "where tenant_id = ?", ActorId.SYSTEM.value(), a.id().value()));
        int claimedSchedules = inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "update workflow_schedule set locked_until = now(), updated_by = ?, version = version + 1 "
                        + "where tenant_id = ?", ActorId.SYSTEM.value(), a.id().value()));

        assertThat(claimedExecutions).isPositive();
        assertThat(claimedSchedules).isPositive();
        assertThatThrownBy(() -> inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "update workflow_execution set tenant_id = ?, updated_by = ?, version = version + 1 "
                        + "where tenant_id = ?", b.id().value(), ActorId.SYSTEM.value(), a.id().value())))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isIn("23514", "42501"));
        assertThatThrownBy(() -> inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "update workflow_schedule set tenant_id = ?, updated_by = ?, version = version + 1 "
                        + "where tenant_id = ?", b.id().value(), ActorId.SYSTEM.value(), a.id().value())))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isIn("23514", "42501"));
    }

    @Test
    void theScopeCanNotInsertARowForAnOrganization() {
        assertThatThrownBy(() -> inScope(SCOPE, connection -> {
            TenantScopedTables.WORKFLOW_EXECUTION.inserter().insert(connection, a.id().value());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> inScope(SCOPE, connection -> {
            TenantScopedTables.WORKFLOW_SCHEDULE.inserter().insert(connection, a.id().value());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void theScopeCanRemoveExecutionsForRetentionButNotSchedules() throws SQLException {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        TenantFixtures.asTenant(tenant.id(), connection -> {
            TenantScopedTables.WORKFLOW_EXECUTION.inserter().insert(connection, tenant.id().value());
            TenantScopedTables.WORKFLOW_SCHEDULE.inserter().insert(connection, tenant.id().value());
            return null;
        });

        int schedules = inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "delete from workflow_schedule where tenant_id = ?", tenant.id().value()));
        int executions = inScope(SCOPE, connection -> TenantFixtures.update(connection,
                "delete from workflow_execution where tenant_id = ?", tenant.id().value()));

        assertThat(schedules).as("the policy of the schedule table admits no delete for a scope").isZero();
        assertThat(executions).isEqualTo(1);
    }
}
