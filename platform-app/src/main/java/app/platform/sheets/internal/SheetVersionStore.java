package app.platform.sheets.internal;

import app.platform.tenant.TenantContexts;
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
 * The versions of sheets (FA3, ADR-0186): one row per version. Every statement is a constant with parameters; the
 * organization is a parameter taken from the tenant context in addition to the forced row level security of the table.
 * What a person may see or change is decided by {@link SheetVersions}, not here: this class does what it is told, and
 * the database refuses what the lifecycle does not allow (V064).
 *
 * <p><strong>A list never selects the grid.</strong> {@link #COLUMNS} holds the facts about a version and not the grid;
 * only {@link #findGrid} reads it, for one version (ADR-0173 section 5).
 */
@Repository
class SheetVersionStore {

    /**
     * A version without its grid.
     *
     * @param versionNo its number inside the sheet
     * @param status the status name
     * @param dimension 1 or 2
     * @param rowsMode the rows mode name
     * @param gridBytes the stored size of the canonical grid
     * @param gridHash the SHA-256 of the canonical grid as lower case hex
     * @param version the row version, to send back when changing it
     * @param createdAt when it was made
     * @param updatedAt when it last changed
     * @param submittedAt when it was sent for approval, or null
     * @param decidedAt when its review ended, or null
     * @param reviewEdits how many times an approver changed the grid while it was in review
     * @param approvalRequestId the approval request that reviews it or reviewed it, or null
     * @param updatedBy who last changed it (the base column), or null
     */
    record Row(int versionNo, String status, int dimension, String rowsMode, long gridBytes, String gridHash,
            long version, Instant createdAt, Instant updatedAt, Instant submittedAt, Instant decidedAt,
            int reviewEdits, UUID approvalRequestId, UUID updatedBy) {
    }

    /** A version with its grid as the JSON text the database holds. The grid is the organization's text. */
    record WithGrid(Row row, String grid) {

        @Override
        public String toString() {
            return "WithGrid[" + row.versionNo() + "]";
        }
    }

    /** What the versions of a sheet add up to: how many there are, the highest number, and the draft if any. */
    record State(int count, int highest, Integer draft) {
    }

    /** What the versions of one owner add up to; a number is 0 when no version has that status. */
    record Summary(UUID owner, int active, int approved, int inReview, int draft, int retired, int highest,
            int total) {
    }

    private static final String COLUMNS = "version_no, status, dimension, rows_mode, grid_bytes, "
            + "encode(grid_hash, 'hex') as grid_hash, version, created_at, updated_at, submitted_at, decided_at, "
            + "review_edits, approval_request_id, updated_by";

    private static final String WHERE_OWNER = "tenant_id = :tenant and owner_object = :object and owner_id = :owner "
            + "and deleted_at is null";

    private static final String INSERT = "insert into sheet_version (tenant_id, owner_object, owner_id, version_no, "
            + "dimension, rows_mode, grid, grid_hash, grid_bytes, created_by, updated_by) values (:tenant, :object, "
            + ":owner, :no, :dimension, :mode, cast(:grid as jsonb), :hash, :bytes, :actor, :actor) returning "
            + COLUMNS;

    // The copy never leaves the database: the grid of the version being copied is read and written by one statement.
    private static final String INSERT_COPY = "insert into sheet_version (tenant_id, owner_object, owner_id, "
            + "version_no, dimension, rows_mode, grid, grid_hash, grid_bytes, created_by, updated_by) select "
            + "tenant_id, owner_object, owner_id, :no, dimension, rows_mode, grid, grid_hash, grid_bytes, :actor, "
            + ":actor from sheet_version where " + WHERE_OWNER + " and version_no = :base returning " + COLUMNS;

    // The version in the where clause is the optimistic check: nothing changes when it is stale. The status in it
    // keeps a version that has left draft from being written, whatever the caller believed.
    private static final String UPDATE_GRID = "update sheet_version set dimension = :dimension, rows_mode = :mode, "
            + "grid = cast(:grid as jsonb), grid_hash = :hash, grid_bytes = :bytes, version = version + 1, "
            + "updated_by = :actor where " + WHERE_OWNER + " and version_no = :no and status = 'DRAFT' "
            + "and version = :version returning " + COLUMNS;

    private static final String UPDATE_GRID_IN_REVIEW = "update sheet_version set dimension = :dimension, "
            + "rows_mode = :mode, grid = cast(:grid as jsonb), grid_hash = :hash, grid_bytes = :bytes, "
            + "review_edits = review_edits + 1, version = version + 1, updated_by = :actor where " + WHERE_OWNER
            + " and version_no = :no and status = 'IN_REVIEW' and version = :version returning " + COLUMNS;

    // The one draft of the sheet (the unique index says there is at most one) goes to review under the request.
    private static final String SUBMIT = "update sheet_version set status = 'IN_REVIEW', submitted_at = now(), "
            + "approval_request_id = :request, version = version + 1, updated_by = :actor where " + WHERE_OWNER
            + " and status = 'DRAFT' returning " + COLUMNS;

    private static final String END = "update sheet_version set status = :status, decided_at = now(), "
            + "version = version + 1, updated_by = :actor where " + WHERE_OWNER + " and status = 'IN_REVIEW' "
            + "and approval_request_id = :request returning " + COLUMNS;

    // Both leave the version in use: unpublishing makes it approved again, a newer one replaces it, retiring ends it.
    private static final String LEAVE_ACTIVE = "update sheet_version set status = :status, version = version + 1, "
            + "updated_by = :actor where " + WHERE_OWNER + " and status = 'ACTIVE' returning version_no";

    private static final String ACTIVATE = "update sheet_version set status = 'ACTIVE', version = version + 1, "
            + "updated_by = :actor where " + WHERE_OWNER + " and version_no = :no and status = 'APPROVED' "
            + "returning " + COLUMNS;

    private static final String SUMMARIES = "select owner_id, "
            + "coalesce(max(version_no) filter (where status = 'ACTIVE'), 0) as active, "
            + "coalesce(max(version_no) filter (where status = 'APPROVED'), 0) as approved, "
            + "coalesce(max(version_no) filter (where status = 'IN_REVIEW'), 0) as in_review, "
            + "coalesce(max(version_no) filter (where status = 'DRAFT'), 0) as draft, "
            + "coalesce(max(version_no) filter (where status = 'RETIRED'), 0) as retired, "
            + "max(version_no) as highest, count(*) as total from sheet_version "
            + "where tenant_id = :tenant and owner_object = :object and owner_id in (:owners) "
            + "and deleted_at is null group by owner_id";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    SheetVersionStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /**
     * Takes the lock that serializes the changes of the versions of one sheet, so that two requests for "a new version"
     * arriving together get one draft and one answer. Held until the transaction ends.
     */
    void lock(String object, UUID owner) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "sheet:" + tenant() + ":" + object + ":" + owner).query().listOfRows();
    }

    /** The versions, highest number first, without their grids; all of them, or only those readers may read. */
    List<Row> list(String object, UUID owner, boolean all) {
        return jdbc.sql("select " + COLUMNS + " from sheet_version where " + WHERE_OWNER
                        + " and (cast(:all as boolean) or status in ('ACTIVE', 'SUPERSEDED', 'RETIRED')) "
                        + "order by version_no desc")
                .param("tenant", tenant()).param("object", object).param("owner", owner).param("all", all)
                .query(SheetVersionStore::map).list();
    }

    Optional<Row> find(String object, UUID owner, int versionNo) {
        return jdbc.sql("select " + COLUMNS + " from sheet_version where " + WHERE_OWNER + " and version_no = :no")
                .param("tenant", tenant()).param("object", object).param("owner", owner).param("no", versionNo)
                .query(SheetVersionStore::map).optional();
    }

    /** One version with its grid: the only statement that reads a grid. */
    Optional<WithGrid> findGrid(String object, UUID owner, int versionNo) {
        return jdbc.sql("select " + COLUMNS + ", grid::text as grid_text from sheet_version where " + WHERE_OWNER
                        + " and version_no = :no")
                .param("tenant", tenant()).param("object", object).param("owner", owner).param("no", versionNo)
                .query((rs, row) -> new WithGrid(map(rs, row), rs.getString("grid_text"))).optional();
    }

    State state(String object, UUID owner) {
        return jdbc.sql("select count(*) as total, coalesce(max(version_no), 0) as highest, "
                        + "max(version_no) filter (where status = 'DRAFT') as draft from sheet_version where "
                        + WHERE_OWNER)
                .param("tenant", tenant()).param("object", object).param("owner", owner)
                .query((rs, row) -> new State(rs.getInt("total"), rs.getInt("highest"),
                        rs.getObject("draft") == null ? null : rs.getInt("draft"))).single();
    }

    /** The version a new draft is copied from when none is named: the active one, else the latest. */
    Optional<Integer> defaultBase(String object, UUID owner) {
        return jdbc.sql("select version_no from sheet_version where " + WHERE_OWNER
                        + " order by (status = 'ACTIVE') desc, version_no desc limit 1")
                .param("tenant", tenant()).param("object", object).param("owner", owner).query(Integer.class)
                .optional();
    }

    /** The shape the first version gave the sheet, or empty when there is no version yet. */
    Optional<Row> first(String object, UUID owner) {
        return jdbc.sql("select " + COLUMNS + " from sheet_version where " + WHERE_OWNER
                        + " order by version_no limit 1")
                .param("tenant", tenant()).param("object", object).param("owner", owner)
                .query(SheetVersionStore::map).optional();
    }

    Row insert(String object, UUID owner, int versionNo, SheetGridValidator.Checked checked, UUID actor) {
        return jdbc.sql(INSERT).param("tenant", tenant()).param("object", object).param("owner", owner)
                .param("no", versionNo).param("dimension", checked.grid().dimension())
                .param("mode", checked.grid().rowsMode().name()).param("grid", checked.canonical())
                .param("hash", checked.hash()).param("bytes", checked.bytes()).param("actor", actor)
                .query(SheetVersionStore::map).single();
    }

    /** The new draft made as a copy of another version, or empty when that version does not exist. */
    Optional<Row> insertCopy(String object, UUID owner, int versionNo, int base, UUID actor) {
        return jdbc.sql(INSERT_COPY).param("tenant", tenant()).param("object", object).param("owner", owner)
                .param("no", versionNo).param("base", base).param("actor", actor).query(SheetVersionStore::map)
                .optional();
    }

    /** The draft after the change, or empty when the version is not a draft or was changed since it was read. */
    Optional<Row> updateGrid(String object, UUID owner, int versionNo, long version,
            SheetGridValidator.Checked checked, UUID actor) {
        return jdbc.sql(UPDATE_GRID).param("tenant", tenant()).param("object", object).param("owner", owner)
                .param("no", versionNo).param("version", version).param("dimension", checked.grid().dimension())
                .param("mode", checked.grid().rowsMode().name()).param("grid", checked.canonical())
                .param("hash", checked.hash()).param("bytes", checked.bytes()).param("actor", actor)
                .query(SheetVersionStore::map).optional();
    }

    /**
     * The counted edit of an approver while the version is in review (FA4, ADR-0189). The database lets the grid of an
     * {@code IN_REVIEW} version change only in a transaction that sets {@code platform.review_edit} to {@code on} and
     * raises {@code review_edits} by exactly one; the setting is switched on for this one statement and off again, so
     * nothing else in the transaction can use the window.
     */
    Optional<Row> updateGridInReview(String object, UUID owner, int versionNo, long version,
            SheetGridValidator.Checked checked, UUID actor) {
        jdbc.sql("select set_config('platform.review_edit', 'on', true)").query().listOfRows();
        Optional<Row> changed = jdbc.sql(UPDATE_GRID_IN_REVIEW).param("tenant", tenant()).param("object", object)
                .param("owner", owner).param("no", versionNo).param("version", version)
                .param("dimension", checked.grid().dimension()).param("mode", checked.grid().rowsMode().name())
                .param("grid", checked.canonical()).param("hash", checked.hash()).param("bytes", checked.bytes())
                .param("actor", actor).query(SheetVersionStore::map).optional();
        // Switched off again at once. Not in a finally: if the statement failed, the transaction is already aborted and
        // the setting, which lasts only as long as the transaction, goes with it.
        jdbc.sql("select set_config('platform.review_edit', 'off', true)").query().listOfRows();
        return changed;
    }

    /** The draft sent for approval: it becomes {@code IN_REVIEW} under the request; empty when there is no draft. */
    Optional<Row> submit(String object, UUID owner, UUID requestId, UUID actor) {
        return jdbc.sql(SUBMIT).param("tenant", tenant()).param("object", object).param("owner", owner)
                .param("request", requestId).param("actor", actor).query(SheetVersionStore::map).optional();
    }

    /** The version a request reviewed, moved to the status the review ended with; empty when none is in review. */
    Optional<Row> end(String object, UUID owner, UUID requestId, String status, UUID actor) {
        return jdbc.sql(END).param("tenant", tenant()).param("object", object).param("owner", owner)
                .param("request", requestId).param("status", status).param("actor", actor)
                .query(SheetVersionStore::map).optional();
    }

    /** The version that was in use stops being so: the number it had, or empty when none was in use. */
    Optional<Integer> supersedeActive(String object, UUID owner, String status, UUID actor) {
        return jdbc.sql(LEAVE_ACTIVE).param("tenant", tenant()).param("object", object).param("owner", owner)
                .param("status", status).param("actor", actor).query(Integer.class).optional();
    }

    /** An approved version becomes the one in use; empty when that version is not approved. */
    Optional<Row> activate(String object, UUID owner, int versionNo, UUID actor) {
        return jdbc.sql(ACTIVATE).param("tenant", tenant()).param("object", object).param("owner", owner)
                .param("no", versionNo).param("actor", actor).query(SheetVersionStore::map).optional();
    }

    /**
     * What the versions of these owners add up to, one row each, without a grid: the numbers of the version in use,
     * the latest approved one, the one in review, the draft, and the highest.
     */
    List<Summary> summaries(String object, java.util.Collection<UUID> owners) {
        if (owners.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SUMMARIES).param("tenant", tenant()).param("object", object)
                .param("owners", List.copyOf(owners)).query((rs, n) -> new Summary(
                        rs.getObject("owner_id", UUID.class), rs.getInt("active"), rs.getInt("approved"),
                        rs.getInt("in_review"), rs.getInt("draft"), rs.getInt("retired"), rs.getInt("highest"),
                        rs.getInt("total"))).list();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Row map(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getInt("version_no"), rs.getString("status"), rs.getInt("dimension"),
                rs.getString("rows_mode"), rs.getLong("grid_bytes"), rs.getString("grid_hash"), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "submitted_at"),
                instant(rs, "decided_at"), rs.getInt("review_edits"),
                rs.getObject("approval_request_id", UUID.class), rs.getObject("updated_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }
}
