package app.platform.data.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The tables {@code record_link} (who points at whom) and {@code link_index} (whether that is complete for the
 * records that were there before the table), migration V042 (Sprint 16, ADR-0106). Never reads a record: whether the
 * records found here are alive is asked of the record store. Names and identifiers are only ever parameters.
 */
@Repository
class RecordLinkStore {

    /** Where the pointers of one reference field stand. */
    enum State { FILLING, FILLED }

    /**
     * A record that points at another.
     *
     * @param sourceObject the object of the pointing record
     * @param sourceRecord the pointing record
     * @param field the reference field
     */
    record Incoming(String sourceObject, UUID sourceRecord, String field) {
    }

    /**
     * The state of the pointers of one field.
     *
     * @param id the row
     * @param objectApiName the object
     * @param fieldApiName the field
     * @param state where it stands
     * @param cursor the last record the fill looked at, or null
     * @param recordsDone how many records the fill has looked at
     */
    record Index(UUID id, String objectApiName, String fieldApiName, State state, UUID cursor, long recordsDone) {
    }

    private static final String UPSERT = "insert into record_link (tenant_id, source_object, source_record, "
            + "field_api_name, target_object, target_record, created_by, updated_by) "
            + "values (:tenant, :object, :record, :field, :targetObject, :target, :actor, :actor) "
            + "on conflict (tenant_id, source_record, field_api_name) where deleted_at is null "
            + "do update set target_object = excluded.target_object, target_record = excluded.target_record, "
            + "version = record_link.version + 1, updated_by = :actor";

    private static final String REMOVE = "delete from record_link where tenant_id = :tenant "
            + "and source_record = :record and field_api_name = :field";

    private static final String REMOVE_ALL = "delete from record_link where tenant_id = :tenant "
            + "and source_record = :record";

    private static final String INCOMING = "select source_object, source_record, field_api_name from record_link "
            + "where tenant_id = :tenant and target_record = :target and deleted_at is null "
            + "order by source_object, field_api_name, source_record limit :limit";

    // Two pointers of the same record, one through each field of a junction.
    private static final String PAIR = "select exists (select 1 from record_link one join record_link other "
            + "on other.tenant_id = one.tenant_id and other.source_record = one.source_record "
            + "where one.tenant_id = :tenant and one.source_object = :object and one.field_api_name = :first "
            + "and one.target_record = :left and other.field_api_name = :second and other.target_record = :right "
            + "and one.deleted_at is null and other.deleted_at is null and one.source_record <> :except)";

    private static final String LOCK_PAIR = "select pg_advisory_xact_lock(hashtextextended("
            + "cast(:tenant as text) || '/' || :object || '/' || :left || '/' || :right, 18))";

    private static final String INDEX_COLUMNS = "id, object_api_name, field_api_name, state, cursor_id, "
            + "records_done";

    private static final String INDEXES = "select " + INDEX_COLUMNS + " from link_index "
            + "where tenant_id = :tenant and deleted_at is null order by object_api_name, field_api_name";

    private static final String INDEX_ONE = "select " + INDEX_COLUMNS + " from link_index "
            + "where tenant_id = :tenant and id = :id and deleted_at is null";

    private static final String INDEX_INSERT = "insert into link_index (tenant_id, object_api_name, field_api_name, "
            + "created_by, updated_by) values (:tenant, :object, :field, :actor, :actor) "
            + "on conflict (tenant_id, object_api_name, field_api_name) where deleted_at is null do nothing";

    private static final String INDEX_PROGRESS = "update link_index set cursor_id = :cursor, "
            + "records_done = records_done + :done, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and id = :id and deleted_at is null";

    private static final String INDEX_FILLED = "update link_index set state = 'FILLED', version = version + 1, "
            + "updated_by = :actor where tenant_id = :tenant and id = :id and deleted_at is null";

    private static final String TRY_WORK_LOCK = "select pg_try_advisory_xact_lock(hashtextextended("
            + "cast(:tenant as text) || '/' || :object || '/' || :field || '/links', 19))";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    RecordLinkStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    // ---- the pointers ----

    void upsert(String sourceObject, UUID sourceRecord, String field, String targetObject, UUID target,
            UUID actor) {
        jdbc.sql(UPSERT).param("tenant", tenant()).param("object", sourceObject).param("record", sourceRecord)
                .param("field", field).param("targetObject", targetObject).param("target", target)
                .param("actor", actor).update();
    }

    void remove(UUID sourceRecord, String field) {
        jdbc.sql(REMOVE).param("tenant", tenant()).param("record", sourceRecord).param("field", field).update();
    }

    void removeAll(UUID sourceRecord) {
        jdbc.sql(REMOVE_ALL).param("tenant", tenant()).param("record", sourceRecord).update();
    }

    /** The pointers at a record, at most {@code limit}, in a stable order. */
    List<Incoming> incoming(UUID target, int limit) {
        return jdbc.sql(INCOMING).param("tenant", tenant()).param("target", target).param("limit", limit)
                .query((rs, row) -> new Incoming(rs.getString("source_object"),
                        rs.getObject("source_record", UUID.class), rs.getString("field_api_name"))).list();
    }

    /**
     * Whether another record of the junction already links the same two parents. Takes, for the rest of the
     * transaction, a lock on the pair, so that two writes of the same pair at once cannot both pass (the second waits
     * for the first to commit and then finds it).
     *
     * @param except the record being written (it does not count against itself), or the nil identifier
     */
    boolean pairTaken(String object, String firstField, UUID left, String secondField, UUID right, UUID except) {
        jdbc.sql(LOCK_PAIR).param("tenant", tenant()).param("object", object).param("left", left.toString())
                .param("right", right.toString()).query().listOfRows();
        return Boolean.TRUE.equals(jdbc.sql(PAIR).param("tenant", tenant()).param("object", object)
                .param("first", firstField).param("left", left).param("second", secondField).param("right", right)
                .param("except", except).query(Boolean.class).single());
    }

    // ---- the state of the pointers ----

    List<Index> indexes() {
        return jdbc.sql(INDEXES).param("tenant", tenant()).query(RecordLinkStore::index).list();
    }

    Optional<Index> index(UUID id) {
        return jdbc.sql(INDEX_ONE).param("tenant", tenant()).param("id", id).query(RecordLinkStore::index)
                .optional();
    }

    void ensureIndex(String object, String field, UUID actor) {
        jdbc.sql(INDEX_INSERT).param("tenant", tenant()).param("object", object).param("field", field)
                .param("actor", actor).update();
    }

    void indexProgress(UUID id, UUID cursor, int done, UUID actor) {
        jdbc.sql(INDEX_PROGRESS).param("tenant", tenant()).param("id", id).param("cursor", cursor)
                .param("done", done).param("actor", actor).update();
    }

    void indexFilled(UUID id, UUID actor) {
        jdbc.sql(INDEX_FILLED).param("tenant", tenant()).param("id", id).param("actor", actor).update();
    }

    boolean tryWorkLock(String object, String field) {
        return Boolean.TRUE.equals(jdbc.sql(TRY_WORK_LOCK).param("tenant", tenant()).param("object", object)
                .param("field", field).query(Boolean.class).single());
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Index index(ResultSet rs, int row) throws SQLException {
        return new Index(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getString("field_api_name"), State.valueOf(rs.getString("state")),
                rs.getObject("cursor_id", UUID.class), rs.getLong("records_done"));
    }
}
