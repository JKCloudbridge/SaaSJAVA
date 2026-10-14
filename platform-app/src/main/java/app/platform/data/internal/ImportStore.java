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
 * The import jobs and the rows that failed (Sprint 17, ADR-0123). Every statement is a constant with parameters; the
 * organization is a parameter taken from the tenant context in addition to the forced row level security. The mapping
 * and the rows of the file are typed text of the organization: they are read and written here and never logged.
 */
@Repository
class ImportStore {

    /** A job as stored. */
    record JobRow(UUID id, String object, UUID startedBy, UUID membershipId, String operation, String externalIdField,
            boolean dryRun, String delimiter, String mapping, String state, String sourceKey, long sourceBytes,
            String errorKey, int totalRows, int processedRows, int createdRows, int updatedRows, int failedRows,
            long cursorOffset, boolean checked, String failure, boolean cancelRequested, Instant createdAt,
            Instant finishedAt, Instant expiresAt) {

        /** Prints the identifier only: the mapping is typed text. */
        @Override
        public String toString() {
            return "JobRow[" + id + "]";
        }
    }

    /** A row that failed. */
    record ErrorRow(int rowNumber, String reason, String rawRow) {

        /** Prints the number only: the row is data. */
        @Override
        public String toString() {
            return "ErrorRow[" + rowNumber + "]";
        }
    }

    private static final String COLUMNS = "id, object_api_name, started_by, membership_id, operation, "
            + "external_id_field, dry_run, delimiter, cast(mapping as text) as mapping, state, source_key, "
            + "source_bytes, error_key, total_rows, processed_rows, created_rows, updated_rows, failed_rows, "
            + "cursor_offset, checked, failure, cancel_requested, created_at, finished_at, expires_at";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    ImportStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    JobRow insert(String object, UUID startedBy, UUID membershipId, String operation, String externalIdField,
            boolean dryRun, String delimiter, String mapping) {
        return jdbc.sql("insert into import_job (tenant_id, object_api_name, started_by, membership_id, operation, "
                        + "external_id_field, dry_run, delimiter, mapping, created_by, updated_by) values (:tenant, "
                        + ":object, :user, :membership, :operation, :ext, :dry, :delimiter, cast(:mapping as jsonb), "
                        + ":user, :user) returning " + COLUMNS)
                .param("tenant", tenant()).param("object", object).param("user", startedBy)
                .param("membership", membershipId).param("operation", operation).param("ext", externalIdField)
                .param("dry", dryRun).param("delimiter", delimiter).param("mapping", mapping)
                .query(ImportStore::job).single();
    }

    Optional<JobRow> job(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from import_job where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("id", id).query(ImportStore::job).optional();
    }

    Optional<JobRow> jobForUpdate(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from import_job where tenant_id = :tenant and id = :id "
                        + "and deleted_at is null for update")
                .param("tenant", tenant()).param("id", id).query(ImportStore::job).optional();
    }

    /** The newest jobs a person started for an object. */
    List<JobRow> recent(String object, UUID startedBy, int limit) {
        return jdbc.sql("select " + COLUMNS + " from import_job where tenant_id = :tenant and object_api_name = "
                        + ":object and started_by = :user and deleted_at is null order by created_at desc, id desc "
                        + "limit :limit")
                .param("tenant", tenant()).param("object", object).param("user", startedBy).param("limit", limit)
                .query(ImportStore::job).list();
    }

    /** How many imports of the organization wait or run. */
    int active() {
        return jdbc.sql("select count(*) from import_job where tenant_id = :tenant and state in ('QUEUED', "
                        + "'RUNNING') and deleted_at is null")
                .param("tenant", tenant()).query(Integer.class).single();
    }

    /** The file arrived: the job waits for the runner. False when the job is no longer waiting for a file. */
    boolean queue(UUID id, String sourceKey, long bytes, Duration retention, UUID actor) {
        return jdbc.sql("update import_job set state = 'QUEUED', source_key = :key, source_bytes = :bytes, "
                        + "expires_at = now() + make_interval(secs => :seconds), version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id and state = 'AWAITING_FILE' "
                        + "and deleted_at is null")
                .param("key", sourceKey).param("bytes", bytes).param("seconds", retention.toMillis() / 1000.0)
                .param("actor", actor).param("tenant", tenant()).param("id", id).update() == 1;
    }

    /** The jobs the runner may take: waiting, or running with a claim that has expired. */
    List<UUID> runnable(int limit) {
        return jdbc.sql("select id from import_job where tenant_id = :tenant and deleted_at is null and (state = "
                        + "'QUEUED' or (state = 'RUNNING' and lease_until < now())) order by created_at limit :limit")
                .param("tenant", tenant()).param("limit", limit).query(UUID.class).list();
    }

    /** Takes the job for a while; false when somebody else has it. */
    boolean claim(UUID id, Duration lease, UUID actor) {
        return jdbc.sql("update import_job set state = 'RUNNING', lease_until = now() + make_interval(secs => "
                        + ":seconds), version = version + 1, updated_by = :actor where tenant_id = :tenant and "
                        + "id = :id and deleted_at is null and (state = 'QUEUED' or (state = 'RUNNING' and "
                        + "lease_until < now()))")
                .param("seconds", lease.toMillis() / 1000.0).param("actor", actor).param("tenant", tenant())
                .param("id", id).update() == 1;
    }

    /** The result of the check of the file: how many rows it has. */
    void checked(UUID id, int totalRows, UUID actor) {
        jdbc.sql("update import_job set checked = true, total_rows = :rows, version = version + 1, updated_by = "
                        + ":actor where tenant_id = :tenant and id = :id")
                .param("rows", totalRows).param("actor", actor).param("tenant", tenant()).param("id", id).update();
    }

    /** Records the progress of a batch together with the batch (the same transaction) and renews the claim. */
    void progress(UUID id, int processed, int created, int updated, int failed, long offset, Duration lease,
            UUID actor) {
        jdbc.sql("update import_job set processed_rows = processed_rows + :processed, created_rows = created_rows + "
                        + ":created, updated_rows = updated_rows + :updated, failed_rows = failed_rows + :failed, "
                        + "cursor_offset = :offset, lease_until = now() + make_interval(secs => :seconds), "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id")
                .param("processed", processed).param("created", created).param("updated", updated)
                .param("failed", failed).param("offset", offset).param("seconds", lease.toMillis() / 1000.0)
                .param("actor", actor).param("tenant", tenant()).param("id", id).update();
    }

    /** Renews the claim during a long step that has no progress to record. */
    void renew(UUID id, Duration lease, UUID actor) {
        jdbc.sql("update import_job set lease_until = now() + make_interval(secs => :seconds), version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id")
                .param("seconds", lease.toMillis() / 1000.0).param("actor", actor).param("tenant", tenant())
                .param("id", id).update();
    }

    /** Ends the job in a state, with the reason when it failed and the key of the report when there is one. */
    void finish(UUID id, String state, String failure, String errorKey, Duration retention, UUID actor) {
        jdbc.sql("update import_job set state = :state, failure = :failure, error_key = :errorKey, lease_until = "
                        + "null, finished_at = now(), expires_at = now() + make_interval(secs => :seconds), version = "
                        + "version + 1, updated_by = :actor where tenant_id = :tenant and id = :id")
                .param("state", state).param("failure", failure).param("errorKey", errorKey)
                .param("seconds", retention.toMillis() / 1000.0).param("actor", actor).param("tenant", tenant())
                .param("id", id).update();
    }

    /** Asks the running job to stop at the end of its batch. */
    boolean requestCancel(UUID id, UUID actor) {
        return jdbc.sql("update import_job set cancel_requested = true, version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and state in ('QUEUED', 'RUNNING') "
                        + "and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("id", id).update() == 1;
    }

    /** The jobs that ended and whose files are due to be removed. */
    List<JobRow> expired(int limit) {
        return jdbc.sql("select " + COLUMNS + " from import_job where tenant_id = :tenant and deleted_at is null "
                        + "and state in ('COMPLETED', 'FAILED', 'CANCELLED') and expires_at < now() "
                        + "and (source_key is not null or error_key is not null) order by expires_at limit :limit")
                .param("tenant", tenant()).param("limit", limit).query(ImportStore::job).list();
    }

    /** The files are gone: the keys and the report rows go with them, the counts stay. */
    void forgetFiles(UUID id, UUID actor) {
        jdbc.sql("delete from import_error where tenant_id = :tenant and job_id = :id")
                .param("tenant", tenant()).param("id", id).update();
        jdbc.sql("update import_job set source_key = null, error_key = null, version = version + 1, updated_by = "
                        + ":actor where tenant_id = :tenant and id = :id")
                .param("actor", actor).param("tenant", tenant()).param("id", id).update();
    }

    // ---- the rows that failed ----

    void addError(UUID jobId, int rowNumber, String reason, String rawRow, UUID actor) {
        jdbc.sql("insert into import_error (tenant_id, job_id, row_number, reason, raw_row, created_by, updated_by) "
                        + "values (:tenant, :job, :row, :reason, :raw, :actor, :actor) on conflict (tenant_id, "
                        + "job_id, row_number) where deleted_at is null do nothing")
                .param("tenant", tenant()).param("job", jobId).param("row", rowNumber).param("reason", reason)
                .param("raw", rawRow).param("actor", actor).update();
    }

    int errorCount(UUID jobId) {
        return jdbc.sql("select count(*) from import_error where tenant_id = :tenant and job_id = :job")
                .param("tenant", tenant()).param("job", jobId).query(Integer.class).single();
    }

    /** The failed rows after a row number, in order, at most {@code limit}. */
    List<ErrorRow> errors(UUID jobId, int afterRow, int limit) {
        return jdbc.sql("select row_number, reason, raw_row from import_error where tenant_id = :tenant and "
                        + "job_id = :job and row_number > :after order by row_number limit :limit")
                .param("tenant", tenant()).param("job", jobId).param("after", afterRow).param("limit", limit)
                .query((rs, row) -> new ErrorRow(rs.getInt("row_number"), rs.getString("reason"),
                        rs.getString("raw_row"))).list();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static JobRow job(ResultSet rs, int row) throws SQLException {
        return new JobRow(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getObject("started_by", UUID.class), rs.getObject("membership_id", UUID.class),
                rs.getString("operation"), rs.getString("external_id_field"), rs.getBoolean("dry_run"),
                rs.getString("delimiter"), rs.getString("mapping"), rs.getString("state"), rs.getString("source_key"),
                rs.getLong("source_bytes"), rs.getString("error_key"), rs.getInt("total_rows"),
                rs.getInt("processed_rows"), rs.getInt("created_rows"), rs.getInt("updated_rows"),
                rs.getInt("failed_rows"), rs.getLong("cursor_offset"), rs.getBoolean("checked"),
                rs.getString("failure"), rs.getBoolean("cancel_requested"), instant(rs, "created_at"),
                instant(rs, "finished_at"), instant(rs, "expires_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }
}
