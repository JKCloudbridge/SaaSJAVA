package app.platform.integration.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code webhook_delivery} (Sprint 32, ADR-0278, ADR-0279): one row for each event that a
 * subscription must be told about, and a unit of queued work. It waits (a due time), is claimed with a lease by a
 * worker
 * on any instance, is retried with a wait kept in the row, and ends. It holds identifiers, a stable reason, a class of
 * status and times, never a payload body, a header or an answer, so nothing here needs to guard a canary; the guard of
 * the table holds the lifecycle.
 *
 * <p>Everything except {@link #claim} and the counts across organizations runs in the organization's own tenant
 * context. Those run in the system scope {@code webhook_dispatch}, which the policies of this table admit to read and
 * to
 * claim and which only {@link WebhookWorker} may enter.
 */
@Repository
class WebhookDeliveryStore {

    /** A delivery as stored. */
    record Delivery(UUID id, UUID subscriptionId, UUID eventId, String eventType, String object, UUID recordId,
            Long recordVersion, String state, String reason, String queuedReason, String lastStatusClass,
            Integer httpStatus, int attempts, int generation, Instant runAt, Instant lastAttemptAt,
            Instant createdAt, Instant finishedAt, long version) {

        @Override
        public String toString() {
            return "Delivery[" + id + "]";
        }
    }

    /** A delivery a worker has claimed, in any organization. */
    record Claimed(UUID id, UUID tenantId, UUID subscriptionId, String state, int attempts, int generation) {
    }

    private static final String COLUMNS = "id, subscription_id, event_id, event_type, object_api_name, record_id, "
            + "record_version, state, reason, queued_reason, last_status_class, http_status, attempts, generation, "
            + "run_at, last_attempt_at, created_at, finished_at, version";
    private static final String OPEN = "'PENDING', 'SENDING'";
    private static final UUID SYSTEM = ActorId.SYSTEM.value();

    /** What makes a delivery due: queued or held by a worker whose lease ended, and its time has come. */
    private static final String DUE = "c.state in (" + OPEN + ") and c.run_at <= now() and c.deleted_at is null "
            + "and (c.locked_until is null or c.locked_until < now())";

    /**
     * Claims fairly (ADR-0164, ADR-0231, ADR-0279): the organizations that have something due are found by a skip scan,
     * each contributes its oldest due deliveries up to the cap by turns, the first {@code limit} of all are locked with
     * {@code skip locked} and given a lease. Two instances that choose the same rows simply split them, and one
     * organization with thousands of events cannot fill a whole claim.
     */
    private static final String FAIR_CLAIM = """
            with recursive due_tenants(tenant_id) as (
                (select c.tenant_id from webhook_delivery c where %1$s order by c.tenant_id limit 1)
                union all
                select (select c.tenant_id from webhook_delivery c
                         where c.tenant_id > d.tenant_id and %1$s order by c.tenant_id limit 1)
                  from due_tenants d where d.tenant_id is not null
            ),
            candidates as (
                select r.id, r.turn, r.run_at
                  from due_tenants t
                 cross join lateral (
                       select x.id, x.run_at, row_number() over (order by x.run_at, x.id) as turn
                         from (select c.id, c.run_at from webhook_delivery c
                                where c.tenant_id = t.tenant_id and %1$s
                                order by c.run_at, c.id limit :cap) x) r
                 where t.tenant_id is not null
                 order by r.turn, r.run_at, r.id
                 limit :limit
            ),
            picked as (
                select c.id from webhook_delivery c
                 where c.id in (select id from candidates) and %1$s
                 order by c.run_at, c.id
                 for update skip locked
            )
            update webhook_delivery e
               set locked_until = now() + make_interval(secs => cast(:lease as double precision)),
                   version = e.version + 1, updated_by = :system
              from picked
             where e.id = picked.id
            returning e.id, e.tenant_id, e.subscription_id, e.state, e.attempts, e.generation, e.run_at
            """.replace("%1$s", DUE);

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    WebhookDeliveryStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    // ---- starting work (tenant context) ----

    /**
     * Starts a delivery that has not ended. Empty when this event and subscription already have one: the work of one
     * event is made once, whichever instance handles it, however often the outbox hands the event over.
     */
    Optional<UUID> start(UUID subscriptionId, UUID eventId, String eventType, String object, UUID recordId,
            Long recordVersion) {
        return insert(subscriptionId, eventId, eventType, object, recordId, recordVersion, "PENDING", null);
    }

    /** Records that nothing was sent, and why, as a delivery of its own. */
    Optional<UUID> skipped(UUID subscriptionId, UUID eventId, String eventType, String object, UUID recordId,
            Long recordVersion, String reason) {
        return insert(subscriptionId, eventId, eventType, object, recordId, recordVersion, "SKIPPED", reason);
    }

    /** Records an event the queue had no room for: visible, never dropped, and replayable (ADR-0279). */
    Optional<UUID> deadLettered(UUID subscriptionId, UUID eventId, String eventType, String object, UUID recordId,
            Long recordVersion, String reason) {
        return insert(subscriptionId, eventId, eventType, object, recordId, recordVersion, "DEAD_LETTERED", reason);
    }

    private Optional<UUID> insert(UUID subscriptionId, UUID eventId, String eventType, String object, UUID recordId,
            Long recordVersion, String state, String reason) {
        boolean ended = !"PENDING".equals(state);
        return jdbc.sql("insert into webhook_delivery (subscription_id, event_id, event_type, object_api_name, "
                        + "record_id, record_version, state, reason, finished_at, created_by, updated_by) "
                        + "values (:subscription, :event, :type, :object, :record, :recordVersion, :state, :reason, "
                        + "case when :ended then now() end, :system, :system) "
                        + "on conflict (tenant_id, subscription_id, event_id) where deleted_at is null "
                        + "do nothing returning id")
                .param("subscription", subscriptionId).param("event", eventId).param("type", eventType)
                .param("object", object).param("record", recordId).param("recordVersion", recordVersion, Types.BIGINT)
                .param("state", state).param("reason", reason, Types.VARCHAR).param("ended", ended)
                .param("system", SYSTEM).query(UUID.class).optional();
    }

    /** How many deliveries of the subscription are open, counted only up to {@code limit}. */
    int openCount(UUID subscriptionId, int limit) {
        return jdbc.sql("select count(*) from (select 1 from webhook_delivery where tenant_id = :tenant "
                        + "and subscription_id = :subscription and state in (" + OPEN + ") and deleted_at is null "
                        + "limit :limit) x")
                .param("tenant", tenant()).param("subscription", subscriptionId).param("limit", limit)
                .query(Integer.class).single();
    }

    /**
     * Latest state wins for changes: the updates of the same record that are still only waiting are replaced by the
     * newer one, because a delivery names a record and never a value, so an older waiting one says nothing the newer
     * does not. A delivery that a worker holds is never touched. Returns how many were replaced.
     */
    int supersedeOlder(UUID subscriptionId, String object, UUID recordId, UUID newer) {
        return jdbc.sql("update webhook_delivery set state = 'SUPERSEDED', reason = 'superseded', "
                        + "finished_at = now(), locked_until = null, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and subscription_id = :subscription "
                        + "and object_api_name = :object and record_id = :record and event_type = 'record.updated' "
                        + "and state = 'PENDING' and (locked_until is null or locked_until < now()) "
                        + "and id <> :newer and id < :newer and deleted_at is null")
                .param("system", SYSTEM).param("tenant", tenant()).param("subscription", subscriptionId)
                .param("object", object).param("record", recordId).param("newer", newer).update();
    }

    // ---- the claim (system scope) ----

    /**
     * Claims up to {@code limit} due deliveries, a fair share for each organization. Must run in a transaction in the
     * system scope {@code webhook_dispatch}.
     */
    List<Claimed> claim(int limit, int perOrganization, Duration lease) {
        return jdbc.sql(FAIR_CLAIM).param("lease", lease.toMillis() / 1000.0).param("system", SYSTEM)
                .param("cap", perOrganization).param("limit", limit)
                .query((rs, row) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("subscription_id", UUID.class), rs.getString("state"), rs.getInt("attempts"),
                        rs.getInt("generation")))
                .list().stream().sorted(java.util.Comparator.comparing(Claimed::id)).toList();
    }

    /** How many deliveries are open across organizations (a gauge; never per organization). Scope as above. */
    long openAcrossOrganizations() {
        return jdbc.sql("select count(*) from webhook_delivery where state in (" + OPEN + ") and deleted_at is null")
                .query(Long.class).single();
    }

    /** How many dead letters exist across organizations. Scope as above. */
    long deadLettersAcrossOrganizations() {
        return jdbc.sql("select count(*) from webhook_delivery where state = 'DEAD_LETTERED' "
                + "and deleted_at is null").query(Long.class).single();
    }

    /** How long the oldest due delivery has waited, in seconds, across organizations. Scope as above. */
    long oldestDueAgeSeconds() {
        return jdbc.sql("select coalesce(max(extract(epoch from (now() - run_at))::bigint), 0) "
                + "from webhook_delivery where state in (" + OPEN + ") and run_at <= now() and deleted_at is null")
                .query(Long.class).single();
    }

    // ---- moving a delivery (tenant context) ----

    /**
     * Takes the right to make an attempt: the delivery becomes {@code SENDING}, its attempts count one more and the
     * lease is renewed. Empty when it is no longer there to send (another instance ended it).
     */
    Optional<Delivery> admit(UUID id, Duration lease) {
        return jdbc.sql("update webhook_delivery set state = 'SENDING', attempts = attempts + 1, "
                        + "locked_until = now() + make_interval(secs => cast(:lease as double precision)), "
                        + "last_attempt_at = now(), queued_reason = null, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id and state in ('PENDING', 'SENDING') "
                        + "and deleted_at is null returning " + COLUMNS)
                .param("lease", lease.toMillis() / 1000.0).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).query((rs, row) -> delivery(rs)).optional();
    }

    /** Puts the delivery back in the queue to be looked at again at {@code runAt}, for this stable reason. */
    boolean defer(UUID id, Instant runAt, String reason) {
        return jdbc.sql("update webhook_delivery set state = 'PENDING', run_at = :runAt, locked_until = null, "
                        + "queued_reason = :reason, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id and state in (" + OPEN + ") and deleted_at is null")
                .param("runAt", ts(runAt)).param("reason", reason).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).update() == 1;
    }

    /**
     * Defers every delivery of the subscription that is due and not held, in one statement, so a paused subscription
     * costs one write and not one per delivery. Bounded by {@code limit} per call.
     */
    int deferSubscription(UUID subscriptionId, Instant runAt, String reason, int limit) {
        return jdbc.sql("update webhook_delivery set run_at = :runAt, queued_reason = :reason, "
                        + "version = version + 1, updated_by = :system where id in (select id from "
                        + "webhook_delivery where tenant_id = :tenant and subscription_id = :subscription "
                        + "and state = 'PENDING' and run_at <= now() and deleted_at is null "
                        + "and (locked_until is null or locked_until < now()) limit :limit)")
                .param("runAt", ts(runAt)).param("reason", reason).param("system", SYSTEM).param("tenant", tenant())
                .param("subscription", subscriptionId).param("limit", limit).update();
    }

    /** After a failed attempt that may be tried again: waits until {@code runAt}. */
    boolean retryLater(UUID id, Instant runAt, String reason, String statusClass, Integer httpStatus) {
        return jdbc.sql("update webhook_delivery set state = 'PENDING', run_at = :runAt, locked_until = null, "
                        + "reason = :reason, queued_reason = 'backoff', last_status_class = :class, "
                        + "http_status = :status, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id and state in (" + OPEN + ") and deleted_at is null")
                .param("runAt", ts(runAt)).param("reason", reason).param("class", statusClass)
                .param("status", httpStatus, Types.INTEGER).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).update() == 1;
    }

    /** Ends the delivery in a state that is not open. False when it had ended already. */
    boolean end(UUID id, String state, String reason, String statusClass, Integer httpStatus) {
        return jdbc.sql("update webhook_delivery set state = :state, reason = :reason, "
                        + "last_status_class = coalesce(:class, last_status_class), http_status = :status, "
                        + "queued_reason = null, locked_until = null, finished_at = now(), version = version + 1, "
                        + "updated_by = :system where tenant_id = :tenant and id = :id and state in (" + OPEN + ") "
                        + "and deleted_at is null")
                .param("state", state).param("reason", reason, Types.VARCHAR).param("class", statusClass,
                        Types.VARCHAR)
                .param("status", httpStatus, Types.INTEGER).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).update() == 1;
    }

    /**
     * Ends every open delivery of the subscription as skipped, for this reason (a subscription that was removed).
     * Bounded by {@code limit}; the caller repeats until it returns less.
     */
    int endOpenOf(UUID subscriptionId, String reason, int limit) {
        return jdbc.sql("update webhook_delivery set state = 'SKIPPED', reason = :reason, queued_reason = null, "
                        + "locked_until = null, finished_at = now(), version = version + 1, updated_by = :system "
                        + "where id in (select id from webhook_delivery where tenant_id = :tenant "
                        + "and subscription_id = :subscription and state in (" + OPEN + ") and deleted_at is null "
                        + "limit :limit)")
                .param("reason", reason).param("system", SYSTEM).param("tenant", tenant())
                .param("subscription", subscriptionId).param("limit", limit).update();
    }

    /**
     * Sends a dead letter again: the same delivery, a new generation, the attempts counted afresh. Two clicks make one:
     * the second finds it no longer dead.
     */
    boolean replay(UUID id) {
        return jdbc.sql("update webhook_delivery set state = 'PENDING', generation = generation + 1, "
                        + "attempts = 0, run_at = now(), locked_until = null, finished_at = null, "
                        + "queued_reason = 'replayed', version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id and state = 'DEAD_LETTERED' and deleted_at is null")
                .param("system", SYSTEM).param("tenant", tenant()).param("id", id).update() == 1;
    }

    // ---- reading (tenant context) ----

    Optional<Delivery> find(UUID subscriptionId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from webhook_delivery where tenant_id = :tenant "
                        + "and subscription_id = :subscription and id = :id and deleted_at is null")
                .param("tenant", tenant()).param("subscription", subscriptionId).param("id", id)
                .query((rs, row) -> delivery(rs)).optional();
    }

    Optional<Delivery> findAny(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from webhook_delivery where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null").param("tenant", tenant()).param("id", id)
                .query((rs, row) -> delivery(rs)).optional();
    }

    /**
     * The newest deliveries of the subscription, optionally only those in these states, and optionally only those
     * older than the identifier of the last one the person saw. Identifiers are time ordered, so they are the page key.
     */
    List<Delivery> page(UUID subscriptionId, List<String> states, UUID before, int limit) {
        String filter = (states.isEmpty() ? "" : "and state in (:states) ")
                + (before == null ? "" : "and id < :before ");
        var statement = jdbc.sql("select " + COLUMNS + " from webhook_delivery where tenant_id = :tenant "
                        + "and subscription_id = :subscription and deleted_at is null " + filter
                        + "order by id desc limit :limit")
                .param("tenant", tenant()).param("subscription", subscriptionId).param("limit", limit);
        if (!states.isEmpty()) {
            statement = statement.param("states", List.copyOf(states));
        }
        if (before != null) {
            statement = statement.param("before", before);
        }
        return statement.query((rs, row) -> delivery(rs)).list();
    }

    /** The identifiers of the dead letters of the subscription, oldest first, up to {@code limit}. */
    List<UUID> deadLetterIds(UUID subscriptionId, int limit) {
        return jdbc.sql("select id from webhook_delivery where tenant_id = :tenant and subscription_id = :subscription "
                        + "and state = 'DEAD_LETTERED' and deleted_at is null order by id limit :limit")
                .param("tenant", tenant()).param("subscription", subscriptionId).param("limit", limit)
                .query(UUID.class).list();
    }

    /** What the queue of one subscription holds, by state. */
    record Counts(long pending, long deadLettered, long done, long skipped, Instant oldestOpen) {
    }

    Counts counts(UUID subscriptionId) {
        return jdbc.sql("select count(*) filter (where state in (" + OPEN + ")) as pending, "
                        + "count(*) filter (where state = 'DEAD_LETTERED') as dead, "
                        + "count(*) filter (where state = 'DONE') as done, "
                        + "count(*) filter (where state in ('SKIPPED', 'SUPERSEDED')) as skipped, "
                        + "min(run_at) filter (where state in (" + OPEN + ")) as oldest_open "
                        + "from webhook_delivery where tenant_id = :tenant and subscription_id = :subscription "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("subscription", subscriptionId)
                .query((rs, row) -> new Counts(rs.getLong("pending"), rs.getLong("dead"), rs.getLong("done"),
                        rs.getLong("skipped"), instant(rs, "oldest_open")))
                .single();
    }

    /** How many deliveries of the subscription are held by a worker whose lease has not ended. */
    int inFlight(UUID subscriptionId, UUID except) {
        return jdbc.sql("select count(*) from webhook_delivery where tenant_id = :tenant "
                        + "and subscription_id = :subscription and state = 'SENDING' and locked_until > now() "
                        + "and id <> :except and deleted_at is null")
                .param("tenant", tenant()).param("subscription", subscriptionId).param("except", except)
                .query(Integer.class).single();
    }

    /**
     * Removes what ended before the retention, a bounded batch at a time, so the table never grows without end. The
     * database refuses to delete anything younger than a day or anything still open. Idempotent: a second run finds
     * what the first left.
     */
    int purge(Duration retention, int batch) {
        return jdbc.sql("delete from webhook_delivery where id in (select id from webhook_delivery "
                        + "where tenant_id = :tenant and state not in (" + OPEN + ") "
                        + "and created_at < now() - make_interval(secs => :secs) limit :batch)")
                .param("tenant", tenant()).param("secs", (double) retention.toMillis() / 1000.0)
                .param("batch", batch).update();
    }

    private static Delivery delivery(ResultSet rs) throws SQLException {
        long recordVersion = rs.getLong("record_version");
        Long version = rs.wasNull() ? null : recordVersion;
        int status = rs.getInt("http_status");
        Integer httpStatus = rs.wasNull() ? null : status;
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("subscription_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("object_api_name"),
                rs.getObject("record_id", UUID.class), version, rs.getString("state"), rs.getString("reason"),
                rs.getString("queued_reason"), rs.getString("last_status_class"), httpStatus, rs.getInt("attempts"),
                rs.getInt("generation"), rs.getTimestamp("run_at").toInstant(), instant(rs, "last_attempt_at"),
                rs.getTimestamp("created_at").toInstant(), instant(rs, "finished_at"), rs.getLong("version"));
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
