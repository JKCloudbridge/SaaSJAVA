package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.SheetVersionSummaryView;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts a draft version from an uploaded spreadsheet file (UI3B, ADR-0195). The file is read elsewhere
 * ({@link SpreadsheetImporter}, without the database); this is the part that asks the rules of the sheet.
 *
 * <p>The two steps are separate so that no database transaction is held while a file arrives or is read:
 * {@link #plan} asks, before the body is read, whether this member may change the sheet and whether a draft can be
 * started, and says in which shape the grid has to be made; {@link #start} asks it all again under the lock of the
 * sheet, because the world may have moved while the file was read, and stores the grid. Who may change a sheet is
 * {@link SheetOwners}'s answer, the one place; the grid has passed {@link SheetGridValidator}, the one validator; the
 * database refuses a second draft whatever this class does.
 */
@Service
class SheetImports {

    /**
     * The shape a grid made from a file must have.
     *
     * @param dimension 2 when the sheet names its rows, 1 when the filler adds them
     * @param rowsMode the rows mode that goes with the dimension
     */
    record Plan(int dimension, RowsMode rowsMode) {
    }

    private final SheetOwners owners;
    private final SheetVersionStore store;
    private final SheetChanges changes;
    private final SheetProperties properties;
    private final SheetVersions versions;

    SheetImports(SheetOwners owners, SheetVersionStore store, SheetChanges changes, SheetProperties properties,
            SheetVersions versions) {
        this.owners = owners;
        this.store = store;
        this.changes = changes;
        this.properties = properties;
        this.versions = versions;
    }

    /**
     * What an upload may do now: the shape of the grid to make. The first version of a sheet that has none names its
     * rows (dimension 2, fixed); a sheet that has versions keeps the shape its first one gave.
     *
     * @throws ApiException {@code NOT_FOUND}, {@code FORBIDDEN}, {@code CONFLICT} (a draft exists, with its number; the
     *         most versions a sheet may have)
     */
    @Transactional(readOnly = true)
    Plan plan(String ownerObject, UUID ownerId) {
        SheetOwners.Reach reach = owners.reachForChange(ownerObject, ownerId);
        return check(reach, store.state(reach.object(), reach.id()));
    }

    /**
     * Stores the grid as a new draft.
     *
     * @param expected the shape the grid was made for; a sheet whose shape has changed since is {@code CONFLICT}
     * @param imported the checked grid and its report
     * @param format {@code xlsx} or {@code csv}
     * @param fileBytes the size of the file
     */
    @Transactional
    SheetVersionSummaryView start(String ownerObject, UUID ownerId, Plan expected,
            SpreadsheetImporter.Imported imported, String format, int fileBytes) {
        SheetOwners.Reach reach = owners.reachForChange(ownerObject, ownerId);
        store.lock(reach.object(), reach.id());
        SheetVersionStore.State state = store.state(reach.object(), reach.id());
        Plan now = check(reach, state);
        if (!now.equals(expected)) {
            changes.refused("import", "changed");
            throw new ApiException(ErrorCode.CONFLICT, "The sheet changed while the file was being read. Upload the "
                    + "file again.");
        }
        SheetVersionStore.Row row;
        try {
            row = store.insert(reach.object(), reach.id(), state.highest() + 1, imported.checked(), reach.userId());
        } catch (DuplicateKeyException e) {
            // The lock makes this unreachable through this class; the database's indexes are the backstop.
            changes.refused("import", "draft_exists");
            throw new ApiException(ErrorCode.CONFLICT, "A draft already exists. Reload and continue with it.");
        }
        int leftOut = imported.report().notes().stream().filter(entry -> entry.note().leftOut())
                .mapToInt(ImportNote.Entry::count).sum();
        changes.imported(reach.userId(), reach.object(), reach.id(), row.versionNo(), format, fileBytes,
                imported.checked(), leftOut);
        return versions.summaryOf(row);
    }

    /** Counts a refused file by a fixed code, never by what it held. */
    void refused(String code) {
        changes.refused("import", code);
    }

    private Plan check(SheetOwners.Reach reach, SheetVersionStore.State state) {
        if (state.draft() != null) {
            changes.refused("import", "draft_exists");
            throw new ApiException(ErrorCode.CONFLICT, "A draft already exists: it is version " + state.draft()
                    + ". Continue with it, or send it for approval first.");
        }
        if (state.count() >= properties.limits().maxVersionsPerOwner()) {
            changes.refused("import", "most_versions");
            throw new ApiException(ErrorCode.CONFLICT, "This sheet has the most versions it can have ("
                    + properties.limits().maxVersionsPerOwner() + ").");
        }
        if (state.count() == 0) {
            return new Plan(2, RowsMode.FIXED);
        }
        SheetVersionStore.Row first = store.first(reach.object(), reach.id()).orElseThrow();
        return new Plan(first.dimension(), RowsMode.of(first.rowsMode()).orElseThrow());
    }
}
