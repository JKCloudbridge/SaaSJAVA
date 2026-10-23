package app.platform.sheets.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The fill-ins of sheets (FF1, ADR-0192): one row per fill-in. Every statement is a constant with parameters; the
 * organization is a parameter taken from the tenant context in addition to the forced row level security of the table.
 * What a person may see or change is decided by {@link SheetFills} and {@link DefaultFillLifecycle}, not here: this
 * class does what it is told, and the database refuses what the lifecycle does not allow (V069).
 *
 * <p><strong>A list never selects the cells.</strong> {@link #COLUMNS} holds the facts about a fill-in and not the
 * cells; only {@link #findCells} reads them, for one fill-in (ADR-0173 section 6).
 */
@Repository
class SheetFillStore {

    /**
     * A fill-in without its cells.
     *
     * @param fillId the header record
     * @param ownerObject the object of the sheet's owner
     * @param ownerId the record the sheet belongs to
     * @param versionNo the version of the sheet it is pinned to
     * @param status the status name
     * @param rowCount how many rows it has
     * @param cellsBytes the stored size of the canonical cells document
     * @param cellsHash the SHA-256 of the canonical cells document as lower case hex
     * @param corrections how many times the cells were corrected after submit
     * @param version the row version, to send back when changing it
     * @param createdAt when it was started
     * @param createdBy who started it
     * @param updatedAt when it last changed
     * @param submittedAt when it was last sent for review, or null
     * @param decidedAt when its last review ended, or null
     * @param approvalRequestId the approval request that reviews it or reviewed it last, or null
     * @param archivedAt when its cells were moved to the object store (FF2, ADR-0193), or null
     * @param archiveKey where they are kept, or null while they are in the row
     */
    record Row(UUID fillId, String ownerObject, UUID ownerId, int versionNo, String status, int rowCount,
            long cellsBytes, String cellsHash, int corrections, long version, Instant createdAt, UUID createdBy,
            Instant updatedAt, Instant submittedAt, Instant decidedAt, UUID approvalRequestId, Instant archivedAt,
            String archiveKey) {

        /** Whether the cells are in the archive and not in the row. */
        boolean archived() {
            return archivedAt != null;
        }
    }

    /** A fill-in with its cells as the JSON text the database holds. The cells are the organization's text. */
    record WithCells(Row row, String cells) {

        @Override
        public String toString() {
            return "WithCells[" + row.fillId() + "]";
        }
    }

    private static final String COLUMNS = "fill_id, owner_object, owner_id, version_no, status, row_count, "
            + "cells_bytes, encode(cells_hash, 'hex') as cells_hash, corrections, version, created_at, created_by, "
            + "updated_at, submitted_at, decided_at, approval_request_id, archived_at, archive_key";

    private static final String WHERE_FILL = "tenant_id = :tenant and fill_object = :object and fill_id = :fill "
            + "and deleted_at is null";

    private static final String INSERT = "insert into sheet_fill (tenant_id, fill_object, fill_id, owner_object, "
            + "owner_id, version_no, cells, cells_hash, cells_bytes, row_count, created_by, updated_by) values "
            + "(:tenant, :object, :fill, :ownerObject, :owner, :no, cast(:cells as jsonb), :hash, :bytes, 0, "
            + ":actor, :actor)";

    // The version in the where clause is the optimistic check: nothing changes when it is stale. The status in it
    // keeps a fill-in that has been sent from being written as the filler's own work, whatever the caller believed.
    private static final String UPDATE_CELLS = "update sheet_fill set cells = cast(:cells as jsonb), "
            + "cells_hash = :hash, cells_bytes = :bytes, row_count = :rows, version = version + 1, "
            + "updated_by = :actor where " + WHERE_FILL + " and status in ('DRAFT', 'RETURNED') "
            + "and version = :version returning " + COLUMNS;

    private static final String UPDATE_CELLS_CORRECTED = "update sheet_fill set cells = cast(:cells as jsonb), "
            + "cells_hash = :hash, cells_bytes = :bytes, row_count = :rows, corrections = corrections + 1, "
            + "version = version + 1, updated_by = :actor where " + WHERE_FILL
            + " and status in ('SUBMITTED', 'APPROVED') and version = :version returning " + COLUMNS;

    private static final String SUBMIT = "update sheet_fill set status = 'SUBMITTED', submitted_at = now(), "
            + "approval_request_id = :request, version = version + 1, updated_by = :actor where " + WHERE_FILL
            + " and status in ('DRAFT', 'RETURNED') returning " + COLUMNS;

    private static final String END = "update sheet_fill set status = :status, decided_at = now(), "
            + "version = version + 1, updated_by = :actor where " + WHERE_FILL + " and status = 'SUBMITTED' "
            + "and approval_request_id = :request returning " + COLUMNS;

    private static final String ARCHIVABLE = "select fill_object, fill_id from sheet_fill where tenant_id = :tenant "
            + "and status = 'APPROVED' and archived_at is null and deleted_at is null and decided_at < :cutoff "
            + "order by decided_at, id limit :limit";

    // The move to the archive (V071): the cells become the empty object, nothing else about the fill-in changes. The
    // status and the empty archive pointer in the where clause make a second move, or a move of a fill-in that is not
    // approved, find nothing; the database refuses them too.
    private static final String ARCHIVE = "update sheet_fill set cells = cast('{}' as jsonb), archived_at = now(), "
            + "archive_key = :key, archive_bytes = :packed, version = version + 1, updated_by = :actor where "
            + WHERE_FILL + " and status = 'APPROVED' and archived_at is null returning " + COLUMNS;

    private static final String SUMMARIES = "select " + COLUMNS + " from sheet_fill where tenant_id = :tenant "
            + "and fill_object = :object and fill_id in (:fills) and deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    SheetFillStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /**
     * Takes the lock that serializes the changes of one fill-in, so that a save and a submit arriving together are
     * decided one after the other. Held until the transaction ends.
     */
    void lock(String object, UUID fill) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "fill:" + tenant() + ":" + object + ":" + fill).query().listOfRows();
    }

    /** One fill-in without its cells. */
    Optional<Row> find(String object, UUID fill) {
        return jdbc.sql("select " + COLUMNS + " from sheet_fill where " + WHERE_FILL)
                .param("tenant", tenant()).param("object", object).param("fill", fill)
                .query(SheetFillStore::map).optional();
    }

    /** One fill-in with its cells: the only statement that reads a cells document. */
    Optional<WithCells> findCells(String object, UUID fill) {
        return jdbc.sql("select " + COLUMNS + ", cells::text as cells_text from sheet_fill where " + WHERE_FILL)
                .param("tenant", tenant()).param("object", object).param("fill", fill)
                .query((rs, row) -> new WithCells(map(rs, row), rs.getString("cells_text"))).optional();
    }

    /** A header a job works on. */
    record Ref(String object, UUID id) {
    }

    /**
     * Approved fill-ins decided before the cutoff whose cells are still in the row, oldest decision first (FF2,
     * ADR-0193). Reads no cells.
     */
    List<Ref> archivable(Instant cutoff, int limit) {
        return jdbc.sql(ARCHIVABLE).param("tenant", tenant()).param("cutoff", OffsetDateTime.ofInstant(cutoff,
                java.time.ZoneOffset.UTC)).param("limit", limit)
                .query((rs, row) -> new Ref(rs.getString("fill_object"), rs.getObject("fill_id", UUID.class))).list();
    }

    /**
     * Moves the cells of a fill-in to the archive: the row keeps its hash, size and row count and gets the pointer. The
     * database lets this happen only in a transaction that sets {@code platform.fill_archive} to {@code on}; it is
     * switched on for this one statement and off again, so nothing else in the transaction can use the window.
     *
     * @return the fill-in as it now is, or empty when it is not approved or was archived already
     */
    Optional<Row> archive(String object, UUID fill, String key, int packedBytes) {
        jdbc.sql("select set_config('platform.fill_archive', 'on', true)").query().listOfRows();
        Optional<Row> moved = jdbc.sql(ARCHIVE).param("tenant", tenant()).param("object", object)
                .param("fill", fill).param("key", key).param("packed", packedBytes)
                .param("actor", app.platform.sharedkernel.ActorId.SYSTEM.value()).query(SheetFillStore::map)
                .optional();
        jdbc.sql("select set_config('platform.fill_archive', 'off', true)").query().listOfRows();
        return moved;
    }

    /** The facts of these fill-ins, one row each, without a cells document. */
    List<Row> summaries(String object, Collection<UUID> fills) {
        if (fills.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SUMMARIES).param("tenant", tenant()).param("object", object).param("fills", List.copyOf(fills))
                .query(SheetFillStore::map).list();
    }

    /** Starts the fill-in with an empty cells document; the database refuses a version that is not in use. */
    void insert(String object, UUID fill, String ownerObject, UUID owner, int versionNo, UUID actor) {
        byte[] bytes = FillValidator.EMPTY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.sql(INSERT).param("tenant", tenant()).param("object", object).param("fill", fill)
                .param("ownerObject", ownerObject).param("owner", owner).param("no", versionNo)
                .param("cells", FillValidator.EMPTY).param("hash", SheetGridWriter.sha256(bytes))
                .param("bytes", bytes.length).param("actor", actor).update();
    }

    /** The fill-in after the filler's change, or empty when it is not a draft or was changed since it was read. */
    Optional<Row> updateCells(String object, UUID fill, long version, FillValidator.Checked checked, UUID actor) {
        return jdbc.sql(UPDATE_CELLS).param("tenant", tenant()).param("object", object).param("fill", fill)
                .param("version", version).param("cells", checked.canonical()).param("hash", checked.hash())
                .param("bytes", checked.bytes()).param("rows", checked.doc().rows().size()).param("actor", actor)
                .query(SheetFillStore::map).optional();
    }

    /**
     * The counted correction of a fill-in that was sent (FF1, ADR-0192). The database lets the cells of a submitted or
     * approved fill-in change only in a transaction that sets {@code platform.fill_correction} to {@code on} and raises
     * {@code corrections} by exactly one; the setting is switched on for this one statement and off again, so nothing
     * else in the transaction can use the window.
     */
    Optional<Row> updateCellsCorrected(String object, UUID fill, long version, FillValidator.Checked checked,
            UUID actor) {
        jdbc.sql("select set_config('platform.fill_correction', 'on', true)").query().listOfRows();
        Optional<Row> changed = jdbc.sql(UPDATE_CELLS_CORRECTED).param("tenant", tenant()).param("object", object)
                .param("fill", fill).param("version", version).param("cells", checked.canonical())
                .param("hash", checked.hash()).param("bytes", checked.bytes())
                .param("rows", checked.doc().rows().size()).param("actor", actor).query(SheetFillStore::map)
                .optional();
        // Switched off again at once. Not in a finally: if the statement failed, the transaction is already aborted and
        // the setting, which lasts only as long as the transaction, goes with it.
        jdbc.sql("select set_config('platform.fill_correction', 'off', true)").query().listOfRows();
        return changed;
    }

    /** The fill-in sent for review under the request; empty when it is neither a draft nor returned. */
    Optional<Row> submit(String object, UUID fill, UUID requestId, UUID actor) {
        return jdbc.sql(SUBMIT).param("tenant", tenant()).param("object", object).param("fill", fill)
                .param("request", requestId).param("actor", actor).query(SheetFillStore::map).optional();
    }

    /** The fill-in a request reviewed, moved to the status the review ended with; empty when none is under review. */
    Optional<Row> end(String object, UUID fill, UUID requestId, String status, UUID actor) {
        return jdbc.sql(END).param("tenant", tenant()).param("object", object).param("fill", fill)
                .param("request", requestId).param("status", status).param("actor", actor)
                .query(SheetFillStore::map).optional();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Row map(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("fill_id", UUID.class), rs.getString("owner_object"),
                rs.getObject("owner_id", UUID.class), rs.getInt("version_no"), rs.getString("status"),
                rs.getInt("row_count"), rs.getLong("cells_bytes"), rs.getString("cells_hash"),
                rs.getInt("corrections"), rs.getLong("version"), instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class), instant(rs, "updated_at"), instant(rs, "submitted_at"),
                instant(rs, "decided_at"), rs.getObject("approval_request_id", UUID.class),
                instant(rs, "archived_at"), rs.getString("archive_key"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }
}
