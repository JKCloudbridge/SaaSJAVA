package app.platform.sheets.internal;

import app.platform.sharedkernel.approval.RecordLocks;
import app.platform.sharedkernel.files.RecordFileDirectory;
import app.platform.sharedkernel.solution.FillCorrections;
import app.platform.sheets.FillStatus;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.SheetCalculationView;
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
    private final FillValueStore valueStore;
    private final FillArchive archive;
    private final FillCalculations calculations;
    private final RecordFileDirectory directory;
    private final List<FillCorrections> corrections;
    private final JsonMapper json = JsonMapper.builder().build();

    SheetFills(FillOwners owners, SheetFillStore store, SheetVersionStore versions, FillValidator validator,
            FillChanges changes, FillProperties properties, SheetProperties sheetProperties, RecordLocks locks,
            FillValueStore valueStore, FillArchive archive, FillCalculations calculations,
            RecordFileDirectory directory, List<FillCorrections> corrections) {
        this.corrections = List.copyOf(corrections);
        this.owners = owners;
        this.store = store;
        this.versions = versions;
        this.validator = validator;
        this.changes = changes;
        this.properties = properties;
        this.sheetProperties = sheetProperties;
        this.locks = locks;
        this.valueStore = valueStore;
        this.archive = archive;
        this.calculations = calculations;
        this.directory = directory;
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
        String editAs = null;
        FillStructure shape = FillStructure.of(json.readTree(pinned.grid()));
        // The cells of an archived fill-in are read back from the object store and are read-only (ADR-0193).
        String cells = inFormatOf(pinned, row.archived() ? archive.read(row) : found.cells());
        if (reach.isPresent() && reach.get().canChange() && !row.archived()) {
            if (status.fillerMayChange()) {
                canEdit = !retired(pinned) && caller.userId().equals(row.createdBy());
                editAs = canEdit ? "FILLER" : null;
            } else if (owners.mayCorrect(reach.get())) {
                canEdit = true;
                correction = true;
                editAs = "CORRECTOR";
            }
        }
        if (!canEdit && asked && !row.archived() && FillClassRules.hasReviewerCells(shape)) {
            // The person asked to review fills the cells of the reviewer's class while the review is pending (SH5).
            canEdit = true;
            editAs = "REVIEWER";
        }
        return new SheetFillView(summary(row, pinned.row().status()), json.readValue(pinned.grid(), DOCUMENT),
                json.readValue(cells, DOCUMENT), canEdit, correction, limits(), editAs);
    }

    /**
     * The answers of the formulas of a fill-in, calculated now from the version it is pinned to and its cells (SH5).
     * Who may read it is the rule of {@link #get}; nothing is stored.
     */
    @Transactional(readOnly = true)
    SheetCalculationView calculation(String fillObject, UUID fillId) {
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
        FillStructure structure = FillStructure.of(json.readTree(pinned.grid()));
        String cells = row.archived() ? archive.read(row) : found.cells();
        return calculations.view(structure, validator.read(cells));
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
        FillOwners.Caller caller = owners.caller(fillObject);
        Optional<FillOwners.Reach> reached = owners.reachIfReadable(fillObject, fillId);
        store.lock(fillObject, fillId);
        SheetFillStore.Row current = store.find(fillObject, fillId).orElseThrow(() -> ApiException.notFound(NO_FILL));
        FillStatus status = FillStatus.valueOf(current.status());
        boolean asked = status == FillStatus.SUBMITTED && !current.archived()
                && owners.holdsOpenTask(current.approvalRequestId(), caller.userId());
        if (reached.isEmpty() && !asked) {
            throw ApiException.notFound(NO_FILL);
        }
        if (current.archived()) {
            changes.refused("save", "archived");
            throw new ApiException(ErrorCode.CONFLICT, "This fill-in is archived, so it can no longer be changed.");
        }
        boolean correction = !status.fillerMayChange();
        FillOwners.Reach reach = reached.orElse(null);
        if (!correction) {
            if (reach == null || !reach.canChange()) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (!reach.userId().equals(current.createdBy())) {
                // The cells of a draft are their filler's own work, whoever else may see the fill-in.
                changes.refused("save", "not_the_filler");
                throw new ApiException(ErrorCode.FORBIDDEN, "Only the person who started a fill-in can fill it in.");
            }
        }
        boolean mayCorrect = reach != null && reach.canChange() && owners.mayCorrect(reach);
        if (correction && !mayCorrect && !asked) {
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
        FillStructure structure = FillStructure.of(json.readTree(pinned.grid()));
        SheetGrid grid = structure.grid();
        FillValidator.Result result = validator.check(raw, structure);
        if (!result.accepted()) {
            changes.refused("save", "invalid");
            throw ApiException.validation(places(result.problems()));
        }
        FillValidator.Checked checked = result.checked();
        refuseUnknownFiles(fillId, checked.doc());
        FillDoc stored = validator.read(store.findCells(fillObject, fillId).orElseThrow().cells());
        // Who writes which cells (SH5): what differs from the stored cells must be of the writer's own class. A
        // person who holds an open task and changes only the reviewer's cells makes the reviewer's counted edit; a
        // person who may override the lock may correct any input cell, as before.
        boolean review = false;
        if (structure.isFormatThree()) {
            if (!correction) {
                refuseOtherClass(FillClassRules.violations(structure, stored, checked.doc(),
                        SheetDocument.InputClass.FILLER));
            } else if (asked && FillClassRules.violations(structure, stored, checked.doc(),
                    SheetDocument.InputClass.REVIEWER).isEmpty()) {
                review = true;
            }
        }
        if (correction && !review && !mayCorrect) {
            if (structure.isFormatThree() && asked) {
                refuseOtherClass(FillClassRules.violations(structure, stored, checked.doc(),
                        SheetDocument.InputClass.REVIEWER));
            }
            changes.refused("save", "sent");
            throw new ApiException(ErrorCode.FORBIDDEN, "A fill-in that was sent for review can be changed only by "
                    + "the people who may correct it.");
        }
        if (correction) {
            // A fill-in that was sent must stay a complete one: an edit cannot take a required value away.
            List<FillValidator.Problem> incomplete = validator.completeness(structure, checked.doc());
            if (!incomplete.isEmpty()) {
                changes.refused("save", "incomplete");
                throw ApiException.validation(places(incomplete));
            }
            if (SheetGridWriter.hex(checked.hash()).equals(current.cellsHash()) && version == current.version()) {
                // Nothing to change: the same cells are not an edit, and counting it would be a lie.
                changes.refused("save", "unchanged");
                return summary(current, pinned.row().status());
            }
        }
        UUID actor = caller.userId();
        SheetFillStore.Row saved = (review ? store.updateCellsReviewed(fillObject, fillId, version, checked, actor)
                : correction ? store.updateCellsCorrected(fillObject, fillId, version, checked, actor)
                : store.updateCells(fillObject, fillId, version, checked, actor)).orElse(null);
        if (saved == null) {
            // Changed since it was read, or moved to another status between the read above and the update.
            Optional<SheetFillStore.Row> now = store.find(fillObject, fillId);
            changes.refused("save", "conflict");
            if (now.isPresent() && !now.get().status().equals(current.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "This fill-in has moved on since you opened it. Reload it.");
            }
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (!FillValues.reportableColumns(grid).isEmpty()) {
            // In the transaction of the save: the copy a report reads is never ahead of or behind the cells (ADR-0193).
            valueStore.replace(fillObject, fillId, current.ownerObject(), current.ownerId(), current.versionNo(),
                    FillValues.of(grid, checked.doc(), reportableAnswers(structure, checked.doc())), actor);
        }
        if (review) {
            changes.reviewEdited(actor, fillObject, fillId, saved.version(), checked);
        } else if (correction) {
            if (status == FillStatus.SUBMITTED) {
                // Into the history of the request: the member overrode the lock the review holds on the header.
                locks.overridden(fillObject, fillId, actor);
            }
            changes.corrected(actor, fillObject, fillId, saved.version(), saved.corrections(), checked);
            if (status != FillStatus.SUBMITTED) {
                // The header is not locked by a review: what it says about the cells (the score) is brought up to date
                // here, in the same transaction. While a review is open the next approval calculates again.
                corrections.stream().filter(one -> one.serves(fillObject))
                        .forEach(one -> one.corrected(fillObject, fillId));
            }
        } else {
            changes.saved(actor, fillObject, fillId, saved.version(), checked);
        }
        return summary(saved, pinned.row().status());
    }

    /** A cell may name only a file that is on this fill-in's header: asked of the file directory, never trusted. */
    private void refuseUnknownFiles(UUID fillId, FillDoc doc) {
        List<String> named = FillValidator.attachmentIds(doc);
        if (named.isEmpty()) {
            return;
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        directory.existing(fillId, named.stream().map(UUID::fromString).toList())
                .forEach(id -> present.add(id.toString()));
        List<FillValidator.Problem> unknown = FillValidator.unknownFiles(doc, present);
        if (!unknown.isEmpty()) {
            changes.refused("save", "file_unknown");
            throw ApiException.validation(places(unknown));
        }
    }

    /** Refuses a save that changed cells of the other person's class, naming the places and never a value. */
    private void refuseOtherClass(List<String> others) {
        if (others.isEmpty()) {
            return;
        }
        changes.refused("save", "other_class");
        Map<String, List<String>> places = new LinkedHashMap<>();
        others.forEach(place -> places.put(place, List.of(FillClassRules.OTHER_PARTS)));
        throw ApiException.validation(places);
    }

    // ---- helpers ----

    /**
     * A fill-in on a sheet in format version 2 is born with the empty document of format 1 (the start knows nothing
     * of the sheet); it is shown in format 3, the format its first save will write, so the screen meets one shape.
     */
    private String inFormatOf(SheetVersionStore.WithGrid pinned, String stored) {
        FillStructure structure = FillStructure.of(json.readTree(pinned.grid()));
        if (!structure.isFormatThree() || json.readTree(stored).path("formatVersion").asInt() == FillDoc.FORMAT_THREE) {
            return stored;
        }
        return FillValidator.write(new FillDoc(FillDoc.FORMAT_THREE, structure.sheetKey(), List.of()));
    }

    /** The answers of the calculated columns of the sheet, for the report copy; none for a sheet without. */
    private Map<String, List<SheetCalculator.Answer>> reportableAnswers(FillStructure structure, FillDoc doc) {
        SheetCalculator.Outcome outcome = calculations.calculate(structure, doc);
        return outcome == null ? Map.of() : outcome.columns();
    }

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
                row.corrections(), row.createdAt(), row.updatedAt(), row.submittedAt(), row.decidedAt(),
                row.archivedAt());
    }
}
