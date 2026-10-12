package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.StorageStrategy;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The generic store: every record of every object in the one table {@code object_record}, the values as a JSON object
 * keyed by field API name (ADR-0084, ADR-0088). The only class that names the table.
 *
 * <p><strong>No SQL is ever built from a name.</strong> Every statement below is a constant; the object API name, the
 * field name, the identifiers and the values travel as parameters, so a hostile name can only ever be data (a test
 * proves it). The organization is a parameter of every statement taken from the tenant context, in addition to the
 * database's row level security, which is forced on the table: forgetting the filter would show nothing, not everything
 * (ADR-0003).
 *
 * <p>The typed copies of indexed fields ("slots") exist in the table and are not used before Sprint 16.
 */
@Repository
class JsonbRecordStorage implements StorageStrategy {

    private static final String COLUMNS = "id, object_api_name, record_type, owner_id, "
            + "cast(payload as text) as payload, version, created_at, created_by, updated_at, updated_by, "
            + "deleted_at, deleted_by";

    private static final String INSERT = "insert into object_record "
            + "(tenant_id, object_api_name, owner_id, payload, created_by, updated_by) "
            + "values (:tenant, :object, :owner, cast(:payload as jsonb), :actor, :actor) returning " + COLUMNS;

    private static final String FIND = "select " + COLUMNS + " from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id";

    // A null in the patch removes the field: the merge puts the null in, and stripping the nulls takes the key out.
    private static final String UPDATE = "update object_record "
            + "set payload = jsonb_strip_nulls(payload || cast(:patch as jsonb)), version = version + 1, "
            + "updated_by = :actor "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and version = :version "
            + "and deleted_at is null returning " + COLUMNS;

    private static final String SOFT_DELETE = "update object_record "
            + "set deleted_at = now(), deleted_by = :actor, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and deleted_at is null "
            + "returning " + COLUMNS;

    private static final String RESTORE = "update object_record "
            + "set deleted_at = null, deleted_by = null, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and deleted_at is not null "
            + "returning " + COLUMNS;

    private static final String PURGE = "delete from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and deleted_at is not null";

    private static final String LIST_FIRST = "select " + COLUMNS + " from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null "
            + "order by id limit :limit";

    private static final String LIST_AFTER = "select " + COLUMNS + " from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null and id > :after "
            + "order by id limit :limit";

    private static final String BIN_FIRST = "select " + COLUMNS + " from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is not null "
            + "order by id limit :limit";

    private static final String BIN_AFTER = "select " + COLUMNS + " from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is not null and id > :after "
            + "order by id limit :limit";

    private static final String HAS_RECORDS = "select exists (select 1 from object_record "
            + "where tenant_id = :tenant and object_api_name = :object)";

    // jsonb_exists is the function behind the operator ?, which a JDBC statement cannot spell.
    private static final String HAS_VALUES = "select exists (select 1 from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and jsonb_exists(payload, :field))";

    // Not "for update skip locked": under forced row level security PostgreSQL silently matches zero rows for this
    // statement shape when a locking clause is added to the subquery (checked against a real login role, not a
    // superuser). Two instances racing on the same rows is harmless without it too: the second delete matches
    // whatever the first one left, which is nothing for a row the first one already removed.
    private static final String PURGE_OLD = "delete from object_record where tenant_id = :tenant and id in ("
            + "select id from object_record where tenant_id = :tenant and deleted_at is not null "
            + "and deleted_at < now() - make_interval(secs => :seconds) order by deleted_at limit :limit) "
            + "returning object_api_name, id";

    private static final String CHECK_VIOLATION = "23514";
    private static final String PAYLOAD_SIZE_CONSTRAINT = "object_record_payload_size";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;
    private final RecordJson json;

    JsonbRecordStorage(JdbcClient jdbc, TenantContexts contexts, RecordJson json) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.json = json;
    }

    @Override
    public DataRecord insert(String objectApiName, UUID ownerId, UUID actor, Map<String, Object> fields) {
        try {
            return jdbc.sql(INSERT).param("tenant", tenant()).param("object", objectApiName)
                    .param("owner", ownerId).param("payload", json.write(fields)).param("actor", actor)
                    .query(this::map).single();
        } catch (DataIntegrityViolationException e) {
            throw tooLarge(e);
        }
    }

    @Override
    public DataRecord find(String objectApiName, UUID id) {
        return jdbc.sql(FIND).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                .query(this::map).optional().orElse(null);
    }

    @Override
    public DataRecord update(String objectApiName, UUID id, long expectedVersion, Map<String, Object> set,
            List<String> clear, UUID actor) {
        Map<String, Object> patch = new HashMap<>(set);
        clear.forEach(name -> patch.put(name, null));
        try {
            return jdbc.sql(UPDATE).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                    .param("version", expectedVersion).param("patch", json.write(patch)).param("actor", actor)
                    .query(this::map).optional().orElse(null);
        } catch (DataIntegrityViolationException e) {
            throw tooLarge(e);
        }
    }

    @Override
    public DataRecord softDelete(String objectApiName, UUID id, UUID actor) {
        return jdbc.sql(SOFT_DELETE).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                .param("actor", actor).query(this::map).optional().orElse(null);
    }

    @Override
    public DataRecord restore(String objectApiName, UUID id, UUID actor) {
        return jdbc.sql(RESTORE).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                .param("actor", actor).query(this::map).optional().orElse(null);
    }

    @Override
    public boolean purge(String objectApiName, UUID id) {
        return jdbc.sql(PURGE).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                .update() > 0;
    }

    @Override
    public List<DataRecord> list(String objectApiName, UUID after, int limit) {
        return page(after == null ? LIST_FIRST : LIST_AFTER, objectApiName, after, limit);
    }

    @Override
    public List<DataRecord> listDeleted(String objectApiName, UUID after, int limit) {
        return page(after == null ? BIN_FIRST : BIN_AFTER, objectApiName, after, limit);
    }

    @Override
    public boolean hasRecords(String objectApiName) {
        return jdbc.sql(HAS_RECORDS).param("tenant", tenant()).param("object", objectApiName)
                .query(Boolean.class).single();
    }

    @Override
    public boolean hasValues(String objectApiName, String fieldApiName) {
        return jdbc.sql(HAS_VALUES).param("tenant", tenant()).param("object", objectApiName)
                .param("field", fieldApiName).query(Boolean.class).single();
    }

    @Override
    public List<Purged> purgeDeletedOlderThan(Duration period, int limit) {
        return jdbc.sql(PURGE_OLD).param("tenant", tenant()).param("seconds", period.toMillis() / 1000.0)
                .param("limit", limit)
                .query((rs, row) -> new Purged(rs.getString("object_api_name"), rs.getObject("id", UUID.class)))
                .list();
    }

    private List<DataRecord> page(String statement, String objectApiName, UUID after, int limit) {
        JdbcClient.StatementSpec spec = jdbc.sql(statement).param("tenant", tenant()).param("object", objectApiName)
                .param("limit", limit);
        if (after != null) {
            spec = spec.param("after", after);
        }
        return spec.query(this::map).list();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private DataRecord map(ResultSet rs, int row) throws SQLException {
        return new DataRecord(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getString("record_type"), rs.getObject("owner_id", UUID.class), json.read(rs.getString("payload")),
                rs.getLong("version"), instant(rs, "created_at"), rs.getObject("created_by", UUID.class),
                instant(rs, "updated_at"), rs.getObject("updated_by", UUID.class), instant(rs, "deleted_at"),
                rs.getObject("deleted_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    /**
     * The one check the database makes that a request can trip: the size of the values (nothing else is named). A
     * hostile object name is also refused by a database check (a different constraint, the same SQLSTATE), and that
     * is a programming mistake, not a request to report as "too large": only the size constraint is translated.
     */
    private static RuntimeException tooLarge(DataIntegrityViolationException e) {
        if (e.getMostSpecificCause() instanceof SQLException sql && CHECK_VIOLATION.equals(sql.getSQLState())
                && sql.getMessage() != null && sql.getMessage().contains(PAYLOAD_SIZE_CONSTRAINT)) {
            return ApiException.validation("fields", "The values are too large for one record.");
        }
        return e;
    }
}
