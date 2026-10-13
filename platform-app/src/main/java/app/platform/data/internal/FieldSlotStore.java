package app.platform.data.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The table {@code field_slot} (migration V041): which field of which object holds which slot, and how far its copy
 * is (ADR-0101). Also the locks that make a change of the assignments safe (ADR-0101): every write of a record holds a
 * <em>shared</em> advisory lock of its object for the rest of its transaction, and a change of the assignments of an
 * object takes the lock <em>exclusively</em>, so it waits for the writes in flight and every write that starts after
 * it sees the new assignments. A name or a value is only ever a parameter.
 */
@Repository
class FieldSlotStore {

    /** Where the copy of a field is in its life. */
    enum State { FILLING, FILLED, CLEARING }

    /**
     * One assigned slot.
     *
     * @param id the row
     * @param objectApiName the object
     * @param fieldApiName the field
     * @param slot the slot column
     * @param state where the copy is
     * @param cursor the identifier of the last record the fill looked at, or null
     * @param recordsDone how many records the fill or the clearing has looked at
     * @param verifiedAt when the copy was last checked complete, or null
     */
    record Row(UUID id, String objectApiName, String fieldApiName, SlotColumn slot, State state, UUID cursor,
            long recordsDone, OffsetDateTime verifiedAt) {
    }

    private static final String COLUMNS = "id, object_api_name, field_api_name, slot, state, cursor_id, "
            + "records_done, verified_at";

    private static final String LOCK_SHARED = "select pg_advisory_xact_lock_shared(hashtextextended("
            + "cast(:tenant as text) || '/' || :object, 16))";

    private static final String TRY_EXCLUSIVE = "select pg_try_advisory_xact_lock(hashtextextended("
            + "cast(:tenant as text) || '/' || :object, 16))";

    private static final String TRY_WORK_LOCK = "select pg_try_advisory_xact_lock(hashtextextended("
            + "cast(:tenant as text) || '/' || :object || '/work', 17))";

    private static final String ACTIVE = "select " + COLUMNS + " from field_slot where tenant_id = :tenant "
            + "and object_api_name = :object and deleted_at is null and state in ('FILLING', 'FILLED')";

    private static final String ALL = "select " + COLUMNS + " from field_slot where tenant_id = :tenant "
            + "and deleted_at is null order by object_api_name, slot";

    private static final String FOR_OBJECT = "select " + COLUMNS + " from field_slot where tenant_id = :tenant "
            + "and object_api_name = :object and deleted_at is null order by slot";

    private static final String ONE = "select " + COLUMNS + " from field_slot where tenant_id = :tenant "
            + "and id = :id and deleted_at is null";

    private static final String INSERT = "insert into field_slot (tenant_id, object_api_name, field_api_name, slot, "
            + "created_by, updated_by) values (:tenant, :object, :field, :slot, :actor, :actor)";

    private static final String PROGRESS = "update field_slot set cursor_id = :cursor, "
            + "records_done = records_done + :done, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and id = :id and deleted_at is null";

    private static final String RESTART = "update field_slot set cursor_id = null, records_done = 0, "
            + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
            + "and deleted_at is null";

    private static final String FILLED = "update field_slot set state = 'FILLED', verified_at = now(), "
            + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
            + "and deleted_at is null and state = 'FILLING'";

    private static final String CLEARING = "update field_slot set state = 'CLEARING', cursor_id = null, "
            + "records_done = 0, version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
            + "and deleted_at is null and state <> 'CLEARING'";

    private static final String FREE = "update field_slot set deleted_at = now(), deleted_by = :actor, "
            + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
            + "and deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    FieldSlotStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /**
     * Holds the shared lock of the object to the end of the transaction and answers the slots a write must fill: the
     * copies being written (FILLING) and the complete ones (FILLED). A slot that is being cleared is not among them:
     * a write leaves it empty.
     */
    List<Row> activeForWrite(String objectApiName) {
        jdbc.sql(LOCK_SHARED).param("tenant", tenant()).param("object", objectApiName).query().listOfRows();
        return jdbc.sql(ACTIVE).param("tenant", tenant()).param("object", objectApiName).query(FieldSlotStore::row)
                .list();
    }

    /** Every assigned slot of the organization (live rows), by object and slot. */
    List<Row> all() {
        return jdbc.sql(ALL).param("tenant", tenant()).query(FieldSlotStore::row).list();
    }

    /** The assigned slots (live rows) of one object, in any state. */
    List<Row> forObject(String objectApiName) {
        return jdbc.sql(FOR_OBJECT).param("tenant", tenant()).param("object", objectApiName)
                .query(FieldSlotStore::row).list();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(ONE).param("tenant", tenant()).param("id", id).query(FieldSlotStore::row).optional();
    }

    /**
    /**
     * Takes the lock of the object exclusively, trying for at most {@code waitMillis} while writes are in flight.
     * It never queues behind a write (a pending exclusive request would hold back every new write) and never
     * raises an error, so the transaction stays usable when the lock could not be had.
     *
     * @return whether the lock was taken; false when writes in flight did not end in time (try again later)
     */
    boolean lockExclusive(String objectApiName, long waitMillis) {
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (true) {
            if (Boolean.TRUE.equals(jdbc.sql(TRY_EXCLUSIVE).param("tenant", tenant())
                    .param("object", objectApiName).query(Boolean.class).single())) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Takes, to the end of the transaction, the right to work on the slots of an object; false when another has it. */
    boolean tryWorkLock(String objectApiName) {
        return Boolean.TRUE.equals(jdbc.sql(TRY_WORK_LOCK).param("tenant", tenant()).param("object", objectApiName)
                .query(Boolean.class).single());
    }

    void insert(String objectApiName, String fieldApiName, SlotColumn slot, UUID actor) {
        jdbc.sql(INSERT).param("tenant", tenant()).param("object", objectApiName).param("field", fieldApiName)
                .param("slot", slot.column()).param("actor", actor).update();
    }

    void progress(UUID id, UUID cursor, int done, UUID actor) {
        jdbc.sql(PROGRESS).param("tenant", tenant()).param("id", id).param("cursor", cursor).param("done", done)
                .param("actor", actor).update();
    }

    void restart(UUID id, UUID actor) {
        jdbc.sql(RESTART).param("tenant", tenant()).param("id", id).param("actor", actor).update();
    }

    boolean markFilled(UUID id, UUID actor) {
        return jdbc.sql(FILLED).param("tenant", tenant()).param("id", id).param("actor", actor).update() == 1;
    }

    boolean markClearing(UUID id, UUID actor) {
        return jdbc.sql(CLEARING).param("tenant", tenant()).param("id", id).param("actor", actor).update() == 1;
    }

    void free(UUID id, UUID actor) {
        jdbc.sql(FREE).param("tenant", tenant()).param("id", id).param("actor", actor).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getString("field_api_name"),
                SlotColumn.byColumn(rs.getString("slot")).orElseThrow(), State.valueOf(rs.getString("state")),
                rs.getObject("cursor_id", UUID.class), rs.getLong("records_done"),
                rs.getObject("verified_at", OffsetDateTime.class));
    }
}
