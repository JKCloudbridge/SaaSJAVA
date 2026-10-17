package app.platform.workflow.internal;

import app.platform.sharedkernel.ActorId;
import app.platformapi.WorkflowDeadLetterSummaryView;
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
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about executions and their steps that runs inside one organization's own tenant context (ADR-0137,
 * ADR-0141 to ADR-0143): making an execution, the steps and their attempts, the final status, replay, cancel and the
 * history the API shows. What spans organizations (claiming, clean-up) is {@link DispatchStore}, the only other class
 * that names these tables.
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
     * @param chainRoot the first cause of the chain, or null when the execution is its own root
     * @param chainDepth how many executions lead to this one
     * @param chainWorkflows the workflows already in the chain
     * @param runAt when it is due, or null for now
     * @param endStatus a status to make it in at once ({@code SKIPPED} for a loop), or null for {@code QUEUED}
     * @param reason the reason of a delayed or ended execution, or null
     */
    record NewExecution(UUID workflowId, UUID versionId, String workflowApiName, int versionNumber,
            String objectApiName, UUID recordId, Long recordVersion, String trigger, UUID eventId, Instant firingAt,
            String key, UUID chainRoot, int chainDepth, List<UUID> chainWorkflows, Instant runAt, String endStatus,
            String reason) {

        /** An execution with no chain, due now (the shape of a schedule's firing). */
        NewExecution(UUID workflowId, UUID versionId, String workflowApiName, int versionNumber,
                String objectApiName, UUID recordId, Long recordVersion, String trigger, UUID eventId,
                Instant firingAt, String key) {
            this(workflowId, versionId, workflowApiName, versionNumber, objectApiName, recordId, recordVersion,
                    trigger, eventId, firingAt, key, null, 0, List.of(), null, null, null);
        }
    }

    /** What the history can be asked for. */
    record Filter(String workflow, String object, String status, UUID record, Instant from, Instant to,
            String trigger) {

        /** A filter without a trigger. */
        Filter(String workflow, String object, String status, UUID record, Instant from, Instant to) {
            this(workflow, object, status, record, from, to, null);
        }
    }

    /** The position a page of the history continues after. */
    record After(Instant createdAt, UUID id) {
    }

    /** What a step's failed attempt did to the execution. */
    enum Attempt {
        /** The step waits for its next attempt. */
        RETRYING,
        /** The attempts are spent or the failure is permanent: the step failed. */
        FAILED
    }

    /** The row a replay is made from. */
    record Replayable(UUID id, String status, int generation, Integer failedPosition, int stepsDone) {
    }

    private static final String COLUMNS = "e.id, e.workflow_api_name, e.workflow_version_number, e.object_api_name, "
            + "e.trigger_kind, e.status, e.reason, e.record_id, e.record_version, e.attempts, e.created_at, "
            + "e.started_at, e.finished_at, e.failure_type, e.replay_of, e.replay_generation, e.chain_depth, "
            + "e.resume_step, e.run_at, "
            + "(select r.id from workflow_execution r where r.replay_of = e.id and r.deleted_at is null limit 1) "
            + "as replayed_by";

    private static final String SYSTEM = "system";

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
        String status = execution.endStatus() == null ? "QUEUED" : execution.endStatus();
        return jdbc.sql("insert into workflow_execution (workflow_id, workflow_version_id, workflow_api_name, "
                        + "workflow_version_number, object_api_name, record_id, record_version, trigger_kind, "
                        + "event_id, firing_at, idempotency_key, chain_root, chain_depth, chain_workflows, status, "
                        + "reason, run_at, finished_at, created_by, updated_by) values (:workflow, :version, "
                        + ":workflowName, :number, :object, :record, :recordVersion, :trigger, :event, :firing, "
                        + ":key, :root, :depth, cast(string_to_array(:workflows, ',') as uuid[]), "
                        + "cast(:status as text), :reason, coalesce(cast(:runAt as timestamptz), now()), "
                        + "case when cast(:status as text) = 'QUEUED' then null else now() end, :system, :system) "
                        + "on conflict (tenant_id, idempotency_key) where deleted_at is null do nothing")
                .param("workflow", execution.workflowId()).param("version", execution.versionId())
                .param("workflowName", execution.workflowApiName()).param("number", execution.versionNumber())
                .param("object", execution.objectApiName()).param("record", execution.recordId())
                .param("recordVersion", execution.recordVersion()).param("trigger", execution.trigger())
                .param("event", execution.eventId())
                .param("firing", execution.firingAt() == null ? null : Timestamp.from(execution.firingAt()))
                .param("key", execution.key()).param("root", execution.chainRoot())
                .param("depth", execution.chainDepth()).param("workflows", join(execution.chainWorkflows()))
                .param("status", status).param("reason", execution.reason())
                .param("runAt", execution.runAt() == null ? null : Timestamp.from(execution.runAt()))
                .param(SYSTEM, ActorId.SYSTEM.value()).update() == 1;
    }

    private static String join(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    /** How many executions an object made in the window, for the rate guard of the handler (ADR-0140). */
    int createdSince(String objectApiName, Instant since) {
        return jdbc.sql("select count(*) from workflow_execution where object_api_name = :object "
                        + "and created_at >= :since")
                .param("object", objectApiName).param("since", Timestamp.from(since)).query(Integer.class).single();
    }

    // ---- the history ----

    Optional<WorkflowExecutionView> get(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from workflow_execution e where e.id = :id and e.deleted_at is null")
                .param("id", id).query(ExecutionStore::view).optional()
                .map(view -> withSteps(view, steps(id)));
    }

    /** One page of the history, newest first; one more than {@code limit} is read so the caller sees if more follow. */
    List<WorkflowExecutionView> list(Filter filter, After after, int limit) {
        StringBuilder sql = new StringBuilder(
                "select " + COLUMNS + " from workflow_execution e where e.deleted_at is null");
        List<Object[]> parameters = new ArrayList<>();
        if (filter.workflow() != null) {
            sql.append(" and e.workflow_api_name = :workflow");
            parameters.add(new Object[] {"workflow", filter.workflow()});
        }
        if (filter.object() != null) {
            sql.append(" and e.object_api_name = :object");
            parameters.add(new Object[] {"object", filter.object()});
        }
        if (filter.status() != null) {
            sql.append(" and e.status = :status");
            parameters.add(new Object[] {"status", filter.status()});
        }
        if (filter.trigger() != null) {
            sql.append(" and e.trigger_kind = :trigger");
            parameters.add(new Object[] {"trigger", filter.trigger()});
        }
        if (filter.record() != null) {
            sql.append(" and e.record_id = :record");
            parameters.add(new Object[] {"record", filter.record()});
        }
        if (filter.from() != null) {
            sql.append(" and e.created_at >= :from");
            parameters.add(new Object[] {"from", Timestamp.from(filter.from())});
        }
        if (filter.to() != null) {
            sql.append(" and e.created_at < :to");
            parameters.add(new Object[] {"to", Timestamp.from(filter.to())});
        }
        if (after != null) {
            sql.append(" and (e.created_at, e.id) < (:afterTime, :afterId)");
            parameters.add(new Object[] {"afterTime", Timestamp.from(after.createdAt())});
            parameters.add(new Object[] {"afterId", after.id()});
        }
        sql.append(" order by e.created_at desc, e.id desc limit :limit");
        var statement = jdbc.sql(sql.toString()).param("limit", limit + 1);
        for (Object[] parameter : parameters) {
            statement = statement.param((String) parameter[0], parameter[1]);
        }
        return statement.query(ExecutionStore::view).list();
    }

    /** The dead letters waiting for a person, counted by workflow and reason, the largest group first. */
    WorkflowDeadLetterSummaryView deadLetters() {
        List<WorkflowDeadLetterSummaryView.DeadLetterGroup> groups = jdbc
                .sql("select workflow_api_name, object_api_name, "
                        + "coalesce(reason, 'unknown') as reason, count(*) as total from workflow_execution "
                        + "where status = 'DEAD_LETTERED' and deleted_at is null "
                        + "and not exists (select 1 from workflow_execution r where r.replay_of = "
                        + "workflow_execution.id and r.deleted_at is null) "
                        + "group by workflow_api_name, object_api_name, coalesce(reason, 'unknown') "
                        + "order by total desc, workflow_api_name, reason")
                .query((rs, row) -> new WorkflowDeadLetterSummaryView.DeadLetterGroup(rs.getString("workflow_api_name"),
                        rs.getString("object_api_name"), rs.getString("reason"), rs.getLong("total")))
                .list();
        return new WorkflowDeadLetterSummaryView(groups.stream().mapToLong(
                WorkflowDeadLetterSummaryView.DeadLetterGroup::count).sum(), groups);
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
                .param("lease", lease.toMillis() / 1000.0).param(SYSTEM, ActorId.SYSTEM.value()).param("id", id)
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
                .param(SYSTEM, ActorId.SYSTEM.value()).param("id", id).param("token", token).update() == 1;
    }

    /**
     * Puts the execution back in the queue, due after the delay, with the reason it waits (ADR-0142). It keeps no
     * lease, so no worker and no connection is held while it waits.
     *
     * @param reportedBack whether the worker that held the claim reported honestly what happened (a step that failed
     * and will be attempted again): then the claim does not count against the limit of claims, which is for workers
     * that die or stall and never report (a failed attempt is not a crash)
     * @return true when the claim was still the caller's
     */
    boolean reschedule(UUID id, long token, Duration delay, String reason, String failureType,
            boolean reportedBack) {
        return jdbc.sql("update workflow_execution set status = 'QUEUED', reason = :reason, failure_type = :failure, "
                        + "run_at = now() + make_interval(secs => cast(:delay as double precision)), "
                        + "attempts = case when :reported then 0 else attempts end, "
                        + "locked_until = null, version = version + 1, updated_by = :system "
                        + "where id = :id and version = :token and deleted_at is null")
                .param("reported", reportedBack)
                .param("reason", reason).param("failure", failureType).param("delay", delay.toMillis() / 1000.0)
                .param(SYSTEM, ActorId.SYSTEM.value()).param("id", id).param("token", token).update() == 1;
    }

    /**
     * Cancels an execution that has not started or waits for a retry. One atomic update that checks the status: a
     * worker that claims the same row at the same moment either wins or loses, never both (ADR-0141).
     *
     * @return true when it was cancelled
     */
    boolean cancel(UUID id) {
        boolean cancelled = jdbc.sql("update workflow_execution set status = 'CANCELLED', "
                        + "reason = 'cancelled_by_person', finished_at = now(), locked_until = null, "
                        + "version = version + 1, updated_by = :system "
                        + "where id = :id and status = 'QUEUED' and deleted_at is null")
                .param(SYSTEM, ActorId.SYSTEM.value()).param("id", id).update() == 1;
        if (cancelled) {
            // A step that waited for its next attempt will not get one.
            jdbc.sql("update workflow_step set status = 'FAILED', finished_at = now(), next_attempt_at = null, "
                            + "version = version + 1, updated_by = :system "
                            + "where execution_id = :id and status = 'RETRYING' and deleted_at is null")
                    .param(SYSTEM, ActorId.SYSTEM.value()).param("id", id).update();
        }
        return cancelled;
    }

    // ---- replay ----

    /** What a replay needs to know about an execution, or empty when there is no such execution. */
    Optional<Replayable> replayable(UUID id) {
        return jdbc.sql("select e.id, e.status, e.replay_generation, "
                        + "(select min(s.position) from workflow_step s where s.execution_id = e.id and "
                        + "s.status in ('FAILED', 'RETRYING', 'RUNNING') and s.deleted_at is null) as failed, "
                        + "(select count(*) from workflow_step s where s.execution_id = e.id and "
                        + "s.status = 'COMPLETED' and s.deleted_at is null) as done "
                        + "from workflow_execution e where e.id = :id and e.deleted_at is null")
                .param("id", id)
                .query((rs, row) -> {
                    int failed = rs.getInt("failed");
                    Integer failedPosition = rs.wasNull() ? null : failed;
                    return new Replayable(rs.getObject("id", UUID.class), rs.getString("status"),
                            rs.getInt("replay_generation"), failedPosition, rs.getInt("done"));
                }).optional();
    }

    /** The execution that replays this one, if there is one. */
    Optional<UUID> replayOf(UUID id) {
        return jdbc.sql("select id from workflow_execution where replay_of = :id and deleted_at is null limit 1")
                .param("id", id).query(UUID.class).optional();
    }

    /**
     * Makes the replay of a dead-lettered execution: the same workflow version, record and chain, resuming at the step
     * given. Two clicks at the same moment make one: the second finds the unique key of the replay and makes nothing.
     *
     * @return the new execution, or empty when one exists already (or the original is not replayable)
     */
    Optional<UUID> insertReplay(UUID original, int resumeStep) {
        return jdbc.sql("insert into workflow_execution (workflow_id, workflow_version_id, workflow_api_name, "
                        + "workflow_version_number, object_api_name, record_id, record_version, trigger_kind, "
                        + "replay_of, replay_generation, resume_step, idempotency_key, chain_root, chain_depth, "
                        + "chain_workflows, created_by, updated_by) "
                        + "select e.workflow_id, e.workflow_version_id, e.workflow_api_name, "
                        + "e.workflow_version_number, e.object_api_name, e.record_id, e.record_version, "
                        + "e.trigger_kind, e.id, e.replay_generation + 1, :resume, "
                        + "'replay:' || e.id || ':' || (e.replay_generation + 1), coalesce(e.chain_root, e.id), "
                        + "e.chain_depth, e.chain_workflows, :system, :system from workflow_execution e "
                        + "where e.id = :id and e.status in ('DEAD_LETTERED', 'FAILED') and e.deleted_at is null "
                        + "on conflict do nothing returning id")
                .param("resume", resumeStep).param(SYSTEM, ActorId.SYSTEM.value()).param("id", original)
                .query(UUID.class).optional();
    }

    // ---- retention ----

    /**
     * Removes finished executions (their steps go with them) older than the retention, and dead letters older than
     * theirs, at most one batch.
     */
    int purgeFinished(Duration retention, Duration deadLetterRetention, int batch) {
        return jdbc.sql("delete from workflow_execution where id in (select id from workflow_execution "
                        + "where (status <> 'DEAD_LETTERED' and finished_at < now() - make_interval(secs => "
                        + "cast(:seconds as double precision))) "
                        + "or (status = 'DEAD_LETTERED' and finished_at < now() - make_interval(secs => "
                        + "cast(:deadSeconds as double precision))) "
                        + "order by finished_at limit :batch)")
                .param("seconds", retention.toMillis() / 1000.0)
                .param("deadSeconds", deadLetterRetention.toMillis() / 1000.0).param("batch", batch).update();
    }

    // ---- steps ----

    /** The positions of the steps that already completed, so that a run taken over goes on after them. */
    List<Integer> completedPositions(UUID executionId) {
        return jdbc.sql("select position from workflow_step where execution_id = :id and status = 'COMPLETED' "
                        + "and deleted_at is null order by position")
                .param("id", executionId).query(Integer.class).list();
    }

    /** What the steps of an execution have done so far: where each stands and how many attempts of it ended. */
    record StepState(int position, String status, int attempts) {
    }

    /** The state of every step that has begun, so a run taken over or retried goes on from the right place. */
    List<StepState> stepStates(UUID executionId) {
        return jdbc.sql("select position, status, attempts from workflow_step where execution_id = :id "
                        + "and deleted_at is null order by position")
                .param("id", executionId)
                .query((rs, row) -> new StepState(rs.getInt("position"), rs.getString("status"),
                        rs.getInt("attempts")))
                .list();
    }

    /** Starts a step, or starts its next attempt when an earlier one failed. */
    void startStep(UUID executionId, int position, String name, String type) {
        jdbc.sql("insert into workflow_step (execution_id, position, name, action_type, created_by, updated_by) "
                        + "values (:id, :position, :name, :type, :system, :system) "
                        + "on conflict (tenant_id, execution_id, position) where deleted_at is null do update set "
                        + "status = 'RUNNING', finished_at = null, next_attempt_at = null, "
                        + "version = workflow_step.version + 1, updated_by = :system")
                .param("id", executionId).param("position", position).param("name", name).param("type", type)
                .param(SYSTEM, ActorId.SYSTEM.value()).update();
    }

    /** Completes the step; the attempt that succeeded counts. */
    void completeStep(UUID executionId, int position) {
        jdbc.sql("update workflow_step set status = 'COMPLETED', finished_at = now(), attempts = attempts + 1, "
                        + "next_attempt_at = null, version = version + 1, updated_by = :system "
                        + "where execution_id = :id and position = :position "
                        + "and status = 'RUNNING' and deleted_at is null")
                .param(SYSTEM, ActorId.SYSTEM.value()).param("id", executionId).param("position", position)
                .update();
    }

    /**
     * Records an attempt that failed, in a transaction of its own because the step's own transaction was undone: the
     * step waits for its next attempt ({@link Attempt#RETRYING}) or has failed for good ({@link Attempt#FAILED}). Only
     * the type name and the stable reason are kept, never a message.
     *
     * @param delay when the next attempt is due, for {@code RETRYING}
     */
    void recordFailedAttempt(UUID executionId, int position, String name, String type, Attempt outcome,
            Duration delay, String failureType, String reason) {
        boolean retrying = outcome == Attempt.RETRYING;
        jdbc.sql("insert into workflow_step (execution_id, position, name, action_type, status, finished_at, "
                        + "attempts, next_attempt_at, failure_type, last_failure_type, last_reason, created_by, "
                        + "updated_by) values (:id, :position, :name, :type, :status, "
                        + "case when :retrying then null else now() end, 1, "
                        + "case when :retrying then now() + make_interval(secs => cast(:delay as double precision)) "
                        + "else null end, :failure, :failure, :reason, :system, :system) "
                        + "on conflict (tenant_id, execution_id, position) where deleted_at is null do update set "
                        + "status = excluded.status, finished_at = excluded.finished_at, "
                        + "attempts = workflow_step.attempts + 1, next_attempt_at = excluded.next_attempt_at, "
                        + "failure_type = excluded.failure_type, last_failure_type = excluded.last_failure_type, "
                        + "last_reason = excluded.last_reason, version = workflow_step.version + 1, "
                        + "updated_by = :system")
                .param("id", executionId).param("position", position).param("name", name).param("type", type)
                .param("status", retrying ? "RETRYING" : "FAILED").param("retrying", retrying)
                .param("delay", delay == null ? 0.0 : delay.toMillis() / 1000.0).param("failure", failureType)
                .param("reason", reason).param(SYSTEM, ActorId.SYSTEM.value()).update();
    }

    /** How many mails the steps of this organization completed since the instant (the hourly limit, ADR-0138). */
    int mailStepsSince(Instant since) {
        return jdbc.sql("select count(*) from workflow_step where action_type = 'SEND_EMAIL' "
                        + "and status = 'COMPLETED' and finished_at >= :since and deleted_at is null")
                .param("since", Timestamp.from(since)).query(Integer.class).single();
    }

    private List<WorkflowStepView> steps(UUID executionId) {
        return jdbc.sql("select position, name, action_type, status, started_at, finished_at, "
                        + "coalesce(failure_type, last_failure_type) as failure_type, attempts, next_attempt_at, "
                        + "last_reason from workflow_step where execution_id = :id and deleted_at is null "
                        + "order by position")
                .param("id", executionId).query(ExecutionStore::step).list();
    }

    private static WorkflowExecutionView withSteps(WorkflowExecutionView view, List<WorkflowStepView> steps) {
        return new WorkflowExecutionView(view.id(), view.workflow(), view.objectApiName(), view.workflowVersion(),
                view.trigger(), view.status(), view.reason(), view.recordId(), view.recordVersion(), view.attempts(),
                view.queuedAt(), view.startedAt(), view.finishedAt(), view.failureType(), steps, view.replayOf(),
                view.replayedBy(), view.replayGeneration(), view.chainDepth(), view.resumeStep(), view.runAt());
    }

    private static WorkflowExecutionView view(ResultSet rs, int row) throws SQLException {
        long recordVersion = rs.getLong("record_version");
        Long version = rs.wasNull() ? null : recordVersion;
        return new WorkflowExecutionView(rs.getObject("id", UUID.class), rs.getString("workflow_api_name"),
                rs.getString("object_api_name"), rs.getInt("workflow_version_number"), rs.getString("trigger_kind"),
                rs.getString("status"), rs.getString("reason"), rs.getObject("record_id", UUID.class), version,
                rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant(), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("failure_type"), null, rs.getObject("replay_of", UUID.class),
                rs.getObject("replayed_by", UUID.class), rs.getInt("replay_generation"), rs.getInt("chain_depth"),
                rs.getInt("resume_step"), rs.getTimestamp("run_at").toInstant());
    }

    private static WorkflowStepView step(ResultSet rs, int row) throws SQLException {
        return new WorkflowStepView(rs.getInt("position"), rs.getString("name"), rs.getString("action_type"),
                rs.getString("status"), rs.getTimestamp("started_at").toInstant(), instant(rs, "finished_at"),
                rs.getString("failure_type"), rs.getInt("attempts"), instant(rs, "next_attempt_at"),
                rs.getString("last_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
