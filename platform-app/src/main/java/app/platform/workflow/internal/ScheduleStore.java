package app.platform.workflow.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The statements about the schedules of one organization, run inside that organization's own tenant context (ADR-0135).
 * The rows are kept in step with the published workflows by {@link ScheduleReconciler}; a firing changes only the row
 * it claimed, with the version the claim left it at (the token), so the instance that lost its lease cannot overwrite
 * the outcome of the one that took over.
 */
@Repository
class ScheduleStore {

    /** A stored schedule. */
    record Row(UUID id, UUID workflowId, String trigger, int definitionVersion, Instant nextFireAt, String state,
            Instant coveredThrough, long version) {
    }

    private static final String COLUMNS = "id, workflow_id, trigger_kind, definition_version, next_fire_at, state, "
            + "covered_through, version";

    private final JdbcClient jdbc;

    ScheduleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every schedule of the organization. */
    List<Row> all() {
        return jdbc.sql("select " + COLUMNS + " from workflow_schedule where deleted_at is null")
                .query(ScheduleStore::row).list();
    }

    Optional<Row> find(UUID workflowId) {
        return jdbc.sql("select " + COLUMNS + " from workflow_schedule where workflow_id = :workflow "
                        + "and deleted_at is null")
                .param("workflow", workflowId).query(ScheduleStore::row).optional();
    }

    void insert(UUID workflowId, String trigger, int definitionVersion, Instant nextFireAt, String state) {
        jdbc.sql("insert into workflow_schedule (workflow_id, trigger_kind, definition_version, next_fire_at, "
                        + "state, created_by, updated_by) values (:workflow, :trigger, :definition, :next, :state, "
                        + ":system, :system) on conflict (tenant_id, workflow_id) where deleted_at is null do nothing")
                .param("workflow", workflowId).param("trigger", trigger).param("definition", definitionVersion)
                .param("next", Timestamp.from(nextFireAt)).param("state", state)
                .param("system", ActorId.SYSTEM.value()).update();
    }

    /** Starts the schedule over for a changed definition: nothing of the old firing or coverage is kept. */
    void reset(UUID id, String trigger, int definitionVersion, Instant nextFireAt, String state) {
        jdbc.sql("update workflow_schedule set trigger_kind = :trigger, definition_version = :definition, "
                        + "next_fire_at = :next, state = :state, firing_for = null, firing_after = null, "
                        + "covered_through = null, attempts = 0, failure_type = null, locked_until = null, "
                        + "version = version + 1, updated_by = :system where id = :id and deleted_at is null")
                .param("trigger", trigger).param("definition", definitionVersion)
                .param("next", Timestamp.from(nextFireAt)).param("state", state)
                .param("system", ActorId.SYSTEM.value()).param("id", id).update();
    }

    void delete(UUID id) {
        jdbc.sql("delete from workflow_schedule where id = :id").param("id", id).update();
    }

    /**
     * Records how far the firing has got and renews the lease.
     *
     * @return the new token, or empty when the claim was lost
     */
    Optional<Long> progress(UUID id, long token, Instant firingFor, UUID after, Duration lease) {
        return jdbc.sql("update workflow_schedule set state = 'FIRING', firing_for = :firing, firing_after = :after, "
                        + "locked_until = now() + make_interval(secs => cast(:lease as double precision)), "
                        + "version = version + 1, updated_by = :system "
                        + "where id = :id and version = :token and deleted_at is null returning version")
                .param("firing", Timestamp.from(firingFor)).param("after", after)
                .param("lease", lease.toMillis() / 1000.0).param("system", ActorId.SYSTEM.value()).param("id", id)
                .param("token", token).query(Long.class).optional();
    }

    /**
     * Ends the firing and plans the next one.
     *
     * @param covered for the days-after trigger: the instant the records are now covered through; null to keep none
     * @param failureType the type name of why the firing was given up, or null
     * @return true when the claim was still the caller's
     */
    boolean complete(UUID id, long token, Instant nextFireAt, String state, Instant covered, Instant firedAt,
            String failureType) {
        return jdbc.sql("update workflow_schedule set state = :state, next_fire_at = :next, firing_for = null, "
                        + "firing_after = null, covered_through = coalesce(:covered, covered_through), "
                        + "last_fired_at = :fired, attempts = 0, failure_type = :failure, locked_until = null, "
                        + "version = version + 1, updated_by = :system "
                        + "where id = :id and version = :token and deleted_at is null")
                .param("state", state).param("next", Timestamp.from(nextFireAt))
                .param("covered", covered == null ? null : Timestamp.from(covered))
                .param("fired", Timestamp.from(firedAt)).param("failure", failureType)
                .param("system", ActorId.SYSTEM.value()).param("id", id).param("token", token).update() == 1;
    }

    /** Keeps the firing for another try after a pause. @return true when the claim was still the caller's */
    boolean retryLater(UUID id, long token, int attempts, String failureType, Duration pause) {
        return jdbc.sql("update workflow_schedule set attempts = :attempts, failure_type = :failure, "
                        + "locked_until = now() + make_interval(secs => cast(:pause as double precision)), "
                        + "version = version + 1, updated_by = :system "
                        + "where id = :id and version = :token and deleted_at is null")
                .param("attempts", attempts).param("failure", failureType).param("pause", pause.toMillis() / 1000.0)
                .param("system", ActorId.SYSTEM.value()).param("id", id).param("token", token).update() == 1;
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        Timestamp covered = rs.getTimestamp("covered_through");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("workflow_id", UUID.class),
                rs.getString("trigger_kind"), rs.getInt("definition_version"),
                rs.getTimestamp("next_fire_at").toInstant(), rs.getString("state"),
                covered == null ? null : covered.toInstant(), rs.getLong("version"));
    }
}
