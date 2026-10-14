package app.platform.data.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The metadata of the files of records and the queue of bytes to remove (Sprint 17, ADR-0120, ADR-0121). Every
 * statement is a constant with parameters; the organization is a parameter taken from the tenant context in addition
 * to the forced row level security of the tables. The name of a file is typed text: it is read and written here and
 * nowhere logged.
 */
@Repository
class FileStore {

    /** A file as stored. */
    record FileRow(UUID id, String object, UUID recordId, String name, String contentType, long size, String sha256,
            String storageKey, String state, Instant createdAt, UUID createdBy) {

        /** Prints identifiers only: the name is typed text. */
        @Override
        public String toString() {
            return "FileRow[" + id + "]";
        }
    }

    private static final String COLUMNS = "id, object_api_name, record_id, name, content_type, size_bytes, sha256, "
            + "storage_key, state, created_at, created_by";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    FileStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** The files of the record that are stored, oldest first. */
    List<FileRow> active(UUID recordId) {
        return jdbc.sql("select " + COLUMNS + " from record_file where tenant_id = :tenant and record_id = :record "
                        + "and state = 'ACTIVE' and deleted_at is null order by created_at, id")
                .param("tenant", tenant()).param("record", recordId).query(FileStore::map).list();
    }

    Optional<FileRow> activeFile(UUID recordId, UUID fileId) {
        return jdbc.sql("select " + COLUMNS + " from record_file where tenant_id = :tenant and record_id = :record "
                        + "and id = :id and state = 'ACTIVE' and deleted_at is null")
                .param("tenant", tenant()).param("record", recordId).param("id", fileId)
                .query(FileStore::map).optional();
    }

    /** Takes the lock that serializes the uploads of one record, so that the limit holds when two arrive together. */
    void lockRecord(UUID recordId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "files:" + recordId)
                .query().listOfRows();
    }

    /** Files of the record that count against its limit: the stored ones and the uploads still within their time. */
    int open(UUID recordId, Duration pendingTtl) {
        return jdbc.sql("select count(*) from record_file where tenant_id = :tenant and record_id = :record "
                        + "and deleted_at is null and (state = 'ACTIVE' or created_at > now() - make_interval(secs "
                        + "=> :seconds))")
                .param("tenant", tenant()).param("record", recordId)
                .param("seconds", pendingTtl.toMillis() / 1000.0).query(Integer.class).single();
    }

    /** Starts a file: the row exists before the first byte is written. */
    FileRow insertPending(UUID id, String object, UUID recordId, String name, String contentType, String key,
            UUID actor) {
        return jdbc.sql("insert into record_file (id, tenant_id, object_api_name, record_id, name, content_type, "
                        + "storage_key, created_by, updated_by) values (:id, :tenant, :object, :record, :name, "
                        + ":type, :key, :actor, :actor) returning " + COLUMNS)
                .param("id", id).param("tenant", tenant()).param("object", object).param("record", recordId)
                .param("name", name).param("type", contentType).param("key", key).param("actor", actor)
                .query(FileStore::map).single();
    }

    /** The bytes are stored: the file becomes visible. False when the row is gone (the upload was cleaned up). */
    boolean activate(UUID id, long size, String sha256, UUID actor) {
        return jdbc.sql("update record_file set state = 'ACTIVE', size_bytes = :size, sha256 = :sha, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and state = 'PENDING' and deleted_at is null")
                .param("size", size).param("sha", sha256).param("actor", actor).param("tenant", tenant())
                .param("id", id).update() == 1;
    }

    /** Removes the row (the trigger queues its bytes). */
    boolean delete(UUID id) {
        return jdbc.sql("delete from record_file where tenant_id = :tenant and id = :id")
                .param("tenant", tenant()).param("id", id).update() == 1;
    }

    /** Removes the rows of uploads that never finished (the trigger queues their bytes); returns how many. */
    int deleteExpiredPending(Duration pendingTtl, int limit) {
        return jdbc.sql("delete from record_file where tenant_id = :tenant and id in (select id from record_file "
                        + "where tenant_id = :tenant and state = 'PENDING' and deleted_at is null "
                        + "and created_at < now() - make_interval(secs => :seconds) order by created_at limit :limit)")
                .param("tenant", tenant()).param("seconds", pendingTtl.toMillis() / 1000.0).param("limit", limit)
                .update();
    }

    // ---- the queue of bytes to remove ----

    /** The keys waiting to be removed, oldest first. */
    List<String> queuedKeys(int limit) {
        return jdbc.sql("select storage_key from file_object_cleanup where tenant_id = :tenant "
                        + "and deleted_at is null order by created_at, id limit :limit")
                .param("tenant", tenant()).param("limit", limit).query(String.class).list();
    }

    /** The bytes are gone: the entry goes too. */
    boolean dequeue(String key) {
        return jdbc.sql("delete from file_object_cleanup where tenant_id = :tenant and storage_key = :key")
                .param("tenant", tenant()).param("key", key).update() > 0;
    }

    /** One more failed attempt, counted so a stuck key can be seen. */
    void attempted(String key, UUID actor) {
        jdbc.sql("update file_object_cleanup set attempts = attempts + 1, version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and storage_key = :key and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("key", key).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static FileRow map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
        return new FileRow(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getObject("record_id", UUID.class), rs.getString("name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("storage_key"),
                rs.getString("state"), created.toInstant(), rs.getObject("created_by", UUID.class));
    }
}
