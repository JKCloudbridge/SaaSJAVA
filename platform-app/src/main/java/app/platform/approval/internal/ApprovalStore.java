package app.platform.approval.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about approval requests, their steps, tasks, history and delegations (ADR-0145). It runs under the
 * tenant context that was open when the transaction began; every update of a row carries the version it was based on.
 * Nothing here names a table of another module and nothing here is built from text a person typed: every value is a
 * parameter. The history is only ever inserted (the database refuses any other statement on it).
 */
@Repository
class ApprovalStore {

    private static final String INSTANCE_COLUMNS = "i.id, i.process_id, i.process_version_id, i.process_api_name, "
            + "i.process_label, i.process_version_number, i.object_api_name, i.record_id, i.record_version, "
            + "i.submitted_by, i.status, i.current_step, i.attention, i.outcome_failure, i.chain_root, "
            + "i.chain_depth, i.chain_workflows::text as chain_workflows, i.started_at, i.finished_at, i.version";
    private static final String STEP_COLUMNS = "s.id, s.instance_id, s.position, s.name, s.label, s.completion, "
            + "s.status, s.activated_at, s.finished_at, s.version";
    private static final String ASSIGNMENT_COLUMNS = "a.id, a.instance_id, a.step_id, a.assignee_id, a.via, "
            + "a.for_user_id, a.status, a.decided_at, a.created_at, a.version";
    private static final String HISTORY_COLUMNS = "h.id, h.instance_id, h.step_position, h.kind, h.actor_id, "
            + "h.on_behalf_of, h.target_id, h.reason, h.comment, h.process_version_number, h.created_at";

    /** A request. */
    record InstanceRow(UUID id, UUID processId, UUID processVersionId, String processApiName, String processLabel,
            int processVersionNumber, String objectApiName, UUID recordId, long recordVersion, UUID submittedBy,
            String status, Integer currentStep, String attention, String outcomeFailure, UUID chainRoot,
            int chainDepth, List<UUID> chainWorkflows, Instant startedAt, Instant finishedAt, long version) {

        /** Copies the list. */
        InstanceRow {
            chainWorkflows = List.copyOf(chainWorkflows);
        }

        boolean pending() {
            return "PENDING".equals(status);
        }
    }

    /** A step of a request. */
    record StepRow(UUID id, UUID instanceId, int position, String name, String label, String completion,
            String status, Instant activatedAt, Instant finishedAt, long version) {
    }

    /** A task. */
    record AssignmentRow(UUID id, UUID instanceId, UUID stepId, UUID assigneeId, String via, UUID forUserId,
            String status, Instant decidedAt, Instant createdAt, long version) {
    }

    /** One line of the history. */
    record HistoryRow(UUID id, UUID instanceId, Integer stepPosition, String kind, UUID actorId, UUID onBehalfOf,
            UUID targetId, String reason, String comment, int processVersionNumber, Instant createdAt) {
    }

    /** A delegation. */
    record DelegationRow(UUID id, UUID delegatorId, UUID delegateId, String processObject, String processApiName,
            LocalDate startsOn, LocalDate endsOn, String timeZone, Instant createdAt, long version) {
    }

    /** What a list of requests is narrowed by. A null part does not narrow. */
    record InstanceFilter(String status, String objectApiName, String processApiName, Boolean attention,
            UUID recordId, UUID viewer) {
    }

    private final JdbcClient jdbc;

    ApprovalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- requests ----

    InstanceRow insertInstance(UUID requestId, UUID processId, UUID versionId, String processApiName,
            String processLabel, int versionNumber, String objectApiName, UUID recordId, long recordVersion,
            UUID submittedBy, UUID chainRoot, int chainDepth, List<UUID> chainWorkflows, UUID actor) {
        UUID id = jdbc.sql("insert into approval_instance (id, process_id, process_version_id, process_api_name, "
                        + "process_label, process_version_number, object_api_name, record_id, record_version, "
                        + "submitted_by, status, current_step, chain_root, chain_depth, chain_workflows, "
                        + "created_by, updated_by) values (:requestId, :process, :version, :apiName, :label, "
                        + ":number, :object, :record, :recordVersion, :submitter, 'PENDING', 1, :chainRoot, "
                        + ":chainDepth, cast(:chain as text[]), :actor, :actor) returning id")
                .param("requestId", requestId).param("process", processId).param("version", versionId)
                .param("apiName", processApiName)
                .param("label", processLabel).param("number", versionNumber).param("object", objectApiName)
                .param("record", recordId).param("recordVersion", recordVersion)
                .param("submitter", submittedBy, Types.OTHER).param("chainRoot", chainRoot, Types.OTHER)
                .param("chainDepth", chainDepth).param("chain", textArray(chainWorkflows)).param("actor", actor)
                .query(UUID.class).single();
        return instance(id).orElseThrow();
    }

    Optional<InstanceRow> instance(UUID id) {
        return jdbc.sql("select " + INSTANCE_COLUMNS + " from approval_instance i where i.id = :id "
                        + "and i.deleted_at is null")
                .param("id", id).query(ApprovalStore::instanceRow).optional();
    }

    /** The request with its row locked until the end of the transaction (after the record's row, ADR-0148). */
    Optional<InstanceRow> instanceForUpdate(UUID id) {
        return jdbc.sql("select " + INSTANCE_COLUMNS + " from approval_instance i where i.id = :id "
                        + "and i.deleted_at is null for update")
                .param("id", id).query(ApprovalStore::instanceRow).optional();
    }

    /** The pending request of the record, if there is one. */
    Optional<InstanceRow> pendingFor(String objectApiName, UUID recordId) {
        return jdbc.sql("select " + INSTANCE_COLUMNS + " from approval_instance i where i.object_api_name = :object "
                        + "and i.record_id = :record and i.status = 'PENDING' and i.deleted_at is null")
                .param("object", objectApiName).param("record", recordId).query(ApprovalStore::instanceRow)
                .optional();
    }

    /** The latest request of the record, pending or ended. */
    Optional<InstanceRow> latestFor(String objectApiName, UUID recordId) {
        return jdbc.sql("select " + INSTANCE_COLUMNS + " from approval_instance i where i.object_api_name = :object "
                        + "and i.record_id = :record and i.deleted_at is null "
                        + "order by i.started_at desc, i.id desc limit 1")
                .param("object", objectApiName).param("record", recordId).query(ApprovalStore::instanceRow)
                .optional();
    }

    /** Moves the request on when it still has the version the caller read; false when it does not. */
    boolean updateInstance(UUID id, long version, String status, Integer currentStep, String attention,
            String outcomeFailure, boolean finished, UUID actor) {
        return jdbc.sql("update approval_instance set status = :status, current_step = :step, attention = :attention, "
                        + "outcome_failure = :failure, "
                        + "finished_at = case when :finished then coalesce(finished_at, now()) else null end, "
                        + "version = version + 1, updated_by = :actor where id = :id and version = :version "
                        + "and deleted_at is null")
                .param("status", status).param("step", currentStep, Types.INTEGER)
                .param("attention", attention, Types.VARCHAR).param("failure", outcomeFailure, Types.VARCHAR)
                .param("finished", finished).param("actor", actor).param("id", id).param("version", version)
                .update() == 1;
    }

    /** A page of requests, newest first, narrowed by the filter and (for a caller without view-all) by the viewer. */
    List<InstanceRow> instances(InstanceFilter filter, Instant before, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("select " + INSTANCE_COLUMNS + " from approval_instance i "
                + "where i.deleted_at is null");
        List<Object[]> parameters = new ArrayList<>();
        narrow(sql, parameters, filter);
        if (before != null) {
            sql.append(" and (i.started_at, i.id) < (:before, :beforeId)");
            parameters.add(new Object[] {"before", java.sql.Timestamp.from(before)});
            parameters.add(new Object[] {"beforeId", beforeId});
        }
        sql.append(" order by i.started_at desc, i.id desc limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        for (Object[] parameter : parameters) {
            spec = spec.param((String) parameter[0], parameter[1]);
        }
        return spec.param("limit", limit).query(ApprovalStore::instanceRow).list();
    }

    private static void narrow(StringBuilder sql, List<Object[]> parameters, InstanceFilter filter) {
        if (filter.status() != null) {
            sql.append(" and i.status = :status");
            parameters.add(new Object[] {"status", filter.status()});
        }
        if (filter.objectApiName() != null) {
            sql.append(" and i.object_api_name = :object");
            parameters.add(new Object[] {"object", filter.objectApiName()});
        }
        if (filter.processApiName() != null) {
            sql.append(" and i.process_api_name = :process");
            parameters.add(new Object[] {"process", filter.processApiName()});
        }
        if (filter.recordId() != null) {
            sql.append(" and i.record_id = :record");
            parameters.add(new Object[] {"record", filter.recordId()});
        }
        if (Boolean.TRUE.equals(filter.attention())) {
            sql.append(" and i.attention is not null");
        }
        if (filter.viewer() != null) {
            sql.append(" and (i.submitted_by = :viewer or exists (select 1 from approval_assignment av "
                    + "where av.instance_id = i.id and av.assignee_id = :viewer and av.deleted_at is null))");
            parameters.add(new Object[] {"viewer", filter.viewer()});
        }
    }

    /** How many requests are pending under the process. */
    int pendingUnder(String objectApiName, String processApiName) {
        return jdbc.sql("select count(*) from approval_instance i where i.object_api_name = :object "
                        + "and i.process_api_name = :process and i.status = 'PENDING' and i.deleted_at is null")
                .param("object", objectApiName).param("process", processApiName).query(Integer.class).single();
    }

    /** How many requests are pending for records of the object. */
    int pendingOn(String objectApiName) {
        return jdbc.sql("select count(*) from approval_instance i where i.object_api_name = :object "
                        + "and i.status = 'PENDING' and i.deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    /** Whether the record has a pending request: the lock (ADR-0148). */
    boolean locked(String objectApiName, UUID recordId) {
        return jdbc.sql("select exists (select 1 from approval_instance i where i.object_api_name = :object "
                        + "and i.record_id = :record and i.status = 'PENDING' and i.deleted_at is null)")
                .param("object", objectApiName).param("record", recordId).query(Boolean.class).single();
    }

    /** Whether this request is the pending request of the record: the owner of the lock (FA4A, ADR-0187). */
    boolean ownedBy(String objectApiName, UUID recordId, UUID requestId) {
        return jdbc.sql("select exists (select 1 from approval_instance i where i.id = :request "
                        + "and i.object_api_name = :object and i.record_id = :record and i.status = 'PENDING' "
                        + "and i.deleted_at is null)")
                .param("request", requestId).param("object", objectApiName).param("record", recordId)
                .query(Boolean.class).single();
    }

    /** Which of the records have a pending request. */
    Set<UUID> lockedAmong(String objectApiName, Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("select i.record_id from approval_instance i where i.object_api_name = :object "
                        + "and i.status = 'PENDING' and i.deleted_at is null and i.record_id in (:ids)")
                .param("object", objectApiName).param("ids", List.copyOf(recordIds)).query(UUID.class).list());
    }

    // ---- steps ----

    StepRow insertStep(UUID instanceId, int position, String name, String label, String completion, UUID actor) {
        UUID id = jdbc.sql("insert into approval_step_instance (instance_id, position, name, label, completion, "
                        + "created_by, updated_by) values (:instance, :position, :name, :label, :completion, "
                        + ":actor, :actor) returning id")
                .param("instance", instanceId).param("position", position).param("name", name)
                .param("label", label).param("completion", completion).param("actor", actor)
                .query(UUID.class).single();
        return step(id).orElseThrow();
    }

    Optional<StepRow> step(UUID id) {
        return jdbc.sql("select " + STEP_COLUMNS + " from approval_step_instance s where s.id = :id "
                        + "and s.deleted_at is null")
                .param("id", id).query(ApprovalStore::stepRow).optional();
    }

    List<StepRow> steps(UUID instanceId) {
        return jdbc.sql("select " + STEP_COLUMNS + " from approval_step_instance s where s.instance_id = :instance "
                        + "and s.deleted_at is null order by s.position")
                .param("instance", instanceId).query(ApprovalStore::stepRow).list();
    }

    /** The steps of these requests, in order of request and position. */
    List<StepRow> stepsFor(Collection<UUID> instanceIds) {
        if (instanceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + STEP_COLUMNS + " from approval_step_instance s "
                        + "where s.instance_id in (:ids) and s.deleted_at is null order by s.instance_id, s.position")
                .param("ids", List.copyOf(instanceIds)).query(ApprovalStore::stepRow).list();
    }

    /** Sets the status of a step (and its times) when it still has the version the caller read. */
    boolean updateStep(UUID id, long version, String status, UUID actor) {
        boolean updated = jdbc.sql("update approval_step_instance set status = :status, "
                        + "activated_at = case when :status = 'ACTIVE' then now() else activated_at end, "
                        + "finished_at = case when :status in ('APPROVED', 'REJECTED', 'SKIPPED') then now() "
                        + "else finished_at end, version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("status", status).param("actor", actor).param("id", id).param("version", version)
                .update() == 1;
        if (updated && !"ACTIVE".equals(status)) {
            // A step that is not active has no timer (ADR-0159): a reminder is never sent for a decided step.
            stopTimer(id, actor);
        }
        return updated;
    }

    /** Requests that wait for a decision, and those among them that nobody can decide right now. */
    record PendingCounts(long pending, long needingAttention) {
    }

    /** What the failure view of the organization counts (Sprint 25, ADR-0163). */
    PendingCounts pendingCounts() {
        return jdbc.sql("select count(*) filter (where status = 'PENDING') as pending, "
                        + "count(*) filter (where status = 'PENDING' and attention is not null "
                        + "and attention <> 'overdue') as attention from approval_instance where deleted_at is null")
                .query((rs, row) -> new PendingCounts(rs.getLong("pending"), rs.getLong("attention"))).single();
    }

    // ---- timers (Sprint 25, ADR-0159) ----

    /** The timer of an active step. */
    record TimerRow(UUID id, UUID instanceId, UUID stepId, Instant nextTimerAt, int remindersSent,
            Instant escalatedAt, Instant overdueAt, long version) {
    }

    /** Starts the timer of a step that has just become active, looking at it first at {@code first}. */
    void startTimer(UUID instanceId, UUID stepId, Instant first, UUID actor) {
        jdbc.sql("insert into approval_step_timer (instance_id, step_id, next_timer_at, created_by, updated_by) "
                        + "values (:instance, :step, :first, :actor, :actor) "
                        + "on conflict (tenant_id, step_id) where deleted_at is null do nothing")
                .param("instance", instanceId).param("step", stepId).param("first", java.sql.Timestamp.from(first))
                .param("actor", actor).update();
    }

    Optional<TimerRow> timerOf(UUID stepId) {
        return jdbc.sql("select id, instance_id, step_id, next_timer_at, reminders_sent, escalated_at, overdue_at, "
                        + "version from approval_step_timer where step_id = :step and deleted_at is null")
                .param("step", stepId).query(ApprovalStore::timerRow).optional();
    }

    /** Records what the timer did and when it looks next (null: never). False when the row moved on meanwhile. */
    boolean updateTimer(UUID id, long version, Instant next, int remindersSent, Instant escalatedAt,
            Instant overdueAt, UUID actor) {
        return jdbc.sql("update approval_step_timer set next_timer_at = :next, reminders_sent = :sent, "
                        + "escalated_at = :escalated, overdue_at = :overdue, version = version + 1, "
                        + "updated_by = :actor where id = :id and version = :version and deleted_at is null")
                .param("next", next == null ? null : java.sql.Timestamp.from(next), Types.TIMESTAMP)
                .param("sent", remindersSent)
                .param("escalated", escalatedAt == null ? null : java.sql.Timestamp.from(escalatedAt),
                        Types.TIMESTAMP)
                .param("overdue", overdueAt == null ? null : java.sql.Timestamp.from(overdueAt), Types.TIMESTAMP)
                .param("actor", actor).param("id", id).param("version", version).update() == 1;
    }

    /** Makes the timers of every step of a request look no more (the request ended). */
    void stopTimers(UUID instanceId, UUID actor) {
        jdbc.sql("update approval_step_timer set next_timer_at = null, ended_at = coalesce(ended_at, now()), "
                        + "version = version + 1, updated_by = :actor "
                        + "where instance_id = :instance and ended_at is null and deleted_at is null")
                .param("actor", actor).param("instance", instanceId).update();
    }

    /** Makes the timer look at a step no more (the step ended). */
    void stopTimer(UUID stepId, UUID actor) {
        jdbc.sql("update approval_step_timer set next_timer_at = null, ended_at = coalesce(ended_at, now()), "
                        + "version = version + 1, updated_by = :actor "
                        + "where step_id = :step and ended_at is null and deleted_at is null")
                .param("actor", actor).param("step", stepId).update();
    }

    /**
     * Notes that the timer did something to a step, once. @return true when this call made the note, false when it was
     * made before (by this or another instance)
     */
    boolean insertReminder(UUID instanceId, UUID stepId, String kind, int sequence, UUID actor) {
        return jdbc.sql("insert into approval_reminder (instance_id, step_id, kind, sequence, created_by, "
                        + "updated_by) values (:instance, :step, :kind, :sequence, :actor, :actor) "
                        + "on conflict (tenant_id, step_id, kind, sequence) where deleted_at is null do nothing")
                .param("instance", instanceId).param("step", stepId).param("kind", kind)
                .param("sequence", sequence).param("actor", actor).update() == 1;
    }

    /** How many active steps of the organization are overdue now (the failure view, ADR-0163). */
    int overdueSteps() {
        return jdbc.sql("select count(*) from approval_step_timer where overdue_at is not null and ended_at is null "
                        + "and deleted_at is null")
                .query(Integer.class).single();
    }

    private static TimerRow timerRow(ResultSet rs, int row) throws SQLException {
        return new TimerRow(rs.getObject("id", UUID.class), rs.getObject("instance_id", UUID.class),
                rs.getObject("step_id", UUID.class), instant(rs, "next_timer_at"), rs.getInt("reminders_sent"),
                instant(rs, "escalated_at"), instant(rs, "overdue_at"), rs.getLong("version"));
    }

    // ---- tasks ----

    AssignmentRow insertAssignment(UUID instanceId, UUID stepId, UUID assignee, String via, UUID forUser,
            UUID actor) {
        UUID id = jdbc.sql("insert into approval_assignment (instance_id, step_id, assignee_id, via, for_user_id, "
                        + "created_by, updated_by) values (:instance, :step, :assignee, :via, :forUser, :actor, "
                        + ":actor) returning id")
                .param("instance", instanceId).param("step", stepId).param("assignee", assignee).param("via", via)
                .param("forUser", forUser, Types.OTHER).param("actor", actor).query(UUID.class).single();
        return assignment(id).orElseThrow();
    }

    Optional<AssignmentRow> assignment(UUID id) {
        return jdbc.sql("select " + ASSIGNMENT_COLUMNS + " from approval_assignment a where a.id = :id "
                        + "and a.deleted_at is null")
                .param("id", id).query(ApprovalStore::assignmentRow).optional();
    }

    List<AssignmentRow> assignmentsOf(UUID instanceId) {
        return jdbc.sql("select " + ASSIGNMENT_COLUMNS + " from approval_assignment a "
                        + "where a.instance_id = :instance and a.deleted_at is null order by a.created_at, a.id")
                .param("instance", instanceId).query(ApprovalStore::assignmentRow).list();
    }

    /** Sets the status of a task when it still has the version the caller read. */
    boolean updateAssignment(UUID id, long version, String status, UUID actor) {
        return jdbc.sql("update approval_assignment set status = :status, decided_at = "
                        + "case when :status = 'OPEN' then null else now() end, version = version + 1, "
                        + "updated_by = :actor where id = :id and version = :version and deleted_at is null")
                .param("status", status).param("actor", actor).param("id", id).param("version", version)
                .update() == 1;
    }

    // ---- history ----

    void insertHistory(UUID instanceId, Integer stepPosition, String kind, UUID actorId, UUID onBehalfOf,
            UUID targetId, String reason, String comment, int processVersionNumber, UUID actor) {
        jdbc.sql("insert into approval_history (instance_id, step_position, kind, actor_id, on_behalf_of, target_id, "
                        + "reason, comment, process_version_number, created_by, updated_by) values (:instance, "
                        + ":position, :kind, :actorId, :onBehalfOf, :target, :reason, :comment, :number, :actor, "
                        + ":actor)")
                .param("instance", instanceId).param("position", stepPosition, Types.INTEGER).param("kind", kind)
                .param("actorId", actorId, Types.OTHER).param("onBehalfOf", onBehalfOf, Types.OTHER)
                .param("target", targetId, Types.OTHER).param("reason", reason, Types.VARCHAR)
                .param("comment", comment, Types.VARCHAR).param("number", processVersionNumber)
                .param("actor", actor).update();
    }

    List<HistoryRow> history(UUID instanceId) {
        return jdbc.sql("select " + HISTORY_COLUMNS + " from approval_history h where h.instance_id = :instance "
                        + "order by h.created_at, h.id")
                .param("instance", instanceId).query(ApprovalStore::historyRow).list();
    }

    // ---- the inbox ----

    /**
     * A page of one person's tasks. {@code tab} is {@code TO_APPROVE} (open, not delegated), {@code DELEGATED} (open,
     * delegated to the person) or {@code DECIDED} (approved, rejected or handed on); open tasks are oldest first,
     * decided
     * ones newest first.
     */
    List<AssignmentRow> tasks(UUID person, String tab, String objectApiName, String processApiName, Instant after,
            UUID afterId, int limit) {
        boolean open = !"DECIDED".equals(tab);
        StringBuilder sql = new StringBuilder("select " + ASSIGNMENT_COLUMNS
                + " from approval_assignment a join approval_instance i on i.id = a.instance_id "
                + "where a.assignee_id = :person and a.deleted_at is null and i.deleted_at is null");
        List<Object[]> parameters = new ArrayList<>();
        if (open) {
            sql.append(" and a.status = 'OPEN'");
            sql.append("DELEGATED".equals(tab) ? " and a.via = 'DELEGATED'" : " and a.via <> 'DELEGATED'");
        } else {
            sql.append(" and a.status in ('APPROVED', 'REJECTED', 'REASSIGNED')");
        }
        if (objectApiName != null) {
            sql.append(" and i.object_api_name = :object");
            parameters.add(new Object[] {"object", objectApiName});
        }
        if (processApiName != null) {
            sql.append(" and i.process_api_name = :process");
            parameters.add(new Object[] {"process", processApiName});
        }
        String when = open ? "a.created_at" : "a.decided_at";
        if (after != null) {
            sql.append(" and (").append(when).append(", a.id) ").append(open ? ">" : "<")
                    .append(" (:after, :afterId)");
            parameters.add(new Object[] {"after", java.sql.Timestamp.from(after)});
            parameters.add(new Object[] {"afterId", afterId});
        }
        sql.append(" order by ").append(when).append(open ? " asc, a.id asc" : " desc, a.id desc")
                .append(" limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("person", person);
        for (Object[] parameter : parameters) {
            spec = spec.param((String) parameter[0], parameter[1]);
        }
        return spec.param("limit", limit).query(ApprovalStore::assignmentRow).list();
    }

    /** These requests, by identifier. */
    List<InstanceRow> instances(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + INSTANCE_COLUMNS + " from approval_instance i where i.id in (:ids) "
                        + "and i.deleted_at is null")
                .param("ids", List.copyOf(ids)).query(ApprovalStore::instanceRow).list();
    }

    /** These steps, by identifier. */
    List<StepRow> stepsById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + STEP_COLUMNS + " from approval_step_instance s where s.id in (:ids) "
                        + "and s.deleted_at is null")
                .param("ids", List.copyOf(ids)).query(ApprovalStore::stepRow).list();
    }

    /** How many open tasks the person has (the badge of the inbox). */
    int openTasks(UUID person) {
        return jdbc.sql("select count(*) from approval_assignment a join approval_instance i on i.id = a.instance_id "
                        + "where a.assignee_id = :person and a.status = 'OPEN' and a.deleted_at is null "
                        + "and i.deleted_at is null and i.status = 'PENDING'")
                .param("person", person).query(Integer.class).single();
    }

    /** Whether the person has ever been asked to decide, or submitted a request. */
    boolean involved(UUID person) {
        return jdbc.sql("select exists (select 1 from approval_assignment a where a.assignee_id = :person "
                        + "and a.deleted_at is null) or exists (select 1 from approval_instance i "
                        + "where i.submitted_by = :person and i.deleted_at is null)")
                .param("person", person).query(Boolean.class).single();
    }

    // ---- delegations ----

    DelegationRow insertDelegation(UUID delegator, UUID delegate, String processObject, String processApiName,
            LocalDate startsOn, LocalDate endsOn, String timeZone, UUID actor) {
        UUID id = jdbc.sql("insert into approval_delegation (delegator_id, delegate_id, process_object, "
                        + "process_api_name, starts_on, ends_on, time_zone, created_by, updated_by) values "
                        + "(:delegator, :delegate, :object, :process, :starts, :ends, :zone, :actor, :actor) "
                        + "returning id")
                .param("delegator", delegator).param("delegate", delegate)
                .param("object", processObject, Types.VARCHAR).param("process", processApiName, Types.VARCHAR)
                .param("starts", java.sql.Date.valueOf(startsOn)).param("ends", java.sql.Date.valueOf(endsOn))
                .param("zone", timeZone).param("actor", actor).query(UUID.class).single();
        return delegation(id).orElseThrow();
    }

    Optional<DelegationRow> delegation(UUID id) {
        return jdbc.sql("select d.id, d.delegator_id, d.delegate_id, d.process_object, d.process_api_name, "
                        + "d.starts_on, d.ends_on, d.time_zone, d.created_at, d.version from approval_delegation d "
                        + "where d.id = :id and d.deleted_at is null")
                .param("id", id).query(ApprovalStore::delegationRow).optional();
    }

    /** The delegations of one delegator (or of every delegator when null), newest range first. */
    List<DelegationRow> delegations(UUID delegator, int limit) {
        return jdbc.sql("select d.id, d.delegator_id, d.delegate_id, d.process_object, d.process_api_name, "
                        + "d.starts_on, d.ends_on, d.time_zone, d.created_at, d.version from approval_delegation d "
                        + "where d.deleted_at is null and (cast(:delegator as uuid) is null "
                        + "or d.delegator_id = cast(:delegator as uuid)) "
                        + "order by d.ends_on desc, d.id desc limit :limit")
                .param("delegator", delegator, Types.OTHER).param("limit", limit)
                .query(ApprovalStore::delegationRow).list();
    }

    /** The delegations of these people that could be in force around now (a day on either side of today). */
    List<DelegationRow> delegationsOf(Collection<UUID> delegators, LocalDate from, LocalDate to) {
        if (delegators.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select d.id, d.delegator_id, d.delegate_id, d.process_object, d.process_api_name, "
                        + "d.starts_on, d.ends_on, d.time_zone, d.created_at, d.version from approval_delegation d "
                        + "where d.deleted_at is null and d.delegator_id in (:delegators) "
                        + "and d.starts_on <= :to and d.ends_on >= :from order by d.created_at, d.id")
                .param("delegators", List.copyOf(delegators)).param("from", java.sql.Date.valueOf(from))
                .param("to", java.sql.Date.valueOf(to)).query(ApprovalStore::delegationRow).list();
    }

    /** How many delegations of the person are current or in the future (day by their own zone is the caller's). */
    int currentDelegations(UUID delegator, LocalDate today) {
        return jdbc.sql("select count(*) from approval_delegation d where d.delegator_id = :delegator "
                        + "and d.deleted_at is null and d.ends_on >= :today")
                .param("delegator", delegator).param("today", java.sql.Date.valueOf(today.minusDays(1)))
                .query(Integer.class).single();
    }

    boolean revokeDelegation(UUID id, long version, UUID actor) {
        return jdbc.sql("update approval_delegation set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where id = :id and version = :version "
                        + "and deleted_at is null")
                .param("actor", actor).param("id", id).param("version", version).update() == 1;
    }

    // ---- rows ----

    private static InstanceRow instanceRow(ResultSet rs, int row) throws SQLException {
        return new InstanceRow(rs.getObject("id", UUID.class), rs.getObject("process_id", UUID.class),
                rs.getObject("process_version_id", UUID.class), rs.getString("process_api_name"),
                rs.getString("process_label"), rs.getInt("process_version_number"), rs.getString("object_api_name"),
                rs.getObject("record_id", UUID.class), rs.getLong("record_version"),
                rs.getObject("submitted_by", UUID.class), rs.getString("status"),
                (Integer) rs.getObject("current_step"), rs.getString("attention"), rs.getString("outcome_failure"),
                rs.getObject("chain_root", UUID.class), rs.getInt("chain_depth"),
                uuids(rs.getString("chain_workflows")), instant(rs, "started_at"), instant(rs, "finished_at"),
                rs.getLong("version"));
    }

    private static StepRow stepRow(ResultSet rs, int row) throws SQLException {
        return new StepRow(rs.getObject("id", UUID.class), rs.getObject("instance_id", UUID.class),
                rs.getInt("position"), rs.getString("name"), rs.getString("label"), rs.getString("completion"),
                rs.getString("status"), instant(rs, "activated_at"), instant(rs, "finished_at"),
                rs.getLong("version"));
    }

    private static AssignmentRow assignmentRow(ResultSet rs, int row) throws SQLException {
        return new AssignmentRow(rs.getObject("id", UUID.class), rs.getObject("instance_id", UUID.class),
                rs.getObject("step_id", UUID.class), rs.getObject("assignee_id", UUID.class), rs.getString("via"),
                rs.getObject("for_user_id", UUID.class), rs.getString("status"), instant(rs, "decided_at"),
                instant(rs, "created_at"), rs.getLong("version"));
    }

    private static HistoryRow historyRow(ResultSet rs, int row) throws SQLException {
        return new HistoryRow(rs.getObject("id", UUID.class), rs.getObject("instance_id", UUID.class),
                (Integer) rs.getObject("step_position"), rs.getString("kind"), rs.getObject("actor_id", UUID.class),
                rs.getObject("on_behalf_of", UUID.class), rs.getObject("target_id", UUID.class),
                rs.getString("reason"), rs.getString("comment"), rs.getInt("process_version_number"),
                instant(rs, "created_at"));
    }

    private static DelegationRow delegationRow(ResultSet rs, int row) throws SQLException {
        return new DelegationRow(rs.getObject("id", UUID.class), rs.getObject("delegator_id", UUID.class),
                rs.getObject("delegate_id", UUID.class), rs.getString("process_object"),
                rs.getString("process_api_name"), rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class), rs.getString("time_zone"), instant(rs, "created_at"),
                rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<UUID> uuids(String text) {
        if (text == null || text.length() <= 2) {
            return List.of();
        }
        List<UUID> found = new ArrayList<>();
        for (String part : text.substring(1, text.length() - 1).split(",")) {
            found.add(UUID.fromString(part.replace("\"", "").strip()));
        }
        return found;
    }

    private static String textArray(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }
}
