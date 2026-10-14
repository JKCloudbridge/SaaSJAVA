package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueryPlan;
import app.platform.data.SlotCopy;
import app.platform.data.StorageStrategy;
import app.platform.data.UniqueValueTakenException;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * <p>The typed copies of indexed fields ("slots") are written in the same statement as the record (ADR-0102); the
 * statements that fill and empty them for records that already exist are here too, because only this class names the
 * table.
 */
@Repository
class JsonbRecordStorage implements StorageStrategy {

    private static final String COLUMNS = "id, object_api_name, record_type, owner_id, "
            + "cast(payload as text) as payload, version, created_at, created_by, updated_at, updated_by, "
            + "deleted_at, deleted_by";

    private static final String SLOT_COLUMNS = Arrays.stream(SlotColumn.values()).map(SlotColumn::column)
            .collect(Collectors.joining(", "));

    private static final String SLOT_VALUES = Arrays.stream(SlotColumn.values())
            .map(slot -> slot.fromParameter(slot.column())).collect(Collectors.joining(", "));

    private static final String SLOT_ASSIGNMENTS = Arrays.stream(SlotColumn.values())
            .map(slot -> slot.column() + " = " + slot.fromParameter(slot.column()))
            .collect(Collectors.joining(", "));

    private static final String INSERT = "insert into object_record "
            + "(tenant_id, object_api_name, record_type, owner_id, payload, created_by, updated_by, unique_mask, "
            + SLOT_COLUMNS + ") values (:tenant, :object, cast(:type as text), :owner, cast(:payload as jsonb), "
            + ":actor, :actor, :umask, " + SLOT_VALUES + ") returning " + COLUMNS;

    private static final String FIND = "select " + COLUMNS + " from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id";

    // A null in the patch removes the field: the merge puts the null in, and stripping the nulls takes the key out.
    private static final String UPDATE = "update object_record "
            + "set payload = jsonb_strip_nulls(payload || cast(:patch as jsonb)), record_type = cast(:type as text), "
            + SLOT_ASSIGNMENTS + ", unique_mask = :umask, version = version + 1, "
            + "updated_by = :actor "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and version = :version "
            + "and deleted_at is null returning " + COLUMNS;

    // The identifiers are values (the list is expanded into parameters); the object is a parameter too.
    private static final String LIVE_IDS = "select id from object_record where tenant_id = :tenant "
            + "and object_api_name = :object and deleted_at is null and id in (:ids)";

    private static final String LIVE_RECORDS = "select " + COLUMNS + " from object_record where tenant_id = :tenant "
            + "and object_api_name = :object and deleted_at is null and id in (:ids)";

    // The owner of a record changes only through this statement; the version rises like for any change.
    private static final String CHANGE_OWNER = "update object_record set owner_id = :owner, version = version + 1, "
            + "updated_by = :actor where tenant_id = :tenant and object_api_name = :object and id = :id "
            + "and version = :version and deleted_at is null returning " + COLUMNS;

    private static final String MATCHES = "select exists (select 1 from object_record where tenant_id = :tenant "
            + "and object_api_name = :object and id = :id and deleted_at is null and (";

    private static final String SOFT_DELETE = "update object_record "
            + "set deleted_at = now(), deleted_by = :actor, version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and deleted_at is null "
            + "returning " + COLUMNS;

    private static final String SOFT_DELETE_WITH = "update object_record "
            + "set deleted_at = now(), deleted_by = :actor, deleted_with = :with, version = version + 1, "
            + "updated_by = :actor "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and deleted_at is null "
            + "returning " + COLUMNS;

    private static final String REMOVED_WITH = "select object_api_name, id from object_record "
            + "where tenant_id = :tenant and deleted_with = :with and deleted_at is not null limit :limit";

    // The records are locked (for share) while their pointers are read: a change that runs at the same time either
    // finishes first and is read, or waits, so a pointer that is no longer true is never written. The pointers of a
    // record are found by the unique index of record_link: a record that has one already is left alone.
    private static final String BACKFILL_LINKS = "with batch as (select id, payload from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null and id > :after "
            + "order by id limit :limit for share), written as (insert into record_link (tenant_id, source_object, "
            + "source_record, field_api_name, target_object, target_record, created_by, updated_by) "
            + "select :tenant, :object, id, :field, :target, cast(payload ->> cast(:field as text) as uuid), "
            + ":actor, :actor from batch where pg_input_is_valid(payload ->> cast(:field as text), 'uuid') "
            + "on conflict (tenant_id, source_record, field_api_name) where deleted_at is null do nothing "
            + "returning 1) select (select count(*) from batch) as processed, "
            + "(select id from batch order by id desc limit 1) as last_id";

    private static final String RESTORE = "update object_record "
            + "set deleted_at = null, deleted_by = null, deleted_with = null, version = version + 1, "
            + "updated_by = :actor, "
            + SLOT_ASSIGNMENTS + ", unique_mask = :umask "
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

    // No "for update skip locked": it is not needed (two instances racing on the same rows is harmless: the second
    // delete matches nothing of what the first one already removed), and in a stand-alone test on a table with the
    // same forced row level security and a real login role the statement with the clause deleted nothing.
    private static final String PURGE_OLD = "delete from object_record where tenant_id = :tenant and id in ("
            + "select id from object_record where tenant_id = :tenant and deleted_at is not null "
            + "and deleted_at < now() - make_interval(secs => :seconds) order by deleted_at limit :limit) "
            + "returning object_api_name, id";


    private static final UUID NIL = new UUID(0L, 0L);

    // Fills a slot in a batch: the next records in identifier order, the copy computed by the database from the values
    // (the same conversion a write uses). Only records whose copy differs are written, so a record that carried its own
    // copy (written meanwhile) costs nothing. The batch reports what it looked at, written or not.
    private static final Map<SlotColumn, String> FILL = statements(slot -> "with batch as (select id "
            + "from object_record where tenant_id = :tenant and object_api_name = :object and deleted_at is null "
            + "and id > :after order by id limit :limit), written as (update object_record r set " + slot.column()
            + " = " + slot.fromPayload("field") + " from batch where r.id = batch.id and r.tenant_id = :tenant "
            + "and r." + slot.column() + " is distinct from " + slot.fromPayload("field") + " returning r.id) "
            + "select (select count(*) from batch) as processed, "
            + "(select id from batch order by id desc limit 1) as last_id");

    // Emptying a slot also switches off every uniqueness constraint that covers it: a slot is never reused with a bit
    // of the field before it still set (ADR-0111).
    private static final Map<SlotColumn, String> CLEAR = statements(slot -> "with batch as (select id "
            + "from object_record where tenant_id = :tenant and object_api_name = :object and deleted_at is null "
            + "and (" + slot.column() + " is not null or (unique_mask & " + UniqueKey.maskTouching(slot)
            + ") <> 0) limit :limit) update object_record r set " + slot.column() + " = null, unique_mask = "
            + "r.unique_mask & " + ~UniqueKey.maskTouching(slot)
            + " from batch where r.id = batch.id and r.tenant_id = :tenant");

    private static final Map<SlotColumn, String> UNFILLED = statements(slot -> "select exists (select 1 "
            + "from object_record where tenant_id = :tenant and object_api_name = :object and deleted_at is null "
            + "and " + slot.column() + " is distinct from " + slot.fromPayload("field") + ")");

    private static final Map<SlotColumn, String> HAS_SLOT_VALUES = statements(slot -> "select exists (select 1 "
            + "from object_record where tenant_id = :tenant and object_api_name = :object and deleted_at is null "
            + "and (" + slot.column() + " is not null or (unique_mask & " + UniqueKey.maskTouching(slot)
            + ") <> 0))");

    // ---- uniqueness (ADR-0111): one constant statement per constraint, built from the fixed list of constraints ----

    private static String all(UniqueKey key, String prefix) {
        return key.columns().stream().map(column -> prefix + column.column() + " is not null")
                .collect(Collectors.joining(" and "));
    }

    private static Map<Integer, String> keyed(Function<UniqueKey, String> builder) {
        Map<Integer, String> built = new HashMap<>();
        for (UniqueKey key : UniqueKey.every()) {
            built.put(key.bit(), builder.apply(key));
        }
        return Map.copyOf(built);
    }

    // The values are parameters v0 and v1, converted the way a write converts them; the record being written may keep
    // its own value (the identifier of a record that does not exist yet is the nil identifier, which no record has).
    private static final Map<Integer, String> VALUE_TAKEN = keyed(key -> "select exists (select 1 from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null and id <> :except and "
            + IntStream.range(0, key.columns().size())
                    .mapToObj(index -> key.columns().get(index).column() + " = "
                            + key.columns().get(index).fromParameter("v" + index))
                    .collect(Collectors.joining(" and ")) + ")");

    // The next records in identifier order get their bit. Only records that have a copy in every column of the
    // constraint, whose bit is not set yet. A batch that meets two equal values fails as a whole (23505).
    private static final Map<Integer, String> MASK = keyed(key -> "with batch as (select id from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null and id > :after "
            + "order by id limit :limit), written as (update object_record r set unique_mask = r.unique_mask | "
            + key.mask() + " from batch where r.id = batch.id and r.tenant_id = :tenant and (r.unique_mask & "
            + key.mask() + ") = 0 and " + all(key, "r.") + " returning r.id) "
            + "select (select count(*) from batch) as processed, "
            + "(select id from batch order by id desc limit 1) as last_id, (select count(*) from written) as written");

    private static final Map<Integer, String> UNMASKED = keyed(key -> "select exists (select 1 from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null and " + all(key, "")
            + " and (unique_mask & " + key.mask() + ") = 0)");

    private static final Map<Integer, String> COLLIDING = keyed(key -> "select coalesce(sum(n), 0) from (select "
            + "count(*) as n from object_record where tenant_id = :tenant and object_api_name = :object "
            + "and deleted_at is null and " + all(key, "") + " group by "
            + key.columns().stream().map(SlotColumn::column).collect(Collectors.joining(", "))
            + " having count(*) > 1) groups");

    // The records that share a value of the field, from the stored values, in the conversion of the slot's kind
    // (a number is compared as a number, so 1.0 and 1.00 are the same value).
    private static final Map<SlotColumn, String> DUPLICATES = statements(slot -> "select coalesce(sum(n), 0) from "
            + "(select count(*) as n from object_record where tenant_id = :tenant and object_api_name = :object "
            + "and deleted_at is null and " + slot.fromPayload("field") + " is not null group by "
            + slot.fromPayload("field") + " having count(*) > 1) groups");

    // The recalculation: only the formula results of the payload change. The setting that lets the database accept it
    // without a new version is switched on before and off after, in the transaction of the caller.
    private static final String CALCULATED_ON = "select set_config('platform.record_recalculation', 'on', true)";

    private static final String CALCULATED_OFF = "select set_config('platform.record_recalculation', 'off', true)";

    private static final String CALCULATED = "update object_record "
            + "set payload = jsonb_strip_nulls(payload || cast(:patch as jsonb)) "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and version = :version "
            + "and deleted_at is null";

    private static final String LIVE_COUNT = "select count(*) from (select 1 from object_record "
            + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null limit :cap) live";

    private static Map<SlotColumn, String> statements(Function<SlotColumn, String> builder) {
        Map<SlotColumn, String> built = new EnumMap<>(SlotColumn.class);
        for (SlotColumn slot : SlotColumn.values()) {
            built.put(slot, builder.apply(slot));
        }
        return Map.copyOf(built);
    }

    private static final String UNIQUE_VIOLATION = "23505";
    private static final Pattern UNIQUE_INDEX = Pattern
            .compile("object_record_u_(p_ref_[1-4]_ref_[1-4]|s_[a-z]+_[1-8])");
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
    public DataRecord insert(String objectApiName, String recordType, UUID ownerId, UUID actor,
            Map<String, Object> fields, SlotCopy slots) {
        try {
            return slotted(jdbc.sql(INSERT), slots).param("tenant", tenant()).param("object", objectApiName)
                    .param("type", recordType).param("owner", ownerId).param("payload", json.write(fields))
                    .param("actor", actor)
                    .query(this::map).single();
        } catch (DataIntegrityViolationException e) {
            throw refused(e);
        }
    }

    @Override
    public DataRecord find(String objectApiName, UUID id) {
        return jdbc.sql(FIND).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                .query(this::map).optional().orElse(null);
    }

    @Override
    public DataRecord update(String objectApiName, UUID id, long expectedVersion, String recordType,
            Map<String, Object> set, List<String> clear, UUID actor, SlotCopy slots) {
        Map<String, Object> patch = new HashMap<>(set);
        clear.forEach(name -> patch.put(name, null));
        try {
            return slotted(jdbc.sql(UPDATE), slots).param("tenant", tenant()).param("object", objectApiName)
                    .param("id", id).param("version", expectedVersion).param("type", recordType)
                    .param("patch", json.write(patch)).param("actor", actor)
                    .query(this::map).optional().orElse(null);
        } catch (DataIntegrityViolationException e) {
            throw refused(e);
        }
    }

    @Override
    public Set<UUID> liveIds(String objectApiName, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(LIVE_IDS).param("tenant", tenant()).param("object", objectApiName)
                .param("ids", List.copyOf(ids)).query(UUID.class).list());
    }

    @Override
    public DataRecord restore(String objectApiName, UUID id, UUID actor, SlotCopy slots) {
        try {
            return slotted(jdbc.sql(RESTORE), slots).param("tenant", tenant()).param("object", objectApiName)
                    .param("id", id).param("actor", actor).query(this::map).optional().orElse(null);
        } catch (DataIntegrityViolationException e) {
            throw refused(e);
        }
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

    // ---- links between records (ADR-0106) ----

    @Override
    public DataRecord softDelete(String objectApiName, UUID id, UUID actor, UUID removedWith) {
        if (removedWith == null) {
            return jdbc.sql(SOFT_DELETE).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                    .param("actor", actor).query(this::map).optional().orElse(null);
        }
        return jdbc.sql(SOFT_DELETE_WITH).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                .param("actor", actor).param("with", removedWith).query(this::map).optional().orElse(null);
    }

    @Override
    public List<Removed> removedWith(UUID removedWith, int limit) {
        return jdbc.sql(REMOVED_WITH).param("tenant", tenant()).param("with", removedWith).param("limit", limit)
                .query((rs, row) -> new Removed(rs.getString("object_api_name"), rs.getObject("id", UUID.class)))
                .list();
    }

    @Override
    public Filled backfillLinks(String objectApiName, String fieldApiName, String targetObject, UUID after,
            int limit, UUID actor) {
        return jdbc.sql(BACKFILL_LINKS).param("tenant", tenant()).param("object", objectApiName)
                .param("field", fieldApiName).param("target", targetObject)
                .param("after", after == null ? NIL : after).param("limit", limit).param("actor", actor)
                .query((rs, row) -> new Filled(rs.getInt("processed"), rs.getObject("last_id", UUID.class)))
                .single();
    }

    // ---- queries (ADR-0100) ----

    @Override
    public List<StoredRow> query(QueryPlan plan) {
        JsonbQuerySql.Built built = JsonbQuerySql.build(plan);
        List<StoredRow> rows = new ArrayList<>();
        for (JsonbQuerySql.Phase phase : built.phases()) {
            int wanted = plan.limit() - rows.size();
            if (wanted <= 0) {
                break;
            }
            String statement = "select " + COLUMNS + built.sortSelect() + " from object_record "
                    + "where tenant_id = :tenant and object_api_name = :object and deleted_at is null and "
                    + built.filter() + " and (" + phase.where() + ") order by " + phase.order() + " limit :limit";
            JdbcClient.StatementSpec spec = jdbc.sql(statement).param("tenant", tenant())
                    .param("object", plan.objectApiName()).param("limit", wanted);
            for (Map.Entry<String, Object> parameter : built.params().entrySet()) {
                spec = spec.param(parameter.getKey(), parameter.getValue());
            }
            int keys = plan.order().size();
            rows.addAll(spec.query((rs, row) -> new StoredRow(map(rs, row), sortValues(rs, keys))).list());
        }
        return rows;
    }

    @Override
    public List<DataRecord> liveRecords(String objectApiName, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(LIVE_RECORDS).param("tenant", tenant()).param("object", objectApiName)
                .param("ids", List.copyOf(ids)).query(this::map).list();
    }


    // ---- search (ADR-0124) ----

    @Override
    public List<ScoredRecord> search(SearchPlan plan) {
        SearchStatement built = searchStatement(plan);
        JdbcClient.StatementSpec spec = withParameters(jdbc.sql(built.sql()).param("tenant", tenant())
                .param("object", plan.objectApiName()).param("limit", plan.limit()), built.params());
        return spec.query((rs, row) -> new ScoredRecord(map(rs, row), rs.getInt("score"))).list();
    }

    /** The text of a search and its parameters, except the organization, the object and the page size. */
    record SearchStatement(String sql, Map<String, Object> params) {
    }

    /**
     * The statement of a search: the records that have every word, the rarest word driving, the others checked as
     * semi-joins, then the record security condition. Static so that the benchmark measures this very text.
     */
    static SearchStatement searchStatement(SearchPlan plan) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder inner = new StringBuilder("select a.record_id as record_id, max(case a.field_api_name");
        for (int index = 0; index < plan.fields().size(); index++) {
            inner.append(" when :f").append(index).append(" then ").append(Math.max(1, 4 - index));
            params.put("f" + index, plan.fields().get(index));
        }
        inner.append(" else 1 end) as score from search_term a where a.tenant_id = :tenant and ")
                .append("a.object_api_name = :object and ").append(wordCondition("a", "d", plan.driving(), params))
                .append(" and a.field_api_name = any(cast(:fields as text[]))");
        for (int index = 0; index < plan.others().size(); index++) {
            String alias = "b" + index;
            inner.append(" and exists (select 1 from search_term ").append(alias).append(" where ").append(alias)
                    .append(".tenant_id = a.tenant_id and ").append(alias)
                    .append(".object_api_name = a.object_api_name")
                    .append(" and ").append(alias).append(".record_id = a.record_id and ")
                    .append(wordCondition(alias, "w" + index, plan.others().get(index), params)).append(" and ")
                    .append(alias).append(".field_api_name = any(cast(:fields as text[])))");
        }
        inner.append(" group by a.record_id");
        params.put("fields", SearchStore.array(plan.fields()));
        StringBuilder statement = new StringBuilder("select " + COLUMNS + ", m.score as score from (")
                .append(inner).append(") m join object_record r on r.id = m.record_id where r.tenant_id = :tenant ")
                .append("and r.object_api_name = :object and r.deleted_at is null");
        if (plan.constraint() != null) {
            JsonbQuerySql.Condition where = JsonbQuerySql.condition(plan.constraint());
            statement.append(" and (").append(where.sql()).append(")");
            params.putAll(where.params());
        }
        if (plan.afterScore() != null) {
            statement.append(" and (m.score < :afterScore or (m.score = :afterScore and r.id > :afterId))");
            params.put("afterScore", plan.afterScore());
            params.put("afterId", plan.afterId());
        }
        statement.append(" order by m.score desc, r.id limit :limit");
        return new SearchStatement(statement.toString(), params);
    }

    /** The condition on a word: the whole word, or the range of words that begin with it. Only parameters. */
    private static String wordCondition(String alias, String name, SearchWord word, Map<String, Object> params) {
        params.put(name, word.text());
        if (!word.beginning()) {
            return alias + ".term = :" + name;
        }
        params.put(name + "u", word.upperBound());
        return "(" + alias + ".term >= :" + name + " and " + alias + ".term < :" + name + "u)";
    }

    // ---- record-level security (ADR-0115, ADR-0119) ----

    @Override
    public DataRecord find(String objectApiName, UUID id, QueryPlan.Node condition) {
        JsonbQuerySql.Condition where = JsonbQuerySql.condition(condition);
        return withParameters(jdbc.sql(FIND + " and (" + where.sql() + ")").param("tenant", tenant())
                .param("object", objectApiName).param("id", id), where.params())
                .query(this::map).optional().orElse(null);
    }

    @Override
    public boolean matches(String objectApiName, UUID id, QueryPlan.Node condition) {
        JsonbQuerySql.Condition where = JsonbQuerySql.condition(condition);
        return withParameters(jdbc.sql(MATCHES + where.sql() + "))").param("tenant", tenant())
                .param("object", objectApiName).param("id", id), where.params())
                .query(Boolean.class).single();
    }

    @Override
    public List<DataRecord> liveRecords(String objectApiName, Collection<UUID> ids, QueryPlan.Node condition) {
        if (ids.isEmpty()) {
            return List.of();
        }
        JsonbQuerySql.Condition where = JsonbQuerySql.condition(condition);
        return withParameters(jdbc.sql(LIVE_RECORDS + " and (" + where.sql() + ")").param("tenant", tenant())
                .param("object", objectApiName).param("ids", List.copyOf(ids)), where.params())
                .query(this::map).list();
    }

    @Override
    public DataRecord changeOwner(String objectApiName, UUID id, long expectedVersion, UUID newOwner, UUID actor) {
        return jdbc.sql(CHANGE_OWNER).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                .param("version", expectedVersion).param("owner", newOwner).param("actor", actor)
                .query(this::map).optional().orElse(null);
    }

    private static JdbcClient.StatementSpec withParameters(JdbcClient.StatementSpec spec,
            Map<String, Object> parameters) {
        JdbcClient.StatementSpec bound = spec;
        for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
            bound = bound.param(parameter.getKey(), parameter.getValue());
        }
        return bound;
    }

    /** The statement of an update, for the benchmark harness (it measures the statement the application sends). */
    static String updateStatement() {
        return UPDATE;
    }

    /** The statement of an insert, for the benchmark harness. */
    static String insertStatement() {
        return INSERT;
    }

    private static List<String> sortValues(ResultSet rs, int keys) throws SQLException {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < keys; index++) {
            values.add(rs.getString("sort_" + index));
        }
        return values;
    }

    // ---- the maintenance of the typed copies (ADR-0101, ADR-0102) ----

    @Override
    public Filled fillSlot(String objectApiName, String fieldApiName, String slotColumn, UUID after, int limit) {
        SlotColumn slot = slot(slotColumn);
        JdbcClient.StatementSpec spec = jdbc.sql(FILL.get(slot)).param("tenant", tenant())
                .param("object", objectApiName).param("field", fieldApiName)
                .param("after", after == null ? NIL : after).param("limit", limit);
        return spec.query((rs, row) -> new Filled(rs.getInt("processed"), rs.getObject("last_id", UUID.class)))
                .single();
    }

    @Override
    public int clearSlot(String objectApiName, String slotColumn, int limit) {
        return jdbc.sql(CLEAR.get(slot(slotColumn))).param("tenant", tenant()).param("object", objectApiName)
                .param("limit", limit).update();
    }

    @Override
    public boolean hasUnfilledSlot(String objectApiName, String fieldApiName, String slotColumn) {
        return jdbc.sql(UNFILLED.get(slot(slotColumn))).param("tenant", tenant()).param("object", objectApiName)
                .param("field", fieldApiName).query(Boolean.class).single();
    }

    @Override
    public boolean hasSlotValues(String objectApiName, String slotColumn) {
        return jdbc.sql(HAS_SLOT_VALUES.get(slot(slotColumn))).param("tenant", tenant())
                .param("object", objectApiName).query(Boolean.class).single();
    }

    @Override
    public int liveCountUpTo(String objectApiName, int cap) {
        return jdbc.sql(LIVE_COUNT).param("tenant", tenant()).param("object", objectApiName).param("cap", cap)
                .query(Integer.class).single();
    }

    /** The slot with this column name: the name must come from the index policy, never from a request. */
    private static SlotColumn slot(String column) {
        return SlotColumn.byColumn(column).orElseThrow(() -> new IllegalArgumentException("not a slot column"));
    }

    /**
     * Adds the parameters of the typed copies of a write: the twenty-two slots (the copies given, and an empty value
     * for every other slot) and the mask of the uniqueness constraints the record takes part in.
     */
    private static JdbcClient.StatementSpec slotted(JdbcClient.StatementSpec spec, SlotCopy slots) {
        JdbcClient.StatementSpec bound = spec.param("umask", slots.uniqueMask());
        for (SlotColumn slot : SlotColumn.values()) {
            bound = bound.param(slot.column(), slots.columns().get(slot.column()));
        }
        return bound;
    }

    // ---- uniqueness (ADR-0111) ----

    @Override
    public long duplicateRecords(String objectApiName, String fieldApiName, String slotColumn) {
        return jdbc.sql(DUPLICATES.get(slot(slotColumn))).param("tenant", tenant()).param("object", objectApiName)
                .param("field", fieldApiName).query(Long.class).single();
    }

    @Override
    public boolean valueTaken(String objectApiName, int constraint, List<String> values, UUID except) {
        UniqueKey key = constraint(constraint);
        JdbcClient.StatementSpec spec = jdbc.sql(VALUE_TAKEN.get(key.bit())).param("tenant", tenant())
                .param("object", objectApiName).param("except", except == null ? NIL : except);
        for (int index = 0; index < key.columns().size(); index++) {
            spec = spec.param("v" + index, values.get(index));
        }
        return spec.query(Boolean.class).single();
    }

    @Override
    public Filled maskUnique(String objectApiName, int constraint, UUID after, int limit) {
        UniqueKey key = constraint(constraint);
        try {
            return jdbc.sql(MASK.get(key.bit())).param("tenant", tenant()).param("object", objectApiName)
                    .param("after", after == null ? NIL : after).param("limit", limit)
                    .query((rs, row) -> new Filled(rs.getInt("processed"), rs.getObject("last_id", UUID.class)))
                    .single();
        } catch (DataIntegrityViolationException e) {
            throw refused(e);
        }
    }

    @Override
    public boolean hasUnmasked(String objectApiName, int constraint) {
        return jdbc.sql(UNMASKED.get(constraint(constraint).bit())).param("tenant", tenant())
                .param("object", objectApiName).query(Boolean.class).single();
    }

    @Override
    public long collidingRecords(String objectApiName, int constraint) {
        return jdbc.sql(COLLIDING.get(constraint(constraint).bit())).param("tenant", tenant())
                .param("object", objectApiName).query(Long.class).single();
    }

    /** The constraint with this number: it comes from the enforcement table, never from a request. */
    private static UniqueKey constraint(int bit) {
        return UniqueKey.ofBit(bit).orElseThrow(() -> new IllegalArgumentException("not a constraint"));
    }

    // ---- the recalculation of stored formula results (ADR-0113) ----

    @Override
    public boolean updateCalculated(String objectApiName, UUID id, long expectedVersion, Map<String, Object> set,
            List<String> clear) {
        Map<String, Object> patch = new HashMap<>(set);
        clear.forEach(name -> patch.put(name, null));
        jdbc.sql(CALCULATED_ON).query(String.class).single();
        int written = jdbc.sql(CALCULATED).param("tenant", tenant()).param("object", objectApiName).param("id", id)
                .param("version", expectedVersion).param("patch", json.write(patch)).update();
        jdbc.sql(CALCULATED_OFF).query(String.class).single();
        return written == 1;
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
     * What a request can trip in the database: a value that another live record holds in a unique field (the number of
     * the constraint goes on, never the database's own message, which quotes the value), or the size of the values.
     */
    private static RuntimeException refused(DataIntegrityViolationException e) {
        UniqueKey taken = uniqueKeyOf(e);
        return taken != null ? new UniqueValueTakenException(taken.bit()) : tooLarge(e);
    }

    /** The constraint a unique violation was about, or null when the failure was another one. */
    private static UniqueKey uniqueKeyOf(DataIntegrityViolationException e) {
        if (e.getMostSpecificCause() instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())
                && sql.getMessage() != null) {
            Matcher index = UNIQUE_INDEX.matcher(sql.getMessage());
            if (index.find()) {
                return UniqueKey.ofIndex(index.group()).orElse(null);
            }
        }
        return null;
    }

    /**
     * The size of the values (nothing else is named). A hostile object name is also refused by a database check (a
     * different constraint, the same SQLSTATE), and that is a programming mistake, not a request to report as "too
     * large": only the size constraint is translated.
     */
    private static RuntimeException tooLarge(DataIntegrityViolationException e) {
        if (e.getMostSpecificCause() instanceof SQLException sql && CHECK_VIOLATION.equals(sql.getSQLState())
                && sql.getMessage() != null && sql.getMessage().contains(PAYLOAD_SIZE_CONSTRAINT)) {
            return ApiException.validation("fields", "The values are too large for one record.");
        }
        return e;
    }
}
