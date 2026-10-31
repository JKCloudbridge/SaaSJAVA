package app.platform.integration.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code integration_file} (Sprint 29A, ADR-0242): the ledger of the files picked up from an
 * inbox. A row is written <em>before</em> its file is moved, so a restart at any point finds it, and the progress of
 * the rows applied is written in the same transaction as the rows themselves, so what the ledger says is exactly what
 * was written. It holds hashes, counts, states and stable reasons; never a name, a path, a row or a value.
 */
@Repository
class FileLedgerStore {

    /**
     * A ledger row.
     *
     * @param state {@code CLAIMING}, {@code CLAIMED}, {@code APPLIED}, {@code REJECTED}, {@code DONE},
     *        {@code SET_ASIDE} or {@code ABANDONED}
     * @param rowsTotal the rows of the file, null until it has been read and checked
     * @param rowsDone the rows already applied and recorded
     */
    record Entry(UUID id, UUID connectionId, String fileKey, String contentHash, long sizeBytes, String state,
            Integer rowsTotal, int rowsDone, int rowsApplied, int rowsSkipped, int rowsRefused, String reason) {
    }

    /** How a connection's files stand, for the screens. */
    record Counts(long inProgress, long done, long setAside) {
    }

    private static final UUID SYSTEM = ActorId.SYSTEM.value();
    private static final String COLUMNS = "id, connection_id, file_key, content_hash, size_bytes, state, rows_total, "
            + "rows_done, rows_applied, rows_skipped, rows_refused, reason";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    FileLedgerStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Writes the intent to take a file, before it is moved. */
    UUID claim(UUID connectionId, String fileKey, long sizeBytes) {
        return jdbc.sql("insert into integration_file (connection_id, file_key, size_bytes, created_by, updated_by) "
                        + "values (:connection, :key, :size, :system, :system) returning id")
                .param("connection", connectionId).param("key", fileKey).param("size", sizeBytes)
                .param("system", SYSTEM).query(UUID.class).single();
    }

    /** Whether a file with this key was applied completely before. */
    boolean wasDone(UUID connectionId, String fileKey) {
        return jdbc.sql("select exists (select 1 from integration_file where tenant_id = :tenant "
                        + "and connection_id = :connection and file_key = :key and state = 'DONE' "
                        + "and deleted_at is null)")
                .param("tenant", tenant()).param("connection", connectionId).param("key", fileKey)
                .query(Boolean.class).single();
    }

    /** The rows of a connection that are not finished: files to resume or to move. Oldest first. */
    List<Entry> unfinished(UUID connectionId) {
        return jdbc.sql("select " + COLUMNS + " from integration_file where tenant_id = :tenant "
                        + "and connection_id = :connection and state in ('CLAIMING', 'CLAIMED', 'APPLIED', "
                        + "'REJECTED') and deleted_at is null order by created_at, id")
                .param("tenant", tenant()).param("connection", connectionId).query((rs, row) -> entry(rs)).list();
    }

    Optional<Entry> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration_file where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("id", id).query((rs, row) -> entry(rs)).optional();
    }

    /** The file is in the processing directory. */
    void claimed(UUID id) {
        move(id, "CLAIMED", null);
    }

    /** The file was never moved (it vanished or the move was refused): nothing more to do for this row. */
    void abandon(UUID id, String reason) {
        jdbc.sql("update integration_file set state = 'ABANDONED', reason = :reason, finished_at = now(), "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id "
                        + "and state = 'CLAIMING'")
                .param("reason", reason).param("system", SYSTEM).param("tenant", tenant()).param("id", id).update();
    }

    /** The file was read and every row checked: its content and its number of rows are fixed from here. */
    void read(UUID id, String contentHash, int rows) {
        jdbc.sql("update integration_file set content_hash = :hash, rows_total = :rows, version = version + 1, "
                        + "updated_by = :system where tenant_id = :tenant and id = :id and state = 'CLAIMED'")
                .param("hash", contentHash).param("rows", rows).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).update();
    }

    /**
     * Records a batch, in the transaction that wrote it. Only if the rows done are still what the caller believed, so
     * two actors can never record the same batch twice.
     */
    boolean progress(UUID id, int expectedDone, int rows, int applied, int skipped, int refused) {
        return jdbc.sql("update integration_file set rows_done = rows_done + :rows, "
                        + "rows_applied = rows_applied + :applied, rows_skipped = rows_skipped + :skipped, "
                        + "rows_refused = rows_refused + :refused, version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id and state = 'CLAIMED' and rows_done = :expected")
                .param("rows", rows).param("applied", applied).param("skipped", skipped).param("refused", refused)
                .param("system", SYSTEM).param("tenant", tenant()).param("id", id).param("expected", expectedDone)
                .update() == 1;
    }

    /** Every row was applied; the file waits to be moved. */
    void applied(UUID id) {
        move(id, "APPLIED", null);
    }

    /** The file failed validation as a whole and nothing was applied; it waits to be moved to the error directory. */
    void rejected(UUID id, String reason) {
        move(id, "REJECTED", reason);
    }

    /** The file was moved to the done directory. */
    void done(UUID id) {
        finish(id, "DONE", null);
    }

    /** The file was moved to the error directory. */
    void setAside(UUID id, String reason) {
        finish(id, "SET_ASIDE", reason);
    }

    /** What a connection's files come to. */
    Counts counts(UUID connectionId) {
        return jdbc.sql("select count(*) filter (where state in ('CLAIMING', 'CLAIMED', 'APPLIED', 'REJECTED')), "
                        + "count(*) filter (where state = 'DONE'), count(*) filter (where state = 'SET_ASIDE') "
                        + "from integration_file where tenant_id = :tenant and connection_id = :connection "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId)
                .query((rs, row) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3))).single();
    }

    /** Removes finished rows older than the retention, a bounded number at a time. */
    int purge(UUID connectionId, Duration retention, int batch) {
        return jdbc.sql("delete from integration_file where id in (select id from integration_file "
                        + "where tenant_id = :tenant and connection_id = :connection "
                        + "and state in ('DONE', 'SET_ASIDE', 'ABANDONED') "
                        + "and finished_at < now() - make_interval(secs => cast(:secs as double precision)) "
                        + "limit :batch)")
                .param("tenant", tenant()).param("connection", connectionId)
                .param("secs", retention.toMillis() / 1000.0).param("batch", batch).update();
    }

    private void move(UUID id, String state, String reason) {
        jdbc.sql("update integration_file set state = :state, reason = coalesce(cast(:reason as text), reason), "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id")
                .param("state", state).param("reason", reason).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).update();
    }

    private void finish(UUID id, String state, String reason) {
        jdbc.sql("update integration_file set state = :state, reason = coalesce(cast(:reason as text), reason), "
                        + "finished_at = now(), version = version + 1, updated_by = :system "
                        + "where tenant_id = :tenant and id = :id")
                .param("state", state).param("reason", reason).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).update();
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        int total = rs.getInt("rows_total");
        Integer rowsTotal = rs.wasNull() ? null : total;
        return new Entry(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getString("file_key"), rs.getString("content_hash"), rs.getLong("size_bytes"),
                rs.getString("state"), rowsTotal, rs.getInt("rows_done"), rs.getInt("rows_applied"),
                rs.getInt("rows_skipped"), rs.getInt("rows_refused"), rs.getString("reason"));
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
