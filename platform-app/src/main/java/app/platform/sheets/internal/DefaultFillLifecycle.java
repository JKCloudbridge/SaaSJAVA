package app.platform.sheets.internal;

import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillRefusal;
import app.platform.sheets.FillRefusal.Reason;
import app.platform.sharedkernel.files.RecordFileDirectory;
import app.platform.sheets.FillStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The moves of a fill-in, for the module that owns its header record (FF1, ADR-0192). It is called inside the caller's
 * transaction: the submit and the end of a review run inside the approval engine's own, so a refusal here undoes the
 * decision that asked.
 *
 * <p>Every move first takes the lock of the fill-in that a save takes, so a save and a submit arriving together (two
 * instances, a double click) are decided one after the other, and then names the row it means by its status: a move
 * that no longer applies finds nothing and says so. The database refuses the moves the lifecycle lacks whatever this
 * class does ({@code SheetFillGuardIT}).
 *
 * <p>The cells are checked once more when the fill-in is sent: against the version it is pinned to, as that version is
 * now, because the limits are settings and may have been lowered since the last save, and because the draft may have
 * been saved half done on purpose. Nothing here ever reads a value out loud; a refusal names a place by identifiers.
 */
@Service
@Transactional
class DefaultFillLifecycle implements FillLifecycle {

    private static final Set<FillStatus> ENDINGS = Set.of(FillStatus.APPROVED, FillStatus.RETURNED, FillStatus.DRAFT);

    private final SheetFillStore store;
    private final SheetVersionStore versions;
    private final FillValidator validator;
    private final FillChanges changes;
    private final RecordFileDirectory directory;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultFillLifecycle(SheetFillStore store, SheetVersionStore versions, FillValidator validator,
            FillChanges changes, RecordFileDirectory directory) {
        this.store = store;
        this.versions = versions;
        this.validator = validator;
        this.changes = changes;
        this.directory = directory;
    }

    @Override
    public void start(String fillObject, UUID fillId, String ownerObject, UUID ownerId, int versionNo, UUID actor) {
        store.lock(fillObject, fillId);
        if (store.find(fillObject, fillId).isPresent()) {
            throw refused("start", Reason.ALREADY_STARTED);
        }
        // A sheet in format version 2 is filled in format 3 (SH5); a tab that is not fillable takes no fill-in. The
        // version itself is judged by the database when the row is inserted (it must be the one in use).
        if (versions.findGrid(ownerObject, ownerId, versionNo)
                .map(found -> !FillStructure.of(json.readTree(found.grid())).fillable()).orElse(false)) {
            throw refused("start", Reason.SHEET_NOT_FILLABLE);
        }
        try {
            store.insert(fillObject, fillId, ownerObject, ownerId, versionNo, actor);
        } catch (DuplicateKeyException e) {
            // The lock makes this unreachable through this class; the unique index is the backstop.
            throw refused("start", Reason.ALREADY_STARTED);
        } catch (DataIntegrityViolationException e) {
            // The database says the version is not the one in use (V069). Its message is not repeated.
            throw refused("start", Reason.NOT_IN_USE);
        }
        changes.started(actor, fillObject, fillId, versionNo);
    }

    @Override
    public void submit(String fillObject, UUID fillId, UUID requestId, UUID actor) {
        store.lock(fillObject, fillId);
        SheetFillStore.WithCells found = store.findCells(fillObject, fillId)
                .orElseThrow(() -> refused("submit", Reason.NO_FILL));
        SheetFillStore.Row row = found.row();
        if (!FillStatus.valueOf(row.status()).fillerMayChange()) {
            throw refused("submit", Reason.NOT_FILLABLE);
        }
        if (actor != null && !actor.equals(row.createdBy())) {
            // A fill-in is its filler's own work: the person who is asked to review it, or one who may see every
            // fill-in, does not send it for them. The platform (no actor) may.
            throw refused("submit", Reason.NOT_THE_FILLER);
        }
        SheetVersionStore.WithGrid pinned = versions.findGrid(row.ownerObject(), row.ownerId(), row.versionNo())
                .orElseThrow(() -> refused("submit", Reason.NO_FILL));
        if ("RETIRED".equals(pinned.row().status())) {
            throw refused("submit", Reason.VERSION_RETIRED);
        }
        FillStructure structure = FillStructure.of(json.readTree(pinned.grid()));
        FillDoc doc = validator.read(found.cells());
        // What is stored passed the check when it was saved; the cells are judged again as complete, not as clean.
        List<FillValidator.Problem> missing = validator.completeness(structure, doc);
        if (!missing.isEmpty()) {
            FillValidator.Problem first = missing.get(0);
            Reason reason = "no_rows".equals(first.code()) ? Reason.NO_ROWS : Reason.INCOMPLETE;
            throw "no_rows".equals(first.code()) ? refused("submit", reason)
                    : refused("submit", reason, first.place());
        }
        String lost = requiredFileGone(structure, doc, fillId);
        if (lost != null) {
            throw refused("submit", Reason.INCOMPLETE, lost);
        }
        store.submit(fillObject, fillId, requestId, actor).orElseThrow(() -> refused("submit", Reason.NOT_FILLABLE));
        changes.submitted(actor, fillObject, fillId, row.versionNo(), requestId, row.rowCount());
    }

    /**
     * The place of the first required attachment cell that names no file that still exists, or null (SH5, ADR-0254):
     * a file that is gone does not satisfy a required cell, whatever the cell still says.
     */
    private String requiredFileGone(FillStructure structure, FillDoc doc, UUID fillId) {
        if (!structure.isFormatThree()) {
            return null;
        }
        List<String> named = FillValidator.attachmentIds(doc);
        Set<String> present = new java.util.HashSet<>();
        if (!named.isEmpty()) {
            directory.existing(fillId, named.stream().map(UUID::fromString).toList())
                    .forEach(id -> present.add(id.toString()));
        }
        for (int i = 0; i < doc.rows().size(); i++) {
            FillDoc.Row row = doc.rows().get(i);
            for (Map.Entry<String, Object> cell : row.cells().entrySet()) {
                if (cell.getValue() instanceof List<?> files && files.stream().noneMatch(present::contains)
                        && structure.effective(row.id(), cell.getKey()).required()) {
                    return structure.isNamedRow(row.id()) ? "cells.rows." + row.id() + ".cells." + cell.getKey()
                            : "cells.rows[" + i + "].cells." + cell.getKey();
                }
            }
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean emptiesRequiredAttachment(String fillObject, UUID fillId, UUID fileId) {
        Optional<SheetFillStore.WithCells> found = store.findCells(fillObject, fillId);
        if (found.isEmpty()) {
            return false;
        }
        FillStructure structure = structureOf(found.get().row());
        if (structure == null || !structure.isFormatThree()) {
            return false;
        }
        String id = fileId.toString();
        for (FillDoc.Row row : validator.read(found.get().cells()).rows()) {
            for (Map.Entry<String, Object> cell : row.cells().entrySet()) {
                if (cell.getValue() instanceof List<?> files && files.contains(id)
                        && files.stream().allMatch(id::equals)
                        && structure.effective(row.id(), cell.getKey()).required()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void forgetFile(String fillObject, UUID fillId, UUID fileId, UUID actor) {
        store.lock(fillObject, fillId);
        Optional<SheetFillStore.WithCells> found = store.findCells(fillObject, fillId);
        if (found.isEmpty() || found.get().row().archived()) {
            return;
        }
        SheetFillStore.Row row = found.get().row();
        FillStructure structure = structureOf(row);
        if (structure == null || !structure.isFormatThree()) {
            return;
        }
        String id = fileId.toString();
        FillDoc doc = validator.read(found.get().cells());
        List<FillDoc.Row> kept = new java.util.ArrayList<>();
        boolean changed = false;
        for (FillDoc.Row one : doc.rows()) {
            Map<String, Object> cells = new LinkedHashMap<>();
            for (Map.Entry<String, Object> cell : one.cells().entrySet()) {
                if (cell.getValue() instanceof List<?> files && files.contains(id)) {
                    changed = true;
                    List<String> rest = files.stream().map(String.class::cast).filter(file -> !id.equals(file))
                            .toList();
                    if (!rest.isEmpty()) {
                        cells.put(cell.getKey(), rest);
                    }
                } else {
                    cells.put(cell.getKey(), cell.getValue());
                }
            }
            if (!structure.isNamedRow(one.id()) || !cells.isEmpty() || !one.styles().isEmpty()) {
                kept.add(new FillDoc.Row(one.id(), cells, one.styles()));
            }
        }
        if (!changed) {
            return;
        }
        FillDoc next = new FillDoc(doc.formatVersion(), doc.sheetKey(), kept);
        String canonical = FillValidator.write(next);
        byte[] bytes = canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FillValidator.Checked checked = new FillValidator.Checked(next, canonical, SheetGridWriter.sha256(bytes),
                bytes.length);
        boolean draft = FillStatus.valueOf(row.status()).fillerMayChange();
        Optional<SheetFillStore.Row> saved = draft
                ? store.updateCells(fillObject, fillId, row.version(), checked, actor)
                : store.updateCellsCorrected(fillObject, fillId, row.version(), checked, actor);
        SheetFillStore.Row after = saved.orElseThrow(() -> refused("forget_file", Reason.INVALID));
        if (draft) {
            changes.saved(actor, fillObject, fillId, after.version(), checked);
        } else {
            changes.corrected(actor, fillObject, fillId, after.version(), after.corrections(), checked);
        }
    }

    private FillStructure structureOf(SheetFillStore.Row row) {
        return versions.findGrid(row.ownerObject(), row.ownerId(), row.versionNo())
                .map(pinned -> FillStructure.of(json.readTree(pinned.grid()))).orElse(null);
    }

    @Override
    public boolean ended(String fillObject, UUID fillId, UUID requestId, FillStatus result, UUID actor) {
        if (!ENDINGS.contains(result)) {
            throw new IllegalArgumentException("A review ends as approved, returned or back to a draft");
        }
        store.lock(fillObject, fillId);
        Optional<SheetFillStore.Row> moved = store.end(fillObject, fillId, requestId, result.name(), actor);
        if (moved.isPresent()) {
            changes.reviewed(actor, fillObject, fillId, result.name());
        }
        return moved.isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Pin> pinOf(String fillObject, UUID fillId) {
        return store.find(fillObject, fillId).map(row -> new Pin(row.ownerObject(), row.ownerId(), row.versionNo()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Summary> summaries(String fillObject, Collection<UUID> fillIds) {
        Map<UUID, Summary> found = new LinkedHashMap<>();
        for (SheetFillStore.Row row : store.summaries(fillObject, fillIds)) {
            found.put(row.fillId(), new Summary(row.versionNo(), FillStatus.valueOf(row.status()), row.rowCount(),
                    row.cellsBytes(), row.corrections(), row.createdAt(), row.updatedAt(), row.submittedAt(),
                    row.decidedAt(), row.createdBy(), row.archivedAt()));
        }
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Submission> submittedBetween(String fillObject, Instant from, Instant to, int limit) {
        return store.submittedBetween(fillObject, from, to, limit).stream().map(DefaultFillLifecycle::submission)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Submission> awaitingReview(String fillObject, int limit) {
        return store.awaitingReview(fillObject, limit).stream().map(DefaultFillLifecycle::submission).toList();
    }

    private static Submission submission(SheetFillStore.Row row) {
        return new Submission(row.fillId(), row.ownerId(), FillStatus.valueOf(row.status()), row.submittedAt(),
                row.approvalRequestId(), row.createdBy());
    }

    private FillRefusal refused(String operation, Reason reason) {
        changes.refused(operation, reason.name().toLowerCase(Locale.ROOT));
        return new FillRefusal(reason);
    }

    private FillRefusal refused(String operation, Reason reason, String place) {
        changes.refused(operation, reason.name().toLowerCase(Locale.ROOT));
        return new FillRefusal(reason, place);
    }
}
