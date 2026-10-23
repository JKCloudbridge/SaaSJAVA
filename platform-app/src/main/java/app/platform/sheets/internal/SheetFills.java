package app.platform.sheets.internal;

import app.platform.sharedkernel.approval.RecordLocks;
import app.platform.sheets.FillStatus;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.SheetFillLimitsView;
import app.platformapi.SheetFillSummaryView;
import app.platformapi.SheetFillView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading and saving the cells of a fill-in (FF1, ADR-0192): what a member sees of it and what a member may type.
 *
 * <p>Who may do what is {@link FillOwners}'s answer about the header record the fill-in belongs to. A member who may
 * read the header reads the fill-in together with the version of the sheet it is pinned to, whatever has happened to
 * that sheet since; a person who is asked to review it reads that one fill-in and nothing else of the header. A fill-in
 * the member may not see answers like one that does not exist.
 *
 * <p>The cells are the filler's own work while the fill-in is a draft or was returned. Once it was sent they are fixed,
 * except for the correction of a person who may edit after submit: that is the ability to override the lock of an
 * approval besides the right to change the header (decision 11 of the business analysis). A correction is counted, the
 * database holds it to one at a time, and it is audited by size, counts and the fingerprint, never by content; while
 * the review is pending it is also written into the history of the request. No version of the sheet is ever touched.
 *
 * <p>This class only changes cells. Moving a fill-in through its lifecycle is {@link DefaultFillLifecycle}'s, called by
 * the module that owns the header.
 */
@Service
@Transactional
class SheetFills {

    private static final String NO_FILL = "This fill-in does not exist.";
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {
    };

    private final FillOwners owners;
    private final SheetFillStore store;
    private final SheetVersionStore versions;
    private final FillValidator validator;
    private final FillChanges changes;
    private final FillProperties properties;
    private final SheetProperties sheetProperties;
    private final RecordLocks locks;
    private final JsonMapper json = JsonMapper.builder().build();

    SheetFills(FillOwners owners, SheetFillStore store, SheetVersionStore versions, FillValidator validator,
            FillChanges changes, FillProperties properties, SheetProperties sheetProperties, RecordLocks locks) {
        this.owners = owners;
        this.store = store;
        this.versions = versions;
        this.validator = validator;
        this.changes = changes;
        this.properties = properties;
        this.sheetProperties = sheetProperties;
        this.locks = locks;
    }

    /** One fill-in with the version of the sheet it is pinned to and its cells. */
    @Transactional(readOnly = true)
    SheetFillView get(String fillObject, UUID fillId) {
        Optional<FillOwners.Reach> reach = owners.reachIfReadable(fillObject, fillId);
        FillOwners.Caller caller = owners.caller(fillObject);
        SheetFillStore.WithCells found = store.findCells(fillObject, fillId)
                .orElseThrow(() -> ApiException.notFound(NO_FILL));
        SheetFillStore.Row row = found.row();
        boolean asked = FillStatus.SUBMITTED.name().equals(row.status())
                && owners.holdsOpenTask(row.approvalRequestId(), caller.userId());
        if (reach.isEmpty() && !asked) {
            throw ApiException.notFound(NO_FILL);
        }
        SheetVersionStore.WithGrid pinned = versions.findGrid(row.ownerObject(), row.ownerId(), row.versionNo())
                .orElseThrow(() -> ApiException.notFound(NO_FILL));
        FillStatus status = FillStatus.valueOf(row.status());
        boolean canEdit = false;
        boolean correction = false;
        if (reach.isPresent() && reach.get().canChange()) {
            if (status.fillerMayChange()) {
                canEdit = !retired(pinned) && caller.userId().equals(row.createdBy());
            } else if (owners.mayCorrect(reach.get())) {
                canEdit = true;
                correction = true;
            }
        }
        return new SheetFillView(summary(row, pinned.row().status()), json.readValue(pinned.grid(), DOCUMENT),
                json.readValue(found.cells(), DOCUMENT), canEdit, correction, limits());
    }

    /**
     * Saves the cells of a fill-in. The request carries the row version that was read: a fill-in changed since is
     * {@code CONCURRENT_MODIFICATION} and nothing changes.
     *
     * @param raw the bytes of the cells document as they arrived (their size has been bounded by the caller)
     * @throws ApiException {@code NOT_FOUND}, {@code FORBIDDEN} (the fill-in was sent and the caller may not correct
     *         it, or may read but not change it), {@code VALIDATION_ERROR} (every place of the first stage that found
     *         problems), {@code CONFLICT} (the sheet was retired, or the fill-in moved to another status),
     *         {@code CONCURRENT_MODIFICATION}
     */
    SheetFillSummaryView save(String fillObject, UUID fillId, long version, byte[] raw) {
        FillOwners.Reach reach = owners.reachForChange(fillObject, fillId);
        store.lock(fillObject, fillId);
        SheetFillStore.Row current = store.find(fillObject, fillId).orElseThrow(() -> ApiException.notFound(NO_FILL));
        FillStatus status = FillStatus.valueOf(current.status());
        boolean correction = !status.fillerMayChange();
        if (!correction && !reach.userId().equals(current.createdBy())) {
            // The cells of a draft are their filler's own work, whoever else may see the fill-in.
            changes.refused("save", "not_the_filler");
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the person who started a fill-in can fill it in.");
        }
        if (correction && !owners.mayCorrect(reach)) {
            changes.refused("save", "sent");
            throw new ApiException(ErrorCode.FORBIDDEN, "A fill-in that was sent for review can be changed only by "
                    + "the people who may correct it.");
        }
        SheetVersionStore.WithGrid pinned = versions.findGrid(current.ownerObject(), current.ownerId(),
                current.versionNo()).orElseThrow(() -> ApiException.notFound(NO_FILL));
        if (!correction && retired(pinned)) {
            changes.refused("save", "retired");
            throw new ApiException(ErrorCode.CONFLICT, "The sheet this fill-in was made for is retired, so the "
                    + "fill-in can no longer be changed.");
        }
        FillStructure structure = new FillStructure(SheetGridReader.read(json.readTree(pinned.grid())));
        FillValidator.Result result = validator.check(raw, structure);
        if (!result.accepted()) {
            changes.refused("save", "invalid");
            throw ApiException.validation(places(result.problems()));
        }
        FillValidator.Checked checked = result.checked();
        if (correction) {
            // A fill-in that was sent must stay a complete one: a correction cannot take a required value away.
            List<FillValidator.Problem> incomplete = validator.completeness(structure, checked.doc());
            if (!incomplete.isEmpty()) {
                changes.refused("save", "incomplete");
                throw ApiException.validation(places(incomplete));
            }
            if (SheetGridWriter.hex(checked.hash()).equals(current.cellsHash()) && version == current.version()) {
                // Nothing to correct: the same cells are not a correction, and counting it would be a lie.
                changes.refused("save", "unchanged");
                return summary(current, pinned.row().status());
            }
        }
        SheetFillStore.Row saved = (correction
                ? store.updateCellsCorrected(fillObject, fillId, version, checked, reach.userId())
                : store.updateCells(fillObject, fillId, version, checked, reach.userId())).orElse(null);
        if (saved == null) {
            // Changed since it was read, or moved to another status between the read above and the update.
            Optional<SheetFillStore.Row> now = store.find(fillObject, fillId);
            changes.refused("save", "conflict");
            if (now.isPresent() && !now.get().status().equals(current.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "This fill-in has moved on since you opened it. Reload it.");
            }
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (correction) {
            if (status == FillStatus.SUBMITTED) {
                // Into the history of the request: the member overrode the lock the review holds on the header.
                locks.overridden(fillObject, fillId, reach.userId());
            }
            changes.corrected(reach.userId(), fillObject, fillId, saved.version(), saved.corrections(), checked);
        } else {
            changes.saved(reach.userId(), fillObject, fillId, saved.version(), checked);
        }
        return summary(saved, pinned.row().status());
    }

    // ---- helpers ----

    private static boolean retired(SheetVersionStore.WithGrid pinned) {
        return "RETIRED".equals(pinned.row().status());
    }

    private SheetFillLimitsView limits() {
        return new SheetFillLimitsView(properties.maxBytes(), properties.maxRows(),
                sheetProperties.limits().maxCellChars());
    }

    /** The problems of the first stage that found any, as places with the words for each. */
    private static Map<String, List<String>> places(List<FillValidator.Problem> problems) {
        Map<String, List<String>> places = new LinkedHashMap<>();
        for (FillValidator.Problem problem : problems) {
            places.computeIfAbsent(problem.place(), place -> new ArrayList<>()).add(problem.message());
        }
        return places;
    }

    static SheetFillSummaryView summary(SheetFillStore.Row row, String versionStatus) {
        return new SheetFillSummaryView(row.fillId(), row.ownerObject(), row.ownerId(), row.versionNo(),
                versionStatus, row.status(), row.version(), row.rowCount(), row.cellsBytes(), row.cellsHash(),
                row.corrections(), row.createdAt(), row.updatedAt(), row.submittedAt(), row.decidedAt());
    }
}
