package app.platform.metadata.internal;

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
 * Every statement about change sets, their changes and the releases (ADR-0066, ADR-0067). It runs under the tenant
 * context of the transaction like the rest of the module. Nothing here touches the published definitions: a draft is
 * only ever rows of {@code metadata_change_set} and {@code metadata_change}.
 */
@Repository
class LifecycleStore {

    /** A change set; {@code releaseNumber} is null until it is published. */
    record SetRow(UUID id, String name, String description, String status, Long releaseNumber, Instant createdAt,
            long version, int changeCount) {
    }

    /** One change of a set. */
    record ChangeRow(UUID id, int position, String kind, String objectApiName, String itemApiName, String payload) {
    }

    /** A release, with the name of its change set when it has one. */
    record ReleaseRow(long number, String kind, UUID changeSetId, String changeSetName, Long undoesRelease,
            Long rolledBackBy, String summary, String undo, long metadataVersion, Instant createdAt) {
    }

    private static final String SET_COLUMNS = "s.id, s.name, s.description, s.status, s.release_number, "
            + "s.created_at, s.version, (select count(*) from metadata_change c where c.change_set_id = s.id "
            + "and c.deleted_at is null) as change_count";

    private final JdbcClient jdbc;

    LifecycleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- change sets ----

    List<SetRow> sets() {
        return jdbc.sql("select " + SET_COLUMNS + " from metadata_change_set s where s.deleted_at is null "
                        + "order by (s.status = 'DRAFT') desc, s.created_at desc, s.id desc")
                .query(LifecycleStore::setRow).list();
    }

    Optional<SetRow> set(UUID id) {
        return jdbc.sql("select " + SET_COLUMNS + " from metadata_change_set s where s.id = :id "
                        + "and s.deleted_at is null")
                .param("id", id).query(LifecycleStore::setRow).optional();
    }

    int countOpenSets() {
        return jdbc.sql("select count(*) from metadata_change_set where status = 'DRAFT' and deleted_at is null")
                .query(Integer.class).single();
    }

    boolean openSetNameTaken(String name) {
        return jdbc.sql("select exists (select 1 from metadata_change_set where lower(name) = lower(:name) "
                        + "and status = 'DRAFT' and deleted_at is null)")
                .param("name", name).query(Boolean.class).single();
    }

    UUID insertSet(String name, String description, UUID actor) {
        return jdbc.sql("insert into metadata_change_set (name, description, created_by, updated_by) "
                        + "values (:name, :description, :actor, :actor) returning id")
                .param("name", name).param("description", description).param("actor", actor)
                .query(UUID.class).single();
    }

    /** Marks the set discarded when it is still a draft with the version the caller read. */
    boolean discardSet(UUID id, long version, UUID actor) {
        return jdbc.sql("update metadata_change_set set status = 'DISCARDED', version = version + 1, "
                        + "updated_by = :actor where id = :id and version = :version and status = 'DRAFT' "
                        + "and deleted_at is null")
                .param("actor", actor).param("id", id).param("version", version).update() == 1;
    }

    /** Marks the set published by the release, when it is still a draft. */
    boolean publishSet(UUID id, long releaseNumber, UUID actor) {
        return jdbc.sql("update metadata_change_set set status = 'PUBLISHED', release_number = :release, "
                        + "version = version + 1, updated_by = :actor where id = :id and status = 'DRAFT' "
                        + "and deleted_at is null")
                .param("release", releaseNumber).param("actor", actor).param("id", id).update() == 1;
    }

    // ---- changes ----

    List<ChangeRow> changes(UUID setId) {
        return jdbc.sql("select id, position, kind, object_api_name, item_api_name, cast(payload as text) as payload "
                        + "from metadata_change where change_set_id = :set and deleted_at is null order by position")
                .param("set", setId).query(LifecycleStore::changeRow).list();
    }

    int countChanges(UUID setId) {
        return jdbc.sql("select count(*) from metadata_change where change_set_id = :set and deleted_at is null")
                .param("set", setId).query(Integer.class).single();
    }

    int nextPosition(UUID setId) {
        return jdbc.sql("select coalesce(max(position), 0) + 1 from metadata_change where change_set_id = :set")
                .param("set", setId).query(Integer.class).single();
    }

    void insertChange(UUID setId, int position, Change change, UUID actor) {
        jdbc.sql("insert into metadata_change (change_set_id, position, kind, object_api_name, item_api_name, "
                        + "payload, created_by, updated_by) values (:set, :position, :kind, :object, :item, "
                        + "cast(:payload as jsonb), :actor, :actor)")
                .param("set", setId).param("position", position).param("kind", change.kind().name())
                .param("object", change.object()).param("item", change.item()).param("payload", change.payload())
                .param("actor", actor).update();
    }

    /** Removes a change from its draft set; false when the set has no such change. */
    boolean removeChange(UUID setId, UUID changeId, UUID actor) {
        return jdbc.sql("update metadata_change set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and change_set_id = :set and deleted_at is null")
                .param("actor", actor).param("id", changeId).param("set", setId).update() == 1;
    }

    // ---- releases ----

    /** Takes the organization's lock for metadata publications: they happen one at a time. */
    void lockPublications() {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('metadata-publish:' || "
                        + "platform_current_tenant()::text, 0))")
                .query().singleRow();
    }

    long nextReleaseNumber() {
        return jdbc.sql("select coalesce(max(release_number), 0) + 1 from metadata_release")
                .query(Long.class).single();
    }

    long currentMetadataVersion() {
        return jdbc.sql("select coalesce((select version from metadata_version where deleted_at is null), 0)")
                .query(Long.class).single();
    }

    void insertRelease(long number, String kind, UUID changeSetId, Long undoesRelease, String summary, String undo,
            long metadataVersion, UUID actor) {
        jdbc.sql("insert into metadata_release (release_number, kind, change_set_id, undoes_release, summary, undo, "
                        + "metadata_version, created_by, updated_by) values (:number, :kind, :set, :undoes, "
                        + "cast(:summary as jsonb), cast(:undo as jsonb), :version, :actor, :actor)")
                .param("number", number).param("kind", kind).param("set", changeSetId).param("undoes", undoesRelease)
                .param("summary", summary).param("undo", undo).param("version", metadataVersion)
                .param("actor", actor).update();
    }

    /** Marks a release as rolled back by the given one; true when it was not rolled back before. */
    boolean markRolledBack(long number, long byRelease, UUID actor) {
        return jdbc.sql("update metadata_release set rolled_back_by = :by, version = version + 1, "
                        + "updated_by = :actor where release_number = :number and rolled_back_by is null")
                .param("by", byRelease).param("actor", actor).param("number", number).update() == 1;
    }

    List<ReleaseRow> releases(int limit) {
        return jdbc.sql("select r.release_number, r.kind, r.change_set_id, s.name as set_name, r.undoes_release, "
                        + "r.rolled_back_by, cast(r.summary as text) as summary, cast(r.undo as text) as undo, "
                        + "r.metadata_version, r.created_at from metadata_release r "
                        + "left join metadata_change_set s on s.id = r.change_set_id "
                        + "order by r.release_number desc limit :limit")
                .param("limit", limit).query(LifecycleStore::releaseRow).list();
    }

    Optional<ReleaseRow> release(long number) {
        return jdbc.sql("select r.release_number, r.kind, r.change_set_id, s.name as set_name, r.undoes_release, "
                        + "r.rolled_back_by, cast(r.summary as text) as summary, cast(r.undo as text) as undo, "
                        + "r.metadata_version, r.created_at from metadata_release r "
                        + "left join metadata_change_set s on s.id = r.change_set_id "
                        + "where r.release_number = :number")
                .param("number", number).query(LifecycleStore::releaseRow).optional();
    }

    long latestReleaseNumber() {
        return jdbc.sql("select coalesce(max(release_number), 0) from metadata_release")
                .query(Long.class).single();
    }

    // ---- row mapping ----

    private static SetRow setRow(ResultSet rs, int row) throws SQLException {
        long release = rs.getLong("release_number");
        Long releaseNumber = rs.wasNull() ? null : release;
        return new SetRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), releaseNumber,
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getLong("version"),
                rs.getInt("change_count"));
    }

    private static ChangeRow changeRow(ResultSet rs, int row) throws SQLException {
        return new ChangeRow(rs.getObject("id", UUID.class), rs.getInt("position"), rs.getString("kind"),
                rs.getString("object_api_name"), rs.getString("item_api_name"), rs.getString("payload"));
    }

    private static ReleaseRow releaseRow(ResultSet rs, int row) throws SQLException {
        long undoes = rs.getLong("undoes_release");
        Long undoesRelease = rs.wasNull() ? null : undoes;
        long by = rs.getLong("rolled_back_by");
        Long rolledBackBy = rs.wasNull() ? null : by;
        return new ReleaseRow(rs.getLong("release_number"), rs.getString("kind"),
                rs.getObject("change_set_id", UUID.class), rs.getString("set_name"), undoesRelease, rolledBackBy,
                rs.getString("summary"), rs.getString("undo"), rs.getLong("metadata_version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
