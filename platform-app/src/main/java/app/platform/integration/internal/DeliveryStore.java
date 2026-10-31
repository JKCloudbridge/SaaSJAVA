package app.platform.integration.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
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
 * The only class that names {@code integration_delivery} (ADR-0220, ADR-0230): one row per send and per accepted
 * change, with identifiers, a stable reason, a status and times. Since Sprint 29 an outbound row is also a unit of
 * queued work: it waits (a due time), is claimed with a lease, is retried, waits for a remote job, and ends. It never
 * holds a value or a payload, so nothing here needs to guard a canary; the guard of the table holds the lifecycle.
 *
 * <p>Everything except {@link #claim} and the two counts runs in the organization's own tenant context. Those three run
 * in the system scope {@code integration_dispatch}, which the policies of this table admit to read and to claim
 * and which only {@link OutboundWorker} may enter.
 */
@Repository
class DeliveryStore {

    /** A delivery as stored. */
    record Delivery(UUID id, UUID connectionId, UUID mappingVersionId, String direction, String kind, UUID eventId,
            String object, UUID recordId, String state, String reason, Integer httpStatus, Instant createdAt,
            Instant finishedAt, int attempts, Instant runAt, int generation, String queuedReason,
            String lastStatusClass, Instant lastAttemptAt, String idempotencyKey, String remoteRef,
            Instant deadlineAt, long version) {

        @Override
        public String toString() {
            return "Delivery[" + id + "]";
        }
    }

    /**
     * A delivery a worker has claimed, in any organization.
     *
     * @param tenantId the organization it belongs to
     * @param version the version after the claim; every later change is conditional on it only where it matters
     */
    record Claimed(UUID id, UUID tenantId, UUID connectionId, UUID mappingVersionId, String kind, UUID eventId,
            String object, UUID recordId, String state, int attempts, int generation, String remoteRef,
            Instant deadlineAt, long version) {
    }

    private static final String COLUMNS = "id, connection_id, mapping_version_id, direction, kind, event_id, "
            + "object_api_name, record_id, state, reason, http_status, created_at, finished_at, attempts, run_at, "
            + "generation, queued_reason, last_status_class, last_attempt_at, idempotency_key, remote_ref, "
            + "deadline_at, version";
    private static final String OPEN = DeliveryState.openList();
    private static final UUID SYSTEM = ActorId.SYSTEM.value();

    /**
     * What makes a delivery due: queued or held by a worker whose lease ended, its time has come, and no earlier open
     * delivery of the same record is ahead of it (the order within one record, ADR-0230). {@code c} is the alias.
     */
    private static final String DUE = """
            c.direction = 'OUT' and c.state in (%s) and c.run_at <= now() and c.deleted_at is null
              and (c.locked_until is null or c.locked_until < now())
              and not exists (select 1 from integration_delivery b
                               where b.tenant_id = c.tenant_id and b.connection_id = c.connection_id
                                 and b.object_api_name = c.object_api_name and b.record_id = c.record_id
                                 and b.direction = 'OUT' and b.state in (%s) and b.deleted_at is null
                                 and (b.created_at, b.id) < (c.created_at, c.id))
            """.formatted(OPEN, OPEN);

    /**
     * Claims fairly (ADR-0164, ADR-0231): the organizations that have something due are found by a skip scan, each
     * contributes its oldest due deliveries up to the cap by turns, the first {@code limit} of all are locked with
     * {@code skip locked} and given a lease. Two instances that choose the same rows simply split them.
     */
    private static final String FAIR_CLAIM = """
            with recursive due_tenants(tenant_id) as (
                (select c.tenant_id from integration_delivery c where %1$s order by c.tenant_id limit 1)
                union all
                select (select c.tenant_id from integration_delivery c
                         where c.tenant_id > d.tenant_id and %1$s order by c.tenant_id limit 1)
                  from due_tenants d where d.tenant_id is not null
            ),
            candidates as (
                select r.id, r.turn, r.run_at
                  from due_tenants t
                 cross join lateral (
                       select x.id, x.run_at, row_number() over (order by x.run_at, x.id) as turn
                         from (select c.id, c.run_at from integration_delivery c
                                where c.tenant_id = t.tenant_id and %1$s
                                order by c.run_at, c.id limit :cap) x) r
                 where t.tenant_id is not null
                 order by r.turn, r.run_at, r.id
                 limit :limit
            ),
            picked as (
                select c.id from integration_delivery c
                 where c.id in (select id from candidates) and %1$s
                 order by c.run_at, c.id
                 for update skip locked
            )
            update integration_delivery e
               set locked_until = now() + make_interval(secs => cast(:lease as double precision)),
                   version = e.version + 1, updated_by = :system
              from picked
             where e.id = picked.id
            returning e.id, e.tenant_id, e.connection_id, e.mapping_version_id, e.kind, e.event_id,
                      e.object_api_name, e.record_id, e.state, e.attempts, e.generation, e.remote_ref, e.deadline_at,
                      e.version, e.run_at
            """.formatted(DUE);

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    DeliveryStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    // ---- starting work (tenant context) ----

    /**
     * Starts a delivery that has not ended. Empty when this event and connection already have one: the work of one
     * event is made once, whichever instance handles it (ADR-0220).
     */
    Optional<UUID> start(UUID connectionId, UUID versionId, String direction, String kind, UUID eventId,
            String object, UUID recordId, UUID actor) {
        return insert(connectionId, versionId, direction, kind, eventId, object, recordId, "PENDING", null, actor);
    }

    /** Records that nothing was done, and why, as a delivery of its own. */
    Optional<UUID> skipped(UUID connectionId, UUID versionId, String direction, String kind, UUID eventId,
            String object, UUID recordId, String reason, UUID actor) {
        return insert(connectionId, versionId, direction, kind, eventId, object, recordId, "SKIPPED", reason, actor);
    }

    /** Records a change the queue had no room for: visible, never dropped, and replayable (ADR-0230). */
    Optional<UUID> deadLettered(UUID connectionId, UUID versionId, String kind, UUID eventId, String object,
            UUID recordId, String reason, UUID actor) {
        return insert(connectionId, versionId, "OUT", kind, eventId, object, recordId, "DEAD_LETTERED", reason, actor);
    }

    private Optional<UUID> insert(UUID connectionId, UUID versionId, String direction, String kind, UUID eventId,
            String object, UUID recordId, String state, String reason, UUID actor) {
        boolean ended = !"PENDING".equals(state);
        return jdbc.sql("insert into integration_delivery (connection_id, mapping_version_id, direction, kind, "
                        + "event_id, object_api_name, record_id, state, reason, finished_at, created_by, updated_by) "
                        + "values (:connection, :version, :direction, :kind, :event, :object, :record, :state, "
                        + ":reason, case when :ended then now() end, :actor, :actor) "
                        + "on conflict (tenant_id, connection_id, direction, event_id) where deleted_at is null "
                        + "do nothing returning id")
                .param("connection", connectionId).param("version", versionId).param("direction", direction)
                .param("kind", kind).param("event", eventId).param("object", object).param("record", recordId)
                .param("state", state).param("reason", reason).param("ended", ended).param("actor", actor)
                .query(UUID.class).optional();
    }

    /** How many outbound deliveries of the connection are open, counted only up to {@code limit}. */
    int openCount(UUID connectionId, int limit) {
        return jdbc.sql("select count(*) from (select 1 from integration_delivery where tenant_id = :tenant "
                        + "and connection_id = :connection and direction = 'OUT' and state in (" + OPEN + ") "
                        + "and deleted_at is null limit :limit) x")
                .param("tenant", tenant()).param("connection", connectionId).param("limit", limit)
                .query(Integer.class).single();
    }

    /**
     * Latest state wins (ADR-0230): the changes of the same record that are still only waiting are replaced by the
     * newer one. A delivery that a worker holds is never touched. Returns how many were replaced.
     */
    int supersedeOlder(UUID connectionId, String object, UUID recordId, UUID newer) {
        return jdbc.sql("update integration_delivery set state = 'SUPERSEDED', reason = 'superseded', "
                        + "finished_at = now(), locked_until = null, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and connection_id = :connection and object_api_name = :object "
                        + "and record_id = :record and direction = 'OUT' and kind = 'UPDATE' and state = 'PENDING' "
                        + "and (locked_until is null or locked_until < now()) and id <> :newer "
                        + "and (created_at, id) < (select created_at, id from integration_delivery "
                        + "where id = :newer) and deleted_at is null")
                .param("system", SYSTEM).param("tenant", tenant()).param("connection", connectionId)
                .param("object", object).param("record", recordId).param("newer", newer).update();
    }

    // ---- the claim (system scope) ----

    /**
     * Claims up to {@code limit} due deliveries, a fair share for each organization. Must run in a transaction in the
     * system scope {@code integration_dispatch}.
     */
    List<Claimed> claim(int limit, int perOrganization, Duration lease) {
        return jdbc.sql(FAIR_CLAIM).param("lease", lease.toMillis() / 1000.0).param("system", SYSTEM)
                .param("cap", perOrganization).param("limit", limit)
                .query((rs, row) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("connection_id", UUID.class), rs.getObject("mapping_version_id", UUID.class),
                        rs.getString("kind"), rs.getObject("event_id", UUID.class), rs.getString("object_api_name"),
                        rs.getObject("record_id", UUID.class), rs.getString("state"), rs.getInt("attempts"),
                        rs.getInt("generation"), rs.getString("remote_ref"), instant(rs, "deadline_at"),
                        rs.getLong("version")))
                .list().stream().sorted(java.util.Comparator.comparing(Claimed::id)).toList();
    }

    /** How many outbound deliveries are open across organizations (a gauge; never per organization). Scope as above. */
    long openAcrossOrganizations() {
        return jdbc.sql("select count(*) from integration_delivery where direction = 'OUT' and state in (" + OPEN
                + ") and deleted_at is null").query(Long.class).single();
    }

    /** How many dead letters exist across organizations. Scope as above. */
    long deadLettersAcrossOrganizations() {
        return jdbc.sql("select count(*) from integration_delivery where direction = 'OUT' "
                + "and state = 'DEAD_LETTERED' and deleted_at is null").query(Long.class).single();
    }

    /** How long the oldest due delivery has waited, in seconds, across organizations. Scope as above. */
    long oldestDueAgeSeconds() {
        return jdbc.sql("select coalesce(max(extract(epoch from (now() - run_at))::bigint), 0) "
                + "from integration_delivery where direction = 'OUT' and state in (" + OPEN + ") "
                + "and run_at <= now() and deleted_at is null").query(Long.class).single();
    }

    // ---- moving a delivery (tenant context) ----

    /**
     * Takes the right to make an attempt: the delivery becomes {@code SENDING}, its attempts count one more and the
     * lease is renewed. Returns the delivery as it is then, or empty when it is no longer there to send (another
     * instance ended it).
     */
    Optional<Delivery> admit(UUID id, Duration lease) {
        return jdbc.sql("update integration_delivery set state = 'SENDING', attempts = attempts + 1, "
                        + "locked_until = now() + make_interval(secs => cast(:lease as double precision)), "
                        + "idempotency_key = id::text || ':' || generation, last_attempt_at = now(), "
                        + "queued_reason = null, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id and state in ('PENDING', 'SENDING') "
                        + "and deleted_at is null returning " + COLUMNS)
                .param("lease", lease.toMillis() / 1000.0).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).query((rs, row) -> delivery(rs)).optional();
    }

    /** Puts the delivery back in the queue to be looked at again at {@code runAt}, for this stable reason. */
    boolean defer(UUID id, Instant runAt, String reason) {
        return jdbc.sql("update integration_delivery set state = case when state = 'WAITING_REMOTE' then state "
                        + "else 'PENDING' end, run_at = :runAt, locked_until = null, queued_reason = :reason, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id "
                        + "and state in (" + OPEN + ") and deleted_at is null")
                .param("runAt", ts(runAt)).param("reason", reason).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).update() == 1;
    }

    /**
     * Defers every delivery of the connection that is due and not held, in one statement, so a paused connection costs
     * one write and not one per delivery. Bounded by {@code limit} per call.
     */
    int deferConnection(UUID connectionId, Instant runAt, String reason, int limit) {
        return jdbc.sql("update integration_delivery set run_at = :runAt, queued_reason = :reason, "
                        + "version = version + 1, updated_by = :system where id in (select id from "
                        + "integration_delivery where tenant_id = :tenant and connection_id = :connection "
                        + "and direction = 'OUT' and state = 'PENDING' and run_at <= now() and deleted_at is null "
                        + "and (locked_until is null or locked_until < now()) limit :limit)")
                .param("runAt", ts(runAt)).param("reason", reason).param("system", SYSTEM).param("tenant", tenant())
                .param("connection", connectionId).param("limit", limit).update();
    }

    /** After a failed attempt that may be tried again: waits until {@code runAt}. */
    boolean retryLater(UUID id, Instant runAt, String reason, String statusClass, Integer httpStatus) {
        return jdbc.sql("update integration_delivery set state = 'PENDING', run_at = :runAt, locked_until = null, "
                        + "reason = :reason, queued_reason = 'backoff', last_status_class = :class, "
                        + "http_status = :status, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id and state in ('PENDING', 'SENDING') "
                        + "and deleted_at is null")
                .param("runAt", ts(runAt)).param("reason", reason).param("class", statusClass)
                .param("status", httpStatus).param("system", SYSTEM).param("tenant", tenant()).param("id", id)
                .update() == 1;
    }

    /** Ends the delivery in a state that is not open. False when it had ended already. */
    boolean end(UUID id, String state, String reason, String statusClass, Integer httpStatus) {
        return jdbc.sql("update integration_delivery set state = :state, reason = :reason, "
                        + "last_status_class = coalesce(:class, last_status_class), http_status = :status, "
                        + "queued_reason = null, locked_until = null, finished_at = now(), version = version + 1, "
                        + "updated_by = :system where tenant_id = :tenant and id = :id and state in (" + OPEN + ") "
                        + "and deleted_at is null")
                .param("state", state).param("reason", reason).param("class", statusClass).param("status", httpStatus)
                .param("system", SYSTEM).param("tenant", tenant()).param("id", id).update() == 1;
    }

    /** The other side accepted the work and will say when it is done: wait for it, and ask again at {@code runAt}. */
    boolean waitForRemote(UUID id, String remoteRef, Instant deadline, Instant runAt, String statusClass,
            Integer httpStatus) {
        return jdbc.sql("update integration_delivery set state = 'WAITING_REMOTE', remote_ref = :ref, "
                        + "deadline_at = coalesce(deadline_at, :deadline), run_at = :runAt, locked_until = null, "
                        + "queued_reason = 'waiting_remote', last_status_class = :class, http_status = :status, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id "
                        + "and state in ('PENDING', 'SENDING', 'WAITING_REMOTE') and deleted_at is null")
                .param("ref", remoteRef).param("deadline", ts(deadline)).param("runAt", ts(runAt))
                .param("class", statusClass).param("status", httpStatus).param("system", SYSTEM)
                .param("tenant", tenant()).param("id", id).update() == 1;
    }

    /**
     * Sends a dead letter again (ADR-0231): the same delivery, a new generation, the attempts counted afresh. Two
     * clicks make one: the second finds it no longer dead.
     */
    boolean replay(UUID id) {
        return jdbc.sql("update integration_delivery set state = 'PENDING', generation = generation + 1, "
                        + "attempts = 0, run_at = now(), locked_until = null, finished_at = null, "
                        + "queued_reason = 'replayed', remote_ref = null, deadline_at = null, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id "
                        + "and state = 'DEAD_LETTERED' and direction = 'OUT' and deleted_at is null")
                .param("system", SYSTEM).param("tenant", tenant()).param("id", id).update() == 1;
    }

    /** Ends a pending receive (the receiver's own use): false when it had ended already. */
    boolean finish(UUID id, String state, String reason, Integer httpStatus, UUID actor) {
        return jdbc.sql("update integration_delivery set state = :state, reason = :reason, http_status = :status, "
                        + "finished_at = now(), version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and state = 'PENDING' and deleted_at is null")
                .param("state", state).param("reason", reason).param("status", httpStatus).param("actor", actor)
                .param("tenant", tenant()).param("id", id).update() == 1;
    }

    // ---- reading (tenant context) ----

    Optional<Delivery> find(UUID connectionId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration_delivery where tenant_id = :tenant "
                        + "and connection_id = :connection and id = :id and deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).param("id", id)
                .query((rs, row) -> delivery(rs)).optional();
    }

    Optional<Delivery> findAny(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration_delivery where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null").param("tenant", tenant()).param("id", id)
                .query((rs, row) -> delivery(rs)).optional();
    }

    List<Delivery> recent(UUID connectionId, int limit) {
        return recent(connectionId, List.of(), limit);
    }

    /** The newest deliveries of the connection, optionally only those in these states. */
    List<Delivery> recent(UUID connectionId, List<String> states, int limit) {
        String filter = states.isEmpty() ? "" : "and state in (:states) ";
        var statement = jdbc.sql("select " + COLUMNS + " from integration_delivery where tenant_id = :tenant "
                        + "and connection_id = :connection and deleted_at is null " + filter
                        + "order by created_at desc, id desc limit :limit")
                .param("tenant", tenant()).param("connection", connectionId).param("limit", limit);
        if (!states.isEmpty()) {
            statement = statement.param("states", List.copyOf(states));
        }
        return statement.query((rs, row) -> delivery(rs)).list();
    }

    /** The ids of the dead letters of the connection, oldest first, up to {@code limit}. */
    List<UUID> deadLetterIds(UUID connectionId, int limit) {
        return jdbc.sql("select id from integration_delivery where tenant_id = :tenant "
                        + "and connection_id = :connection and direction = 'OUT' and state = 'DEAD_LETTERED' "
                        + "and deleted_at is null order by created_at, id limit :limit")
                .param("tenant", tenant()).param("connection", connectionId).param("limit", limit)
                .query(UUID.class).list();
    }

    /** What the queue of one connection holds, by state. */
    record Counts(long pending, long sending, long waitingRemote, long deadLettered, long done, long skipped,
            long superseded, long failed, Instant oldestOpen, Instant lastDone, Instant lastDeadLettered) {
    }

    Counts counts(UUID connectionId) {
        return jdbc.sql("select count(*) filter (where state = 'PENDING') as pending, "
                        + "count(*) filter (where state = 'SENDING') as sending, "
                        + "count(*) filter (where state = 'WAITING_REMOTE') as waiting, "
                        + "count(*) filter (where state = 'DEAD_LETTERED') as dead, "
                        + "count(*) filter (where state = 'DONE') as done, "
                        + "count(*) filter (where state = 'SKIPPED') as skipped, "
                        + "count(*) filter (where state = 'SUPERSEDED') as superseded, "
                        + "count(*) filter (where state = 'FAILED') as failed, "
                        + "min(run_at) filter (where state in (" + OPEN + ")) as oldest_open, "
                        + "max(finished_at) filter (where state = 'DONE') as last_done, "
                        + "max(finished_at) filter (where state = 'DEAD_LETTERED') as last_dead "
                        + "from integration_delivery where tenant_id = :tenant and connection_id = :connection "
                        + "and direction = 'OUT' and deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId)
                .query((rs, row) -> new Counts(rs.getLong("pending"), rs.getLong("sending"), rs.getLong("waiting"),
                        rs.getLong("dead"), rs.getLong("done"), rs.getLong("skipped"), rs.getLong("superseded"),
                        rs.getLong("failed"), instant(rs, "oldest_open"), instant(rs, "last_done"),
                        instant(rs, "last_dead")))
                .single();
    }

    /** How many deliveries of the connection are held by a worker whose lease has not ended. */
    int inFlight(UUID connectionId, UUID except) {
        return jdbc.sql("select count(*) from integration_delivery where tenant_id = :tenant "
                        + "and connection_id = :connection and direction = 'OUT' and state = 'SENDING' "
                        + "and locked_until > now() and id <> :except and deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).param("except", except)
                .query(Integer.class).single();
    }

    /** Removes what is older than the retention, a bounded batch at a time, so the table never grows without end. */
    int purge(UUID connectionId, Duration retention, int batch) {
        return jdbc.sql("delete from integration_delivery where id in (select id from integration_delivery "
                        + "where tenant_id = :tenant and connection_id = :connection and state not in (" + OPEN + ") "
                        + "and created_at < now() - make_interval(secs => :secs) limit :batch)")
                .param("tenant", tenant()).param("connection", connectionId)
                .param("secs", (double) retention.toMillis() / 1000.0).param("batch", batch).update();
    }

    private Delivery delivery(ResultSet rs) throws SQLException {
        int status = rs.getInt("http_status");
        Integer httpStatus = rs.wasNull() ? null : status;
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getObject("mapping_version_id", UUID.class), rs.getString("direction"), rs.getString("kind"),
                rs.getObject("event_id", UUID.class), rs.getString("object_api_name"),
                rs.getObject("record_id", UUID.class), rs.getString("state"), rs.getString("reason"), httpStatus,
                rs.getTimestamp("created_at").toInstant(), instant(rs, "finished_at"), rs.getInt("attempts"),
                rs.getTimestamp("run_at").toInstant(), rs.getInt("generation"), rs.getString("queued_reason"),
                rs.getString("last_status_class"), instant(rs, "last_attempt_at"), rs.getString("idempotency_key"),
                rs.getString("remote_ref"), instant(rs, "deadline_at"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
