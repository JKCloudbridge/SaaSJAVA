package app.platform.workflow.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The dispatcher's view of the executions and schedules of every organization (ADR-0135). Every method must run inside
 * a transaction in the {@code WORKFLOW_DISPATCH} system scope: that is the only scope the policies of these two tables
 * admit across organizations (ADR-0015), and this class and {@link Dispatcher} are the only ones that may enter it (an
 * architecture test). It claims and counts; it never reads a record, a definition or a value, and the work of a claimed
 * row always runs in that row's own organization.
 *
 * <p><strong>Claiming</strong> is one statement that picks due rows with {@code FOR UPDATE SKIP LOCKED}, so several
 * instances polling at once never wait for each other and never pick the same row, and gives each picked row a lease
 * (it is invisible to other pollers until the lease ends, so a crashed instance's rows come back by themselves). The
 * version after the claim is the claim's token: every later change of the row succeeds only while the version is the
 * one the claimer last saw, so an instance that was too slow and lost its lease cannot overwrite the outcome of the one
 * that took over (the idiom of the outbox, ADR-0016).
 */
@Repository
class DispatchStore {

    /**
     * An execution this instance has claimed.
     *
     * @param attempt how many times it has been claimed, this claim included (a claim, not an attempt of a step)
     * @param chainRoot the first cause of the chain, or null when the execution is its own root
     * @param chainDepth how many executions lead to this one
     * @param chainWorkflows the workflows already in the chain
     * @param replayOf the execution this one replays, or null
     * @param replayGeneration 0 unless it replays another
     * @param resumeStep the first step to carry out
     */
    record ClaimedExecution(UUID id, UUID tenantId, UUID workflowId, UUID versionId, String objectApiName,
            UUID recordId, Long recordVersion, String trigger, int attempt, long version, Instant queuedAt,
            UUID chainRoot, int chainDepth, List<UUID> chainWorkflows, UUID replayOf, int replayGeneration,
            int resumeStep) {
    }

    /** A schedule this instance has claimed. */
    record ClaimedSchedule(UUID id, UUID tenantId, UUID workflowId, String trigger, int definitionVersion,
            Instant nextFireAt, UUID firingAfter, Instant firingFor, Instant coveredThrough, int attempts,
            long version) {
    }

    private final JdbcClient jdbc;

    DispatchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims up to {@code limit} due executions, oldest first. */
    List<ClaimedExecution> claimExecutions(int limit, Duration lease) {
        return jdbc.sql("""
                update workflow_execution e
                   set status = 'RUNNING', started_at = coalesce(e.started_at, now()),
                       locked_until = now() + make_interval(secs => cast(:lease as double precision)),
                       attempts = e.attempts + 1, version = e.version + 1, updated_by = :system
                 where e.id in (select d.id from workflow_execution d
                                 where d.status in ('QUEUED', 'RUNNING') and d.run_at <= now()
                                   and (d.locked_until is null or d.locked_until < now())
                                   and d.deleted_at is null
                                 order by d.run_at, d.id
                                 limit :limit
                                 for update skip locked)
                returning e.id, e.tenant_id, e.workflow_id, e.workflow_version_id, e.object_api_name, e.record_id,
                          e.record_version, e.trigger_kind, e.attempts, e.version, e.created_at, e.chain_root,
                          e.chain_depth, array_to_string(e.chain_workflows, ',') as chain_workflows, e.replay_of,
                          e.replay_generation, e.resume_step
                """)
                .param("lease", lease.toMillis() / 1000.0).param("system", ActorId.SYSTEM.value())
                .param("limit", limit).query(DispatchStore::execution).list().stream()
                // The statement does not promise an order; handle in the order the rows became due (ADR-0016's idiom).
                .sorted(Comparator.comparing(ClaimedExecution::queuedAt).thenComparing(ClaimedExecution::id))
                .toList();
    }

    /** Claims up to {@code limit} due schedules, the one due longest ago first. */
    List<ClaimedSchedule> claimSchedules(int limit, Duration lease) {
        return jdbc.sql("""
                update workflow_schedule s
                   set locked_until = now() + make_interval(secs => cast(:lease as double precision)),
                       version = s.version + 1, updated_by = :system
                 where s.id in (select d.id from workflow_schedule d
                                 where d.state in ('WAITING', 'FIRING') and d.next_fire_at <= now()
                                   and (d.locked_until is null or d.locked_until < now())
                                   and d.deleted_at is null
                                 order by d.next_fire_at, d.id
                                 limit :limit
                                 for update skip locked)
                returning s.id, s.tenant_id, s.workflow_id, s.trigger_kind, s.definition_version, s.next_fire_at,
                          s.firing_after, s.firing_for, s.covered_through, s.attempts, s.version
                """)
                .param("lease", lease.toMillis() / 1000.0).param("system", ActorId.SYSTEM.value())
                .param("limit", limit).query(DispatchStore::schedule).list().stream()
                .sorted(Comparator.comparing(ClaimedSchedule::nextFireAt).thenComparing(ClaimedSchedule::id))
                .toList();
    }

    /** How many executions wait to be claimed now (for the queue depth gauge). */
    long queued() {
        return jdbc.sql("select count(*) from workflow_execution where status = 'QUEUED' and run_at <= now() "
                        + "and deleted_at is null")
                .query(Long.class).single();
    }

    /** How many executions wait for a person across organizations (the gauge of dead letters, ADR-0143). */
    long deadLetters() {
        return jdbc.sql("select count(*) from workflow_execution where status = 'DEAD_LETTERED' "
                        + "and deleted_at is null")
                .query(Long.class).single();
    }

    /**
     * The organizations that have finished executions older than the retention, or dead letters older than theirs, at
     * most {@code limit} of them.
     */
    List<UUID> organizationsWithExpired(Duration retention, Duration deadLetterRetention, int limit) {
        return jdbc.sql("select distinct tenant_id from workflow_execution where "
                        + "(status <> 'DEAD_LETTERED' and finished_at < now() - make_interval(secs => "
                        + "cast(:seconds as double precision))) "
                        + "or (status = 'DEAD_LETTERED' and finished_at < now() - make_interval(secs => "
                        + "cast(:deadSeconds as double precision))) limit :limit")
                .param("seconds", retention.toMillis() / 1000.0)
                .param("deadSeconds", deadLetterRetention.toMillis() / 1000.0).param("limit", limit)
                .query(UUID.class).list();
    }

    private static ClaimedExecution execution(ResultSet rs, int row) throws SQLException {
        long recordVersion = rs.getLong("record_version");
        Long version = rs.wasNull() ? null : recordVersion;
        return new ClaimedExecution(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("workflow_id", UUID.class), rs.getObject("workflow_version_id", UUID.class),
                rs.getString("object_api_name"), rs.getObject("record_id", UUID.class), version,
                rs.getString("trigger_kind"), rs.getInt("attempts"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("chain_root", UUID.class),
                rs.getInt("chain_depth"), workflows(rs.getString("chain_workflows")),
                rs.getObject("replay_of", UUID.class), rs.getInt("replay_generation"), rs.getInt("resume_step"));
    }

    private static List<UUID> workflows(String joined) {
        return joined == null || joined.isEmpty() ? List.of()
                : java.util.Arrays.stream(joined.split(",")).map(UUID::fromString).toList();
    }

    private static ClaimedSchedule schedule(ResultSet rs, int row) throws SQLException {
        Timestamp firingFor = rs.getTimestamp("firing_for");
        Timestamp covered = rs.getTimestamp("covered_through");
        return new ClaimedSchedule(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("workflow_id", UUID.class), rs.getString("trigger_kind"),
                rs.getInt("definition_version"), rs.getTimestamp("next_fire_at").toInstant(),
                rs.getObject("firing_after", UUID.class), firingFor == null ? null : firingFor.toInstant(),
                covered == null ? null : covered.toInstant(), rs.getInt("attempts"), rs.getLong("version"));
    }
}
