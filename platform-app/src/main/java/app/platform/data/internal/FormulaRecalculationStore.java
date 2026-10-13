package app.platform.data.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The table {@code formula_recalculation} (migration V043): whether the stored formula results of an object are current
 * for its formulas, and the pass that brings them there (ADR-0113). A name or a value is only ever a parameter.
 */
@Repository
class FormulaRecalculationStore {

    /** Where an object's recalculation is. */
    enum State { IDLE, PENDING, RUNNING, CLOSING }

    /** What started the pass in flight. */
    enum Trigger { PUBLISH, MANUAL, RESUME }

    /**
     * The recalculation state of one object.
     *
     * @param id the row
     * @param objectApiName the object
     * @param state IDLE (nothing is running), PENDING (asked for, not started), RUNNING (the first sweep) or CLOSING
     *        (waiting for writes that began before the change to end, then looking again at the records changed since)
     * @param currentStamp the fingerprint of the formulas the stored results are known to be current for, or null
     * @param targetStamp the fingerprint the pass in flight works towards, or null
     * @param startedBy what started the pass in flight, or null
     * @param cursor the identifier of the last record the pass looked at, or null
     * @param sweepStartedAt when the first sweep began: records changed since then are looked at again, or null
     * @param closingAfter when the closing look may begin, or null
     * @param startedAt when the pass began, or null
     * @param examined records looked at by the pass in flight
     * @param changed records whose results were rewritten
     * @param unavailable records with a formula that could not be calculated
     * @param lastFinishedAt when the last pass finished, or null
     * @param lastExamined records the last pass looked at
     * @param lastChanged records the last pass rewrote
     * @param lastUnavailable records the last pass could not calculate
     */
    record Row(UUID id, String objectApiName, State state, String currentStamp, String targetStamp, Trigger startedBy,
            UUID cursor, Instant sweepStartedAt, Instant closingAfter, Instant startedAt, long examined, long changed,
            long unavailable, Instant lastFinishedAt, long lastExamined, long lastChanged, long lastUnavailable) {
    }

    private static final String COLUMNS = "id, object_api_name, state, current_stamp, target_stamp, started_by, "
            + "cursor_id, sweep_started_at, closing_after, started_at, examined, changed, unavailable, "
            + "last_finished_at, last_examined, last_changed, last_unavailable";

    private static final String ALL = "select " + COLUMNS + " from formula_recalculation where tenant_id = :tenant "
            + "and deleted_at is null order by object_api_name";

    private static final String ONE = "select " + COLUMNS + " from formula_recalculation where tenant_id = :tenant "
            + "and object_api_name = :object and deleted_at is null";

    private static final String INSERT = "insert into formula_recalculation (tenant_id, object_api_name, "
            + "current_stamp, created_by, updated_by) values (:tenant, :object, cast(:stamp as text), :actor, :actor) "
            + "on conflict (tenant_id, object_api_name) where deleted_at is null do nothing";

    // Asked for: only an object at rest, or one already asked for, can be asked again.
    private static final String REQUEST = "update formula_recalculation set state = 'PENDING', "
            + "started_by = :by, target_stamp = :stamp, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and id = :id and deleted_at is null and state in ('IDLE', 'PENDING')";

    // The first sweep begins (or begins again, for the formulas as they are now): the counts and the cursor restart.
    private static final String BEGIN = "update formula_recalculation set state = 'RUNNING', target_stamp = :stamp, "
            + "started_by = coalesce(started_by, 'RESUME'), cursor_id = null, sweep_started_at = now(), "
            + "closing_after = null, started_at = now(), examined = 0, changed = 0, unavailable = 0, "
            + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
            + "and deleted_at is null";

    private static final String PROGRESS = "update formula_recalculation set cursor_id = :cursor, "
            + "examined = examined + :examined, changed = changed + :changed, "
            + "unavailable = unavailable + :unavailable, "
            + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
            + "and deleted_at is null";

    private static final String CLOSING = "update formula_recalculation set state = 'CLOSING', cursor_id = null, "
            + "closing_after = now() + make_interval(secs => :seconds), version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and id = :id and deleted_at is null and state = 'RUNNING'";

    private static final String COMPLETE = "update formula_recalculation set state = 'IDLE', "
            + "current_stamp = target_stamp, target_stamp = null, started_by = null, cursor_id = null, "
            + "sweep_started_at = null, closing_after = null, last_finished_at = now(), last_examined = examined, "
            + "last_changed = changed, last_unavailable = unavailable, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and id = :id and deleted_at is null and state = 'CLOSING'";

    private static final String NO_FORMULAS = "update formula_recalculation set state = 'IDLE', "
            + "current_stamp = :stamp, target_stamp = null, started_by = null, cursor_id = null, "
            + "sweep_started_at = null, closing_after = null, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and id = :id and deleted_at is null";

    private static final String TRY_LOCK = "select pg_try_advisory_xact_lock(hashtextextended("
            + "cast(:tenant as text) || '/' || :object || '/recalculation', 18))";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    FormulaRecalculationStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    List<Row> all() {
        return jdbc.sql(ALL).param("tenant", tenant()).query(FormulaRecalculationStore::row).list();
    }

    Optional<Row> find(String objectApiName) {
        return jdbc.sql(ONE).param("tenant", tenant()).param("object", objectApiName)
                .query(FormulaRecalculationStore::row).optional();
    }

    /** Makes the row of an object if it has none; the row is at rest and not known to be current for anything. */
    void ensure(String objectApiName, UUID actor) {
        jdbc.sql(INSERT).param("tenant", tenant()).param("object", objectApiName).param("stamp", null)
                .param("actor", actor).update();
    }

    /** Takes, to the end of the transaction, the right to work on the object's recalculation; false when another has
     * it. */
    boolean tryLock(String objectApiName) {
        return Boolean.TRUE.equals(jdbc.sql(TRY_LOCK).param("tenant", tenant()).param("object", objectApiName)
                .query(Boolean.class).single());
    }

    boolean request(UUID id, Trigger by, String stamp, UUID actor) {
        return jdbc.sql(REQUEST).param("tenant", tenant()).param("id", id).param("by", by.name())
                .param("stamp", stamp).param("actor", actor).update() == 1;
    }

    void begin(UUID id, String stamp, UUID actor) {
        jdbc.sql(BEGIN).param("tenant", tenant()).param("id", id).param("stamp", stamp).param("actor", actor)
                .update();
    }

    void progress(UUID id, UUID cursor, long examined, long changed, long unavailable, UUID actor) {
        jdbc.sql(PROGRESS).param("tenant", tenant()).param("id", id).param("cursor", cursor)
                .param("examined", examined).param("changed", changed).param("unavailable", unavailable)
                .param("actor", actor).update();
    }

    boolean closing(UUID id, double seconds, UUID actor) {
        return jdbc.sql(CLOSING).param("tenant", tenant()).param("id", id).param("seconds", seconds)
                .param("actor", actor).update() == 1;
    }

    boolean complete(UUID id, UUID actor) {
        return jdbc.sql(COMPLETE).param("tenant", tenant()).param("id", id).param("actor", actor).update() == 1;
    }

    /** The object has no formulas (any more): nothing to bring up to date, and nothing in flight. */
    void noFormulas(UUID id, UUID actor) {
        jdbc.sql(NO_FORMULAS).param("tenant", tenant()).param("id", id).param("stamp", FormulaStamp.NONE)
                .param("actor", actor).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        String by = rs.getString("started_by");
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                State.valueOf(rs.getString("state")), rs.getString("current_stamp"), rs.getString("target_stamp"),
                by == null ? null : Trigger.valueOf(by), rs.getObject("cursor_id", UUID.class),
                instant(rs, "sweep_started_at"), instant(rs, "closing_after"), instant(rs, "started_at"),
                rs.getLong("examined"), rs.getLong("changed"), rs.getLong("unavailable"),
                instant(rs, "last_finished_at"), rs.getLong("last_examined"), rs.getLong("last_changed"),
                rs.getLong("last_unavailable"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }
}
