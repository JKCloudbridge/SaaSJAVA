package app.platform.workflow.internal;

import app.platform.sharedkernel.ActorId;
import app.platformapi.WorkflowExecutionView;
import app.platformapi.WorkflowStepView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about executions and their steps that runs inside one organization's own tenant context (ADR-0137):
 * making an execution, the steps, the final status and the history the API shows. What spans organizations (claiming,
 * clean-up) is {@link DispatchStore}, the only other class that names these tables.
 *
 * <p>An execution keeps facts and never a value: identifiers, a version number, a stable reason key and the type name
 * of a failure. Every change to a claimed execution carries the version the claim left it at (the claim's token); a
 * change returns the new token, or nothing when the claim was lost to an instance that took the execution over.
 */
@Repository
class ExecutionStore {

    /**
     * An execution to make.
     *
     * @param key what makes one cause one execution: the event and the version, or the firing, the version and the
     *        record
     */
    record NewExecution(UUID workflowId, UUID versionId, String workflowApiName, int versionNumber,
            String objectApiName, UUID recordId, Long recordVersion, String trigger, UUID eventId, Instant firingAt,
            String key) {
    }

    /** What the history can be asked for. */
    record Filter(String workflow, String object, String status, UUID record, Instant from, Instant to) {
    }

    /** The position a page of the history continues after. */
    record After(Instant createdAt, UUID id) {
    }

    private static final String COLUMNS = "id, workflow_api_name, workflow_version_number, object_api_name, "
            + "trigger_kind, status, reason, record_id, record_version, attempts, created_at, started_at, "
            + "finished_at, failure_type";

    private final JdbcClient jdbc;

    ExecutionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes the execution unless its cause already made one.
     *
     * @return true when a row was made
     */
    boolean insert(NewExecution execution) {
        return jdbc.sql("insert into workflow_execution (workflow_id, workflow_version_id, workflow_api_name, "
                        + "workflow_version_number, object_api_name, record_id, record_version, trigger_kind, "
                        + "event_id, firing_at, idempotency_key, created_by, updated_by) values (:workflow, "
                        + ":version, :workflowName, :number, :object, :record, :recordVersion, :trigger, :event, "
                        + ":firing, :key, :system, :system) "
                        + "on conflict (tenant_id, idempotency_key) where deleted_at is null do nothing")
                .param("workflow", execution.workflowId()).param("version", execution.versionId())
                .param("workflowName", execution.workflowApiName()).param("number", execution.versionNumber())
                .param("object", execution.objectApiName()).param("record", execution.recordId())
                .param("recordVersion", execution.recordVersion()).param("trigger", execution.trigger())
                .param("event", execution.eventId())
                .param("firing", execution.firingAt() == null ? null : Timestamp.from(execution.firingAt()))
                .param("key", execution.key()).param("system", ActorId.SYSTEM.value()).update() == 1;
    }

    // ---- the history ----

    Optional<WorkflowExecutionView> get(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from workflow_execution where id = :id and deleted_at is null")
                .param("id", id).query(ExecutionStore::view).optional()
                .map(view -> withSteps(view, steps(id)));
    }

    /** One page of the history, newest first; one more than {@code limit} is read so the caller sees if more follow. */
    List<WorkflowExecutionView> list(Filter filter, After after, int limit) {
        StringBuilder sql = new StringBuilder(
                "select " + COLUMNS + " from workflow_execution where deleted_at is null");
        List<Object[]> parameters = new ArrayList<>();
        if (filter.workflow() != null) {
            sql.append(" and workflow_api_name = :workflow");
            parameters.add(new Object[] {"workflow", filter.workflow()});
        }
        if (filter.object() != null) {
            sql.append(" and object_api_name = :object");
            parameters.add(new Object[] {"object", filter.object()});
        }
        if (filter.status() != null) {
            sql.append(" and status = :status");
            parameters.add(new Object[] {"status", filter.status()});
        }
        if (filter.record() != null) {
            sql.append(" and record_id = :record");
            parameters.add(new Object[] {"record", filter.record()});
        }
        if (filter.from() != null) {
            sql.append(" and created_at >= :from");
            parameters.add(new Object[] {"from", Timestamp.from(filter.from())});
        }
        if (filter.to() != null) {
            sql.append(" and created_at < :to");
            parameters.add(new Object[] {"to", Timestamp.from(filter.to())});
        }
        if (after != null) {
            sql.append(" and (created_at, id) < (:afterTime, :afterId)");
            parameters.add(new Object[] {"afterTime", Timestamp.from(after.createdAt())});
            parameters.add(new Object[] {"afterId", after.id()});
        }
        sql.append(" order by created_at desc, id desc limit :limit");
        var statement = jdbc.sql(sql.toString()).param("limit", limit + 1);
        for (Object[] parameter : parameters) {
            statement = statement.param((String) parameter[0], parameter[1]);
        }
        return statement.query(ExecutionStore::view).list();
    }

    // ---- one execution being run ----

    /**
     * Renews the lease of the claim. The row stays locked until the transaction ends, so a step that runs in the same
     * transaction cannot be run twice at once, and a claim made meanwhile by another instance waits for it.
     *
     * @return the new token, or empty when the claim was lost
     */
    Optional<Long> renew(UUID id, long token, Duration lease) {
        return jdbc.sql("update workflow_execution set locked_until = now() + make_interval(secs => "
                        + "cast(:lease as double precision)), version = version + 1, updated_by = :system "
                        + "where id = :id and version = :token and status = 'RUNNING' returning version")
                .param("lease", lease.toMillis() / 1000.0).param("system", ActorId.SYSTEM.value()).param("id", id)
                .param("token", token).query(Long.class).optional();
    }

    /**
     * Gives the execution its final status.
     *
     * @return true when the claim was still the caller's
     */
    boolean finish(UUID id, long token, String status, String reason, String failureType) {
        return jdbc.sql("update workflow_execution set status = :status, reason = :reason, "
                        + "failure_type = :failure, finished_at = now(), locked_until = null, "
                        + "version = version + 1, updated_by = :system "
                        + "where id = :id and version = :token and deleted_at is null")
                .param("status", status).param("reason", reason).param("failure", failureType)
                .param("system", ActorId.SYSTEM.value()).param("id", id).param("token", token).update() == 1;
    }

    /** Removes finished executions (their steps go with them) older than the retention, at most one batch. */
    int purgeFinished(Duration retention, int batch) {
        return jdbc.sql("delete from workflow_execution where id in (select id from workflow_execution "
                        + "where finished_at < now() - make_interval(secs => cast(:seconds as double precision)) "
                        + "order by finished_at limit :batch)")
                .param("seconds", retention.toMillis() / 1000.0).param("batch", batch).update();
    }

    // ---- steps ----

    /** The positions of the steps that already completed, so that a run taken over goes on after them. */
    List<Integer> completedPositions(UUID executionId) {
        return jdbc.sql("select position from workflow_step where execution_id = :id and status = 'COMPLETED' "
                        + "and deleted_at is null order by position")
                .param("id", executionId).query(Integer.class).list();
    }

    void startStep(UUID executionId, int position, String name, String type) {
        jdbc.sql("insert into workflow_step (execution_id, position, name, action_type, created_by, updated_by) "
                        + "values (:id, :position, :name, :type, :system, :system)")
                .param("id", executionId).param("position", position).param("name", name).param("type", type)
                .param("system", ActorId.SYSTEM.value()).update();
    }

    void completeStep(UUID executionId, int position) {
        jdbc.sql("update workflow_step set status = 'COMPLETED', finished_at = now(), version = version + 1, "
                        + "updated_by = :system where execution_id = :id and position = :position "
                        + "and status = 'RUNNING' and deleted_at is null")
                .param("system", ActorId.SYSTEM.value()).param("id", executionId).param("position", position)
                .update();
    }

    /** Records a step that failed, in a transaction of its own because the step's own transaction was undone. */
    void failedStep(UUID executionId, int position, String name, String type, String failureType) {
        jdbc.sql("insert into workflow_step (execution_id, position, name, action_type, status, finished_at, "
                        + "failure_type, created_by, updated_by) values (:id, :position, :name, :type, 'FAILED', "
                        + "now(), :failure, :system, :system) "
                        + "on conflict (tenant_id, execution_id, position) where deleted_at is null do nothing")
                .param("id", executionId).param("position", position).param("name", name).param("type", type)
                .param("failure", failureType).param("system", ActorId.SYSTEM.value()).update();
    }

    private List<WorkflowStepView> steps(UUID executionId) {
        return jdbc.sql("select position, name, action_type, status, started_at, finished_at, failure_type "
                        + "from workflow_step where execution_id = :id and deleted_at is null order by position")
                .param("id", executionId).query(ExecutionStore::step).list();
    }

    private static WorkflowExecutionView withSteps(WorkflowExecutionView view, List<WorkflowStepView> steps) {
        return new WorkflowExecutionView(view.id(), view.workflow(), view.objectApiName(), view.workflowVersion(),
                view.trigger(), view.status(), view.reason(), view.recordId(), view.recordVersion(), view.attempts(),
                view.queuedAt(), view.startedAt(), view.finishedAt(), view.failureType(), steps);
    }

    private static WorkflowExecutionView view(ResultSet rs, int row) throws SQLException {
        long recordVersion = rs.getLong("record_version");
        Long version = rs.wasNull() ? null : recordVersion;
        return new WorkflowExecutionView(rs.getObject("id", UUID.class), rs.getString("workflow_api_name"),
                rs.getString("object_api_name"), rs.getInt("workflow_version_number"), rs.getString("trigger_kind"),
                rs.getString("status"), rs.getString("reason"), rs.getObject("record_id", UUID.class), version,
                rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant(), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("failure_type"), null);
    }

    private static WorkflowStepView step(ResultSet rs, int row) throws SQLException {
        return new WorkflowStepView(rs.getInt("position"), rs.getString("name"), rs.getString("action_type"),
                rs.getString("status"), rs.getTimestamp("started_at").toInstant(), instant(rs, "finished_at"),
                rs.getString("failure_type"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
