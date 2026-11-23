package app.platform.integration.internal;

import app.platform.integration.internal.CircuitBreaker.Mode;
import app.platform.integration.internal.CircuitBreaker.State;
import app.platform.integration.internal.RateLimiter.Bucket;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code webhook_subscription} (Sprint 32, ADR-0274, ADR-0276). One row for each standing
 * instruction of an organization to be told at an address of its own: the definition, the member it acts as (fixed at
 * the
 * database), the state, the number of its signing secret, and the circuit breaker and token bucket of the address, kept
 * here and handled under the row's lock so that every instance agrees. It never holds a secret and never a record
 * value;
 * the address is the organization's own data and is read only for its own screen and for the call.
 *
 * <p>Every statement runs in the organization's own tenant context, in the caller's transaction, and row level security
 * refuses any other.
 */
@Repository
class WebhookSubscriptionStore {

    /**
     * A subscription as stored.
     *
     * @param events the events asked for
     * @param objects the only objects it tells about, or null for every object its member may read
     * @param circuit the breaker of the address
     * @param bucket the token bucket of the address
     * @param openedAt when the circuit last opened, or null
     */
    record Subscription(UUID id, String name, String address, List<String> events, List<String> objects,
            UUID membershipId, UUID userId, SubscriptionMachine.State state, String disabledReason, String fingerprint,
            String verifiedFingerprint, Instant verifiedAt, int secretNumber, Instant secretMadeAt,
            Integer previousSecretNumber, Instant previousSecretUntil, State circuit, Bucket bucket, Instant openedAt,
            Instant lastSuccessAt, Instant lastFailureAt, String lastFailureReason, Instant failingSince,
            Instant createdAt, long version) {

        /** Whether the address answered a verification exactly as it stands. */
        boolean verified() {
            return verifiedFingerprint != null && verifiedFingerprint.equals(fingerprint);
        }

        /** The address is the organization's own text: it never appears in a log line that prints the row. */
        @Override
        public String toString() {
            return "Subscription[" + id + "]";
        }
    }

    private static final String COLUMNS = "id, name, address, array_to_string(events, ',') as events, "
            + "array_to_string(objects, ',') as objects, membership_id, user_id, state, disabled_reason, fingerprint, "
            + "verified_fingerprint, verified_at, secret_number, secret_made_at, previous_secret_number, "
            + "previous_secret_until, circuit_state, consecutive_failures, opened_at, open_until, probes_in_flight, "
            + "probe_started_at, tokens, tokens_at, last_success_at, last_failure_at, last_failure_reason, "
            + "failing_since, created_at, version";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    WebhookSubscriptionStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    // ---- making and changing ----

    /** Makes a subscription, switched off, with its first secret number and a full bucket. Returns its identifier. */
    UUID insert(String name, String address, List<String> events, List<String> objects, UUID membershipId,
            UUID userId, String fingerprint, double initialTokens, UUID actor) {
        return jdbc.sql("insert into webhook_subscription (name, address, events, objects, membership_id, user_id, "
                        + "fingerprint, tokens, created_by, updated_by) values (:name, :address, "
                        + "string_to_array(:events, ','), "
                        + "case when cast(:objects as text) is null then null "
                        + "else string_to_array(cast(:objects as text), ',') end, :membership, :user, :fingerprint, "
                        + ":tokens, :actor, :actor) returning id")
                .param("name", name).param("address", address).param("events", String.join(",", events))
                .param("objects", objects == null ? null : String.join(",", objects), Types.VARCHAR)
                .param("membership", membershipId).param("user", userId).param("fingerprint", fingerprint)
                .param("tokens", initialTokens).param("actor", actor).query(UUID.class).single();
    }

    /**
     * Changes what a switched-off subscription is. It stays off, with the reason {@code changed}; the last
     * verification stays on the row but belongs to the old fingerprint, so it no longer counts. False when the version
     * is not the one read.
     */
    boolean update(UUID id, long version, String name, String address, List<String> events, List<String> objects,
            String fingerprint, UUID actor) {
        return jdbc.sql("update webhook_subscription set name = :name, address = :address, "
                        + "events = string_to_array(:events, ','), "
                        + "objects = case when cast(:objects as text) is null then null "
                        + "else string_to_array(cast(:objects as text), ',') end, fingerprint = :fingerprint, "
                        + "disabled_reason = 'changed', version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and version = :version and state = 'DISABLED' "
                        + "and deleted_at is null")
                .param("name", name).param("address", address).param("events", String.join(",", events))
                .param("objects", objects == null ? null : String.join(",", objects), Types.VARCHAR)
                .param("fingerprint", fingerprint).param("actor", actor).param("tenant", tenant()).param("id", id)
                .param("version", version).update() == 1;
    }

    /** Records that the address answered a verification for this fingerprint. */
    void markVerified(UUID id, String fingerprint) {
        jdbc.sql("update webhook_subscription set verified_fingerprint = :fingerprint, verified_at = now(), "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id "
                        + "and fingerprint = :fingerprint and deleted_at is null")
                .param("fingerprint", fingerprint).param("system", ActorId.SYSTEM.value())
                .param("tenant", tenant()).param("id", id).update();
    }

    /**
     * Moves the subscription to a state. Switching on starts afresh: a closed circuit, a full bucket and no run of
     * failures. Returns false when the version is not the one read (a null version skips the check, for the platform's
     * own moves).
     */
    boolean setState(UUID id, Long version, SubscriptionMachine.State state, String disabledReason, double tokens,
            UUID actor) {
        boolean on = state == SubscriptionMachine.State.ACTIVE;
        return jdbc.sql("update webhook_subscription set state = :state, disabled_reason = :reason, "
                        + "circuit_state = case when :on then 'CLOSED' else circuit_state end, "
                        + "consecutive_failures = case when :on then 0 else consecutive_failures end, "
                        + "opened_at = case when :on then null else opened_at end, "
                        + "open_until = case when :on then null else open_until end, "
                        + "probes_in_flight = case when :on then 0 else probes_in_flight end, "
                        + "probe_started_at = case when :on then null else probe_started_at end, "
                        + "tokens = case when :on then :tokens else tokens end, "
                        + "tokens_at = case when :on then now() else tokens_at end, "
                        + "failing_since = case when :on then null else failing_since end, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and (cast(:version as bigint) is null or version = :version) and deleted_at is null")
                .param("state", state.name()).param("reason", disabledReason, Types.VARCHAR).param("on", on)
                .param("tokens", tokens).param("actor", actor).param("tenant", tenant()).param("id", id)
                .param("version", version, Types.BIGINT).update() == 1;
    }

    /** Makes a new secret current; the earlier one may keep signing until {@code previousUntil}. */
    boolean rotate(UUID id, int number, Integer previousNumber, Instant previousUntil, UUID actor) {
        return jdbc.sql("update webhook_subscription set secret_number = :number, secret_made_at = now(), "
                        + "previous_secret_number = :previous, previous_secret_until = :until, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null")
                .param("number", number).param("previous", previousNumber, Types.INTEGER)
                .param("until", previousUntil == null ? null : Timestamp.from(previousUntil), Types.TIMESTAMP)
                .param("actor", actor).param("tenant", tenant()).param("id", id).update() == 1;
    }

    /** Ends the overlap: the earlier secret no longer signs. */
    boolean endPrevious(UUID id) {
        return jdbc.sql("update webhook_subscription set previous_secret_number = null, "
                        + "previous_secret_until = null, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id and previous_secret_number is not null")
                .param("system", ActorId.SYSTEM.value()).param("tenant", tenant())
                .param("id", id).update() == 1;
    }

    /** Removes the subscription by marking it; it stays removed. */
    boolean remove(UUID id, UUID actor) {
        return jdbc.sql("update webhook_subscription set deleted_at = now(), deleted_by = :actor, "
                        + "state = 'DISABLED', disabled_reason = coalesce(disabled_reason, 'switched_off'), "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("id", id).update() == 1;
    }

    // ---- reading ----

    Optional<Subscription> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from webhook_subscription where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("id", id).query((rs, row) -> subscription(rs)).optional();
    }

    /** The subscription, held until the end of the transaction so that decisions about it are made one at a time. */
    Optional<Subscription> lock(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from webhook_subscription where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null for update")
                .param("tenant", tenant()).param("id", id).query((rs, row) -> subscription(rs)).optional();
    }

    List<Subscription> list() {
        return jdbc.sql("select " + COLUMNS + " from webhook_subscription where tenant_id = :tenant "
                        + "and deleted_at is null order by created_at, id")
                .param("tenant", tenant()).query((rs, row) -> subscription(rs)).list();
    }

    int count() {
        return jdbc.sql("select count(*) from webhook_subscription where tenant_id = :tenant and deleted_at is null")
                .param("tenant", tenant()).query(Integer.class).single();
    }

    boolean nameTaken(String name) {
        return jdbc.sql("select count(*) from webhook_subscription where tenant_id = :tenant and name = :name "
                        + "and deleted_at is null").param("tenant", tenant()).param("name", name)
                .query(Integer.class).single() > 0;
    }

    /** The switched-on subscriptions that ask for the event and are not limited to other objects. */
    List<Subscription> matching(String eventType, String object) {
        return jdbc.sql("select " + COLUMNS + " from webhook_subscription where tenant_id = :tenant "
                        + "and state in ('ACTIVE', 'FAILING') and deleted_at is null and :event = any(events) "
                        + "and (objects is null or :object = any(objects)) order by created_at, id")
                .param("tenant", tenant()).param("event", eventType).param("object", object)
                .query((rs, row) -> subscription(rs)).list();
    }

    // ---- the circuit breaker, the bucket and what was learned (the row is held by lock) ----

    /** Stores the breaker and the bucket after a decision. */
    void saveFlow(UUID id, State circuit, Bucket bucket, Instant openedAt) {
        jdbc.sql("update webhook_subscription set circuit_state = :mode, consecutive_failures = :failures, "
                        + "opened_at = :openedAt, open_until = :openUntil, probes_in_flight = :probes, "
                        + "probe_started_at = :probeAt, tokens = :tokens, tokens_at = :tokensAt, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id")
                .param("mode", circuit.mode().name()).param("failures", circuit.consecutiveFailures())
                .param("openedAt", ts(openedAt), Types.TIMESTAMP).param("openUntil", ts(circuit.openUntil()),
                        Types.TIMESTAMP)
                .param("probes", circuit.probesInFlight()).param("probeAt", ts(circuit.probeStartedAt()),
                        Types.TIMESTAMP)
                .param("tokens", bucket.tokens()).param("tokensAt", ts(bucket.at()))
                .param("system", ActorId.SYSTEM.value()).param("tenant", tenant())
                .param("id", id).update();
    }

    /** An attempt was accepted: the time of the last success, and the run of failures is over. */
    void markSuccess(UUID id, Instant now) {
        jdbc.sql("update webhook_subscription set last_success_at = :now, failing_since = null, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id")
                .param("now", ts(now)).param("system", ActorId.SYSTEM.value())
                .param("tenant", tenant()).param("id", id).update();
    }

    /** An attempt failed in a way that shows the address is unhealthy: when, why, and since when it has failed. */
    void markFailure(UUID id, Instant now, String reason) {
        jdbc.sql("update webhook_subscription set last_failure_at = :now, last_failure_reason = :reason, "
                        + "failing_since = coalesce(failing_since, :now), version = version + 1, "
                        + "updated_by = :system where tenant_id = :tenant and id = :id")
                .param("now", ts(now)).param("reason", reason)
                .param("system", ActorId.SYSTEM.value()).param("tenant", tenant())
                .param("id", id).update();
    }

    // ---- reading rows ----

    private static Subscription subscription(ResultSet rs) throws SQLException {
        int previous = rs.getInt("previous_secret_number");
        Integer previousNumber = rs.wasNull() ? null : previous;
        State circuit = new State(Mode.valueOf(rs.getString("circuit_state")), rs.getInt("consecutive_failures"),
                instant(rs, "open_until"), rs.getInt("probes_in_flight"), instant(rs, "probe_started_at"), null);
        return new Subscription(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                split(rs.getString("events")), rs.getString("objects") == null ? null : split(rs.getString("objects")),
                rs.getObject("membership_id", UUID.class), rs.getObject("user_id", UUID.class),
                SubscriptionMachine.State.valueOf(rs.getString("state")), rs.getString("disabled_reason"),
                rs.getString("fingerprint"), rs.getString("verified_fingerprint"), instant(rs, "verified_at"),
                rs.getInt("secret_number"), rs.getTimestamp("secret_made_at").toInstant(), previousNumber,
                instant(rs, "previous_secret_until"), circuit,
                new Bucket(rs.getDouble("tokens"), rs.getTimestamp("tokens_at").toInstant()),
                instant(rs, "opened_at"), instant(rs, "last_success_at"), instant(rs, "last_failure_at"),
                rs.getString("last_failure_reason"), instant(rs, "failing_since"),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version"));
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : List.copyOf(Arrays.asList(joined.split(",")));
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
