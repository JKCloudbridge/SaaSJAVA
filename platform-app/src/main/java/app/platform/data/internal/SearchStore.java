package app.platform.data.internal;

import app.platform.tenant.TenantContexts;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The words of the searchable fields and the state of each field's words (Sprint 17, ADR-0124): the tables
 * {@code search_term} and {@code search_field}. It never reads a record; whether the records the words point at are
 * alive, and which of them a member may see, is decided by the statement that reads them ({@code JsonbRecordStorage}).
 * A word is typed text of the organization: it is a parameter and is never logged.
 */
@Repository
class SearchStore {

    /** Where the words of one field stand. */
    enum State { FILLING, FILLED }

    /**
     * The state of the words of one field.
     *
     * @param id the row
     * @param objectApiName the object
     * @param fieldApiName the field
     * @param state where it stands
     * @param cursor the last record the fill looked at, or null
     * @param recordsDone how many records the fill has looked at
     */
    record Field(UUID id, String objectApiName, String fieldApiName, State state, UUID cursor, long recordsDone) {
    }

    private static final String COLUMNS = "id, object_api_name, field_api_name, state, cursor_id, records_done";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    SearchStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    // ---- the words ----

    /** Replaces the words of one field of one record by these (none removes them). */
    void replace(String object, UUID record, String field, Set<String> words, UUID actor) {
        jdbc.sql("delete from search_term where tenant_id = :tenant and record_id = :record "
                        + "and field_api_name = :field and term <> all(cast(:keep as text[]))")
                .param("tenant", tenant()).param("record", record).param("field", field)
                .param("keep", array(words)).update();
        for (String word : words) {
            jdbc.sql("insert into search_term (tenant_id, object_api_name, record_id, field_api_name, term, "
                            + "created_by, updated_by) values (:tenant, :object, :record, :field, :term, :actor, "
                            + ":actor) on conflict (tenant_id, record_id, field_api_name, term) where deleted_at is "
                            + "null do nothing")
                    .param("tenant", tenant()).param("object", object).param("record", record)
                    .param("field", field).param("term", word).param("actor", actor).update();
        }
    }

    /**
     * About how many records an object has with a word, not counting more than the cap: the statement reads at most
     * {@code cap + 1} index entries, so asking is cheap whatever the word.
     *
     * @param fields the fields the words may be in
     * @param beginning whether the word is a beginning (every word that starts with it) or the whole word
     * @param upper the upper bound of the range for a beginning
     */
    int count(String object, String word, boolean beginning, String upper, Collection<String> fields, int cap) {
        String condition = beginning ? "term >= :word and term < :upper" : "term = :word";
        var spec = jdbc.sql("select count(*) from (select 1 from search_term where tenant_id = :tenant and "
                        + "object_api_name = :object and " + condition + " and field_api_name = any(cast(:fields as "
                        + "text[])) limit :cap) found")
                .param("tenant", tenant()).param("object", object).param("word", word)
                .param("fields", array(fields)).param("cap", cap + 1);
        return (beginning ? spec.param("upper", upper) : spec).query(Integer.class).single();
    }

    /** Removes the words of the fields of an object that are not in the list (their flag was taken off), in a batch. */
    int removeOtherFields(String object, Collection<String> keep, int limit) {
        return jdbc.sql("delete from search_term where tenant_id = :tenant and id in (select id from search_term "
                        + "where tenant_id = :tenant and object_api_name = :object and field_api_name <> "
                        + "all(cast(:keep as text[])) limit :limit)")
                .param("tenant", tenant()).param("object", object).param("keep", array(keep)).param("limit", limit)
                .update();
    }

    /** Removes the words of every field of an object that is no longer known at all (the object is gone). */
    int removeObject(String object, int limit) {
        return jdbc.sql("delete from search_term where tenant_id = :tenant and id in (select id from search_term "
                        + "where tenant_id = :tenant and object_api_name = :object limit :limit)")
                .param("tenant", tenant()).param("object", object).param("limit", limit).update();
    }

    // ---- the state of the words of a field ----

    List<Field> fields() {
        return jdbc.sql("select " + COLUMNS + " from search_field where tenant_id = :tenant and deleted_at is null "
                        + "order by object_api_name, field_api_name")
                .param("tenant", tenant()).query((rs, row) -> field(rs)).list();
    }

    Optional<Field> field(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from search_field where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("id", id).query((rs, row) -> field(rs)).optional();
    }

    /** Notes a searchable field whose words are to be filled (nothing happens when it is noted already). */
    void ensureField(String object, String field, UUID actor) {
        jdbc.sql("insert into search_field (tenant_id, object_api_name, field_api_name, created_by, updated_by) "
                        + "values (:tenant, :object, :field, :actor, :actor) on conflict (tenant_id, object_api_name, "
                        + "field_api_name) where deleted_at is null do nothing")
                .param("tenant", tenant()).param("object", object).param("field", field).param("actor", actor)
                .update();
    }

    /** Forgets the field (its flag was taken off). */
    void forgetField(UUID id) {
        jdbc.sql("delete from search_field where tenant_id = :tenant and id = :id")
                .param("tenant", tenant()).param("id", id).update();
    }

    void progress(UUID id, UUID cursor, long done, UUID actor) {
        jdbc.sql("update search_field set cursor_id = :cursor, records_done = records_done + :done, version = "
                        + "version + 1, updated_by = :actor where tenant_id = :tenant and id = :id")
                .param("cursor", cursor).param("done", done).param("actor", actor).param("tenant", tenant())
                .param("id", id).update();
    }

    void filled(UUID id, UUID actor) {
        jdbc.sql("update search_field set state = 'FILLED', version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and state = 'FILLING'")
                .param("actor", actor).param("tenant", tenant()).param("id", id).update();
    }

    /** One batch of one field at a time across instances: false when another instance holds it. */
    boolean tryWorkLock(String object, String field) {
        return jdbc.sql("select pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "search:" + tenant() + ":" + object + ":" + field).query(Boolean.class).single();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    /** A list of texts as the text of a text array (the elements are quoted and escaped). */
    static String array(Collection<String> values) {
        StringBuilder text = new StringBuilder("{");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return text.append('}').toString();
    }

    private static Field field(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Field(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getString("field_api_name"), State.valueOf(rs.getString("state")),
                rs.getObject("cursor_id", UUID.class), rs.getLong("records_done"));
    }
}
