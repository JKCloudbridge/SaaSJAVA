package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the database itself holds for the queue, the attempt log and the flow state (V088, ADR-0229 to ADR-0234),
 * whatever the application does: a finished delivery is final, a dead letter moves only by a replay that starts a new
 * generation with the attempts counted afresh, a delivery that waits for the other side is not sent again, attempts are
 * never taken back, an attempt record is never changed, and the flow state keeps its shape. Each rule is tried with
 * direct statements as the application role and as the owner of the database, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class ReliabilityGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    private record World(TenantId tenant, UUID connection, UUID version) {
    }

    private static int run(Role role, TenantId tenant, String sql, Object... parameters) throws SQLException {
        if (role == Role.APP) {
            return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection, sql, parameters));
        }
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setTenant(connection, tenant);
                int changed = TenantFixtures.update(connection, sql, parameters);
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static String stateOf(Role role, TenantId tenant, String sql, Object... parameters) {
        try {
            run(role, tenant, sql, parameters);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static World world() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID connection = UUID.randomUUID();
        run(Role.APP, tenant, "insert into integration_connection (id, tenant_id, name, label, connector_type, "
                + "connector_version, base_url, auth_kind, definition_fingerprint, created_by, updated_by) values "
                + "(?, ?, ?, 'Label', 'rest', 1, 'https://api.example.com', 'NONE', ?, ?, ?)", connection,
                tenant.value(), "link-" + UUID.randomUUID().toString().substring(0, 8), "a".repeat(64), ACTOR, ACTOR);
        UUID mapping = UUID.randomUUID();
        run(Role.APP, tenant, "insert into integration_mapping (id, tenant_id, connection_id, object_api_name, "
                + "entity, created_by, updated_by) values (?, ?, ?, 'ObjectA__c', 'item', ?, ?)", mapping,
                tenant.value(), connection, ACTOR, ACTOR);
        UUID version = UUID.randomUUID();
        run(Role.APP, tenant, "insert into integration_mapping_version (id, tenant_id, mapping_id, number, "
                + "definition, definition_hash, created_by, updated_by) values (?, ?, ?, 1, '{}'::jsonb, ?, ?, ?)",
                version, tenant.value(), mapping, "b".repeat(64), ACTOR, ACTOR);
        return new World(tenant, connection, version);
    }

    private static UUID delivery(World world, String state) throws SQLException {
        UUID id = UUID.randomUUID();
        run(Role.APP, world.tenant(), "insert into integration_delivery (id, tenant_id, connection_id, "
                + "mapping_version_id, direction, kind, event_id, object_api_name, record_id, state, finished_at, "
                + "created_by, updated_by) values (?, ?, ?, ?, 'OUT', 'UPDATE', ?, 'ObjectA__c', ?, ?, "
                + "case when ? = 'PENDING' then null else now() end, ?, ?)", id, world.tenant().value(),
                world.connection(), world.version(), UUID.randomUUID(), UUID.randomUUID(), state, state, ACTOR, ACTOR);
        return id;
    }

    private static String move(String set) {
        return "update integration_delivery set " + set + ", version = version + 1, updated_by = ? where id = ?";
    }

    @Test
    void aDeliveryStartsQueuedSkippedOrDeadLetteredAndNeverAsADoneOneOrASendingOne() throws SQLException {
        World world = world();

        for (String state : new String[] {"PENDING", "SKIPPED", "DEAD_LETTERED"}) {
            assertThat(stateOf(Role.APP, world.tenant(), "insert into integration_delivery (tenant_id, "
                    + "connection_id, mapping_version_id, direction, kind, event_id, object_api_name, state, "
                    + "finished_at, created_by, updated_by) values (?, ?, ?, 'OUT', 'UPDATE', ?, 'ObjectA__c', ?, "
                    + "case when ? = 'PENDING' then null else now() end, ?, ?)", world.tenant().value(),
                    world.connection(), world.version(), UUID.randomUUID(), state, state, ACTOR, ACTOR))
                    .as("starts " + state).isNull();
        }
        for (String state : new String[] {"DONE", "SENDING", "SUPERSEDED"}) {
            assertThat(stateOf(Role.APP, world.tenant(), "insert into integration_delivery (tenant_id, "
                    + "connection_id, mapping_version_id, direction, kind, event_id, object_api_name, state, "
                    + "finished_at, created_by, updated_by) values (?, ?, ?, 'OUT', 'UPDATE', ?, 'ObjectA__c', ?, "
                    + "case when ? in ('SENDING') then null else now() end, ?, ?)", world.tenant().value(),
                    world.connection(), world.version(), UUID.randomUUID(), state, state, ACTOR, ACTOR))
                    .as("cannot start " + state).isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aFinishedDeliveryIsFinalAsTheApplicationAndAsTheOwner() throws SQLException {
        for (Role role : Role.values()) {
            for (String ended : new String[] {"DONE", "SKIPPED", "SUPERSEDED"}) {
                World world = world();
                UUID id = delivery(world, "PENDING");
                run(role, world.tenant(), move("state = '" + ended + "', finished_at = now()"), ACTOR, id);

                assertThat(stateOf(role, world.tenant(), move("reason = 'rejected'"), ACTOR, id))
                        .as(role + " cannot touch a " + ended + " delivery").isEqualTo(CHECK_VIOLATION);
                assertThat(stateOf(role, world.tenant(), move("state = 'PENDING', finished_at = null"), ACTOR, id))
                        .as(role + " cannot reopen a " + ended + " delivery").isEqualTo(CHECK_VIOLATION);
            }
        }
    }

    @Test
    void aDeadLetterMovesOnlyByAReplayThatStartsANewGenerationWithTheAttemptsCountedAfresh() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID id = delivery(world, "PENDING");
            run(role, world.tenant(), move("state = 'DEAD_LETTERED', attempts = 3, finished_at = now()"), ACTOR, id);

            assertThat(stateOf(role, world.tenant(), move("state = 'DONE'"), ACTOR, id))
                    .as(role + ": a dead letter does not become done").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'PENDING', finished_at = null"), ACTOR, id))
                    .as(role + ": no new generation, no replay").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'PENDING', finished_at = null, generation = 3, "
                    + "attempts = 0"), ACTOR, id)).as(role + ": a generation is never skipped")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'PENDING', finished_at = null, generation = 2, "
                    + "attempts = 2"), ACTOR, id)).as(role + ": the attempts are counted afresh")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'PENDING', finished_at = null, generation = 2, "
                    + "attempts = 0"), ACTOR, id)).as(role + ": the replay itself").isNull();
            assertThat(stateOf(role, world.tenant(), move("generation = 3"), ACTOR, id))
                    .as(role + ": only a replay starts a generation").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void attemptsAreNeverTakenBackAndWhatADeliveryIsAboutNeverChanges() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID id = delivery(world, "PENDING");
            run(role, world.tenant(), move("attempts = 2"), ACTOR, id);

            assertThat(stateOf(role, world.tenant(), move("attempts = 1"), ACTOR, id)).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("record_id = gen_random_uuid()"), ACTOR, id))
                    .as(role + " cannot move a delivery to another record").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("object_api_name = 'ObjectB__c'"), ACTOR, id))
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("kind = 'DELETE'"), ACTOR, id)).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("queued_reason = 'Not a key!'"), ACTOR, id))
                    .as("a waiting reason is a stable key").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("last_status_class = '404'"), ACTOR, id))
                    .as("a class of status, never a number").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aDeliveryThatWaitsForTheOtherSideIsNeverSentAgain() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID id = delivery(world, "PENDING");
            run(role, world.tenant(), move("state = 'WAITING_REMOTE', remote_ref = '/jobs/1'"), ACTOR, id);

            assertThat(stateOf(role, world.tenant(), move("state = 'SENDING'"), ACTOR, id))
                    .as(role + " cannot send a job again").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'PENDING'"), ACTOR, id))
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'DONE', finished_at = now()"), ACTOR, id))
                    .as("but it may end").isNull();
        }
    }

    @Test
    void anAttemptRecordIsNeverChangedAndHoldsNothingButAStableReason() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID delivery = delivery(world, "PENDING");
            UUID attempt = UUID.randomUUID();
            run(role, world.tenant(), "insert into integration_attempt (id, tenant_id, delivery_id, connection_id, "
                    + "generation, attempt_no, started_at, outcome, reason, created_by, updated_by) values "
                    + "(?, ?, ?, ?, 1, 1, now(), 'RETRY', 'timed_out', ?, ?)", attempt, world.tenant().value(),
                    delivery, world.connection(), ACTOR, ACTOR);

            assertThat(stateOf(role, world.tenant(), "update integration_attempt set reason = 'rejected', "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, attempt))
                    .as(role + " cannot change an attempt").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "insert into integration_attempt (tenant_id, delivery_id, "
                    + "connection_id, generation, attempt_no, started_at, outcome, reason, created_by, updated_by) "
                    + "values (?, ?, ?, 1, 2, now(), 'RETRY', 'https://example.test/secret?token=1', ?, ?)",
                    world.tenant().value(), delivery, world.connection(), ACTOR, ACTOR))
                    .as("a reason is never text").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "insert into integration_attempt (tenant_id, delivery_id, "
                    + "connection_id, generation, attempt_no, started_at, outcome, status_class, created_by, "
                    + "updated_by) values (?, ?, ?, 1, 3, now(), 'RETRY', '503', ?, ?)", world.tenant().value(),
                    delivery, world.connection(), ACTOR, ACTOR)).as("a class of status, never a number")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "insert into integration_attempt (tenant_id, delivery_id, "
                    + "connection_id, generation, attempt_no, started_at, outcome, created_by, updated_by) values "
                    + "(?, ?, ?, 1, 1, now(), 'RETRY', ?, ?)", world.tenant().value(), delivery, world.connection(),
                    ACTOR, ACTOR)).as("one record for one attempt").isEqualTo(UNIQUE_VIOLATION);
        }
    }

    @Test
    void theFlowStateKeepsItsShape() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID flow = UUID.randomUUID();
            run(role, world.tenant(), "insert into integration_flow_state (id, tenant_id, connection_id, created_by, "
                    + "updated_by) values (?, ?, ?, ?, ?)", flow, world.tenant().value(), world.connection(), ACTOR,
                    ACTOR);

            assertThat(stateOf(role, world.tenant(), "insert into integration_flow_state (tenant_id, connection_id, "
                    + "created_by, updated_by) values (?, ?, ?, ?)", world.tenant().value(), world.connection(), ACTOR,
                    ACTOR)).as("one row for one connection").isEqualTo(UNIQUE_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_flow_state set connection_id = ?, "
                    + "version = version + 1, updated_by = ? where id = ?", UUID.randomUUID(), ACTOR, flow))
                    .isNotNull();
            assertThat(stateOf(role, world.tenant(), "update integration_flow_state set forced = 'OPEN', "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, flow))
                    .as("a forced circuit says who forced it").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_flow_state set forced = 'OPEN', "
                    + "forced_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR, ACTOR, flow))
                    .isNull();
            assertThat(stateOf(role, world.tenant(), "update integration_flow_state set circuit_state = 'OPEN', "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, flow))
                    .as("an open circuit says until when").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_flow_state set rate_per_minute = 0, "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, flow)).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_flow_state set max_concurrent = 1000, "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, flow)).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_flow_state set last_failure_reason = "
                    + "'Some Text', version = version + 1, updated_by = ? where id = ?", ACTOR, flow))
                    .as("a reason is a stable key").isEqualTo(CHECK_VIOLATION);
        }
    }
}
