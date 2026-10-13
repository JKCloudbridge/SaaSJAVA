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
 * The table {@code unique_enforcement} (migration V043): which unique field, or which pair of parents of a junction,
 * the database enforces for an object, and how far the mask of the records that existed before is (ADR-0111). Changed
 * under the same exclusive lock of the object as the slot assignments (see {@link FieldSlotStore}); a name or a value
 * is only ever a parameter.
 */
@Repository
class UniqueStore {

    /** Where the enforcement of one constraint is in its life. */
    enum State { ENFORCING, CONFLICT, ENFORCED }

    /** What a constraint is about. */
    enum Kind { VALUE, PAIR }

    /**
     * One enforced constraint.
     *
     * @param id the row
     * @param objectApiName the object
     * @param kind the unique value of a field, or the pair of parents of a junction
     * @param fieldApiName the field (the first master of a pair)
     * @param partnerApiName the second master of a pair, or null
     * @param bit the number of the constraint ({@link UniqueKey#bit()})
     * @param state where the enforcement is
     * @param cursor the identifier of the last record the mask was written for, or null
     * @param recordsDone how many records the mask has been written for in this round
     * @param conflicts how many records hold a value another record holds too (when in CONFLICT)
     */
    record Row(UUID id, String objectApiName, Kind kind, String fieldApiName, String partnerApiName, int bit,
            State state, UUID cursor, long recordsDone, long conflicts) {

        /** The constraint of the row. */
        UniqueKey key() {
            return UniqueKey.ofBit(bit).orElseThrow();
        }

        /** Where a refusal is answered: the field, or the second parent of a junction pair. */
        String place() {
            return kind == Kind.PAIR ? partnerApiName : fieldApiName;
        }
    }

    private static final String COLUMNS = "id, object_api_name, kind, field_api_name, partner_api_name, "
            + "constraint_bit, state, cursor_id, records_done, conflicts";

    private static final String ALL = "select " + COLUMNS + " from unique_enforcement where tenant_id = :tenant "
            + "and deleted_at is null order by object_api_name, constraint_bit";

    private static final String FOR_OBJECT = "select " + COLUMNS + " from unique_enforcement "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null order by constraint_bit";

    private static final String ONE = "select " + COLUMNS + " from unique_enforcement where tenant_id = :tenant "
            + "and id = :id and deleted_at is null";

    private static final String INSERT = "insert into unique_enforcement (tenant_id, object_api_name, kind, "
            + "field_api_name, partner_api_name, constraint_bit, created_by, updated_by) values (:tenant, :object, "
            + ":kind, :field, cast(:partner as text), :constraint_bit, :actor, :actor)";

    private static final String PROGRESS = "update unique_enforcement set cursor_id = :cursor, "
            + "records_done = records_done + :done, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and id = :id and deleted_at is null";

    private static final String RESTART = "update unique_enforcement set state = 'ENFORCING', cursor_id = null, "
            + "records_done = 0, conflicts = 0, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and id = :id and deleted_at is null and state <> 'ENFORCED'";

    private static final String ENFORCED = "update unique_enforcement set state = 'ENFORCED', conflicts = 0, "
            + "verified_at = now(), version = version + 1, updated_by = :actor where tenant_id = :tenant "
            + "and id = :id and deleted_at is null and state <> 'ENFORCED'";

    private static final String CONFLICT = "update unique_enforcement set state = 'CONFLICT', conflicts = :conflicts, "
            + "cursor_id = null, version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
            + "and deleted_at is null and state <> 'ENFORCED'";

    private static final String FREE = "update unique_enforcement set deleted_at = now(), deleted_by = :actor, "
            + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
            + "and deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    UniqueStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Every enforced constraint of the organization (live rows), by object and bit. */
    List<Row> all() {
        return jdbc.sql(ALL).param("tenant", tenant()).query(UniqueStore::row).list();
    }

    /** The enforced constraints of one object (live rows), in any state. */
    List<Row> forObject(String objectApiName) {
        return jdbc.sql(FOR_OBJECT).param("tenant", tenant()).param("object", objectApiName)
                .query(UniqueStore::row).list();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(ONE).param("tenant", tenant()).param("id", id).query(UniqueStore::row).optional();
    }

    void insert(String objectApiName, Kind kind, String fieldApiName, String partnerApiName, int bit, UUID actor) {
        jdbc.sql(INSERT).param("tenant", tenant()).param("object", objectApiName).param("kind", kind.name())
                .param("field", fieldApiName).param("partner", partnerApiName).param("constraint_bit", bit)
                .param("actor", actor).update();
    }

    void progress(UUID id, UUID cursor, long done, UUID actor) {
        jdbc.sql(PROGRESS).param("tenant", tenant()).param("id", id).param("cursor", cursor).param("done", done)
                .param("actor", actor).update();
    }

    /** Starts the round over (from CONFLICT after the data was fixed, or when a check found a record unmasked). */
    void restart(UUID id, UUID actor) {
        jdbc.sql(RESTART).param("tenant", tenant()).param("id", id).param("actor", actor).update();
    }

    boolean markEnforced(UUID id, UUID actor) {
        return jdbc.sql(ENFORCED).param("tenant", tenant()).param("id", id).param("actor", actor).update() == 1;
    }

    boolean markConflict(UUID id, long conflicts, UUID actor) {
        return jdbc.sql(CONFLICT).param("tenant", tenant()).param("id", id).param("conflicts", conflicts)
                .param("actor", actor).update() == 1;
    }

    void free(UUID id, UUID actor) {
        jdbc.sql(FREE).param("tenant", tenant()).param("id", id).param("actor", actor).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                Kind.valueOf(rs.getString("kind")), rs.getString("field_api_name"), rs.getString("partner_api_name"),
                rs.getInt("constraint_bit"), State.valueOf(rs.getString("state")),
                rs.getObject("cursor_id", UUID.class), rs.getLong("records_done"), rs.getLong("conflicts"));
    }
}
