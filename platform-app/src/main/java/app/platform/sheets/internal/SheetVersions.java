package app.platform.sheets.internal;

import app.platform.sharedkernel.approval.RecordLocks;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sharedkernel.solution.SheetDraftRules;
import app.platform.sheets.Revisions;
import app.platform.sheets.SheetStatus;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.NewSheetVersionRequest;
import app.platformapi.SheetCalculatedAnswerView;
import app.platformapi.SheetCalculatedCellView;
import app.platformapi.SheetCalculatedColumnView;
import app.platformapi.SheetCalculationView;
import app.platformapi.SheetLimitsView;
import app.platformapi.SheetPrintView;
import app.platformapi.SheetVersionSummaryView;
import app.platformapi.SheetVersionView;
import app.platformapi.SheetVersionsView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The versions of a sheet (FA3, ADR-0173, ADR-0174, ADR-0186): list them, read one, start a draft and save it.
 *
 * <p>Who may do what is {@link SheetOwners}'s answer about the record the sheet belongs to. A member who may read the
 * record sees the versions that are in use or were; a member who may change it sees every version, drafts included, so
 * a share of the record can never show a draft (ADR-0174 section 3). A version the member may not see answers like a
 * version that does not exist.
 *
 * <p>This release creates and changes drafts only. A version that is not a draft is never changed (the database says
 * so too), and no request here moves a version to another status.
 *
 * <p>Versions are numbered by the application, the highest plus one, under a lock on the sheet; the database refuses a
 * second draft and a repeated number whatever the application does. Nothing is ever deleted, so a number is never
 * reused and a sheet is bounded by {@code max-versions-per-owner}.
 */
@Service
@Transactional
class SheetVersions {

    private static final UUID PLATFORM_ACTOR = new UUID(0L, 0L);
    private static final String NO_SHEET = "This sheet does not exist.";
    private static final String NO_VERSION = "This revision does not exist.";
    private static final TypeReference<Map<String, Object>> GRID = new TypeReference<>() {
    };

    private final SheetOwners owners;
    private final SheetVersionStore store;
    private final SheetGridValidator validator;
    private final SheetChanges changes;
    private final SheetProperties properties;
    private final ImportProperties importProperties;
    private final RecordLocks locks;
    private final List<ReferenceLabels> directories;
    private final ObjectProvider<SheetDraftRules> draftRules;
    private final FormulaProperties formulas;
    private final JsonMapper json = JsonMapper.builder().build();

    SheetVersions(SheetOwners owners, SheetVersionStore store, SheetGridValidator validator, SheetChanges changes,
            SheetProperties properties, ImportProperties importProperties, RecordLocks locks,
            List<ReferenceLabels> directories, ObjectProvider<SheetDraftRules> draftRules,
            FormulaProperties formulas) {
        this.draftRules = draftRules;
        this.formulas = formulas;
        this.owners = owners;
        this.store = store;
        this.validator = validator;
        this.changes = changes;
        this.properties = properties;
        this.importProperties = importProperties;
        this.locks = locks;
        this.directories = directories;
    }

    /**
     * The versions this member sees, newest first, without their grids. A person who may not read the record but is
     * asked to approve a version of it sees that one version and nothing else (ADR-0189).
     */
    @Transactional(readOnly = true)
    SheetVersionsView list(String ownerObject, UUID ownerId) {
        Optional<SheetOwners.Reach> reach = owners.reachIfReadable(ownerObject, ownerId);
        List<SheetVersionStore.Row> rows;
        boolean canChange;
        boolean canChangeHeld = false;
        if (reach.isPresent()) {
            rows = store.list(ownerObject, ownerId, reach.get().canChange(), reach.get().history());
            canChange = reach.get().canChange();
            SheetOwners.Caller caller = owners.caller(ownerObject);
            Optional<SheetVersionStore.Row> held = store.heldDraft(ownerObject, ownerId)
                    .filter(draft -> owners.mayReadHeld(caller, draft));
            canChangeHeld = held.isPresent();
            if (!canChange && held.isPresent()) {
                // A person asked to decide the request a draft was made for sees that draft, whatever else they read.
                List<SheetVersionStore.Row> shown = new ArrayList<>(rows);
                shown.add(0, held.get());
                rows = shown;
            }
        } else {
            SheetOwners.Caller caller = owners.caller(ownerObject);
            rows = store.list(ownerObject, ownerId, true, true).stream()
                    .filter(row -> underReviewOf(caller, row)).toList();
            if (rows.isEmpty()) {
                throw ApiException.notFound(NO_SHEET);
            }
            canChange = false;
            canChangeHeld = rows.stream().anyMatch(row -> SheetStatus.DRAFT.name().equals(row.status()));
        }
        Map<UUID, String> names = names(rows.stream().map(SheetVersionStore.Row::updatedBy).toList());
        List<SheetVersionSummaryView> versions = rows.stream().map(row -> summary(row, names)).toList();
        SheetProperties.Limits limits = properties.limits();
        return new SheetVersionsView(versions, canChange, new SheetLimitsView(limits.maxBytes(),
                limits.maxRows(), limits.maxColumns(), limits.maxHeaderRows(), limits.maxCellChars(),
                limits.maxLabelChars(), limits.maxPicklistValues(), limits.maxMerges(),
                importProperties.maxFileBytes(), limits.maxFixedTextChars(), limits.minColumnWidth(),
                limits.maxColumnWidth(), limits.minRowHeight(), limits.maxRowHeight(), limits.maxSheets(),
                limits.maxFilesPerCell(), limits.maxLinkChars(), properties.formatTwo().enabled(),
                formulas.view()), canChangeHeld);
    }

    /**
     * The answers of the formulas of a version, calculated from the sheet alone (SH2, ADR-0252): every input cell is
     * empty, and a sheet where the filler adds all the rows is calculated for one row. Who may read the version is the
     * one rule of {@link #readable}. Nothing is stored and nothing is logged.
     */
    @Transactional(readOnly = true)
    SheetCalculationView calculation(String ownerObject, UUID ownerId, int versionNo) {
        SheetVersionStore.WithGrid found = readable(ownerObject, ownerId, versionNo);
        tools.jackson.databind.JsonNode stored = json.readTree(found.grid());
        if (!SheetGridValidator.isFormatTwo(stored)) {
            return new SheetCalculationView(0, List.of(), List.of());
        }
        SheetDocument.Tab tab = SheetDocumentReader.read(stored).sheets().get(0);
        int rows = tab.body().dimension() == 1 ? 1 : 0;
        SheetCalculator.Outcome outcome = SheetCalculator.calculate(tab, SheetCalculator.Inputs.emptyRows(rows),
                formulas.limits());
        List<SheetCalculatedCellView> cells = new ArrayList<>();
        outcome.cells().forEach((cell, answer) -> cells.add(new SheetCalculatedCellView(cell, view(answer))));
        List<SheetCalculatedColumnView> columns = new ArrayList<>();
        outcome.columns().forEach((column, answers) -> columns.add(new SheetCalculatedColumnView(column,
                answers.stream().map(SheetVersions::view).toList())));
        return new SheetCalculationView(rows, cells, columns);
    }

    private static SheetCalculatedAnswerView view(SheetCalculator.Answer answer) {
        return new SheetCalculatedAnswerView(answer.kind().json(), answer.value());
    }

    /** One version with its grid. */
    @Transactional(readOnly = true)
    SheetVersionView get(String ownerObject, UUID ownerId, int versionNo) {
        SheetVersionStore.WithGrid found = readable(ownerObject, ownerId, versionNo);
        JsonNode stored = json.readTree(found.grid());
        app.platform.sheets.SheetPages.PrintSettings page = PrintRules.settings(stored);
        return new SheetVersionView(summaryOf(found.row()), json.convertValue(stored, GRID),
                new SheetPrintView(page.paper(), page.orientation(), page.pageWidthMm(), page.pageHeightMm(),
                        page.marginLeft(), page.marginRight(), page.marginTop(), page.marginBottom()));
    }

    /**
     * One version with its grid as the member may see it: the one rule of who reads a version, used by the read of
     * the screen and by the file a template is downloaded as (FA7), so there is never a second rule.
     *
     * @throws ApiException {@code NOT_FOUND} for a version that does not exist and for one the member may not see
     */
    @Transactional(readOnly = true)
    SheetVersionStore.WithGrid readable(String ownerObject, UUID ownerId, int versionNo) {
        Optional<SheetOwners.Reach> reach = owners.reachIfReadable(ownerObject, ownerId);
        SheetOwners.Caller caller = owners.caller(ownerObject);
        return store.findGrid(ownerObject, ownerId, versionNo)
                .filter(version -> reach.map(r -> visible(r, version.row())).orElse(false)
                        || underReviewOf(caller, version.row()))
                .orElseThrow(() -> ApiException.notFound(NO_VERSION));
    }

    /**
     * Starts a new draft: a blank first version in the shape asked for, or a copy of another version.
     *
     * @throws ApiException {@code NOT_FOUND}, {@code FORBIDDEN}, {@code VALIDATION_ERROR} (the shape, or a version to
     *         copy that does not exist), {@code CONFLICT} (a draft already exists, with its number; the most versions
     * a sheet may have) */
    SheetVersionSummaryView create(String ownerObject, UUID ownerId, NewSheetVersionRequest request) {
        SheetOwners.Reach reach = owners.reachForChange(ownerObject, ownerId);
        store.lock(reach.object(), reach.id());
        SheetVersionStore.State state = store.state(reach.object(), reach.id());
        String against = refusedByOwner(reach.object(), reach.id());
        if (against != null) {
            changes.refused("create", "owner_rule");
            throw new ApiException(ErrorCode.CONFLICT, against);
        }
        if (state.draft() != null) {
            changes.refused("create", "draft_exists");
            throw new ApiException(ErrorCode.CONFLICT, "A draft already exists: it is " + Revisions.words(state.draft())
                    + ". Continue with it.");
        }
        if (state.count() >= properties.limits().maxVersionsPerOwner()) {
            changes.refused("create", "most_versions");
            throw new ApiException(ErrorCode.CONFLICT, "This sheet has the most revisions it can have ("
                    + properties.limits().maxVersionsPerOwner() + ").");
        }
        int versionNo = state.highest() + 1;
        SheetVersionStore.Row row;
        int basedOn = 0;
        try {
            if (state.count() == 0) {
                row = firstVersion(reach, request, versionNo);
            } else {
                basedOn = baseOf(reach, request);
                if (SheetFormats.copyRefused(properties, store, json, reach.object(), reach.id(), basedOn)) {
                    changes.refused("create", "format_two_off");
                    throw new ApiException(ErrorCode.CONFLICT, SheetFormats.EARLIER_FORMAT);
                }
                row = store.insertCopy(reach.object(), reach.id(), versionNo, basedOn, reach.userId())
                        .orElseThrow(() -> ApiException.validation("basedOn", "There is no such revision to copy."));
            }
        } catch (DuplicateKeyException e) {
            // The lock makes this unreachable through this class; the database's indexes are the backstop.
            changes.refused("create", "draft_exists");
            throw new ApiException(ErrorCode.CONFLICT, "A draft already exists. Reload and continue with it.");
        }
        changes.created(reach.userId(), reach.object(), reach.id(), row.versionNo(), basedOn, row.dimension());
        return summaryOf(row);
    }

    /**
     * Saves the grid of a draft. The request carries the row version that was read: a draft changed since is
     * {@code CONCURRENT_MODIFICATION} and nothing changes.
     *
     * @param raw the bytes of the grid as they arrived (their size has been bounded by the caller)
     * @throws ApiException {@code NOT_FOUND}, {@code FORBIDDEN}, {@code VALIDATION_ERROR} (every place of the first
     *         stage that found problems), {@code CONFLICT} (the version is no longer a draft),
     *         {@code CONCURRENT_MODIFICATION}
     */
    SheetVersionSummaryView save(String ownerObject, UUID ownerId, int versionNo, long version, byte[] raw) {
        owners.caller(ownerObject);
        Optional<SheetVersionStore.Row> looked = store.find(ownerObject, ownerId, versionNo);
        // A draft held for an approval request is changed by the people asked to decide it (FA8, ADR-0202); any other
        // by whoever may change the record.
        SheetOwners.Reach reach = looked.filter(row -> SheetStatus.DRAFT.name().equals(row.status())
                && row.approvalRequestId() != null).isPresent()
                ? owners.reachForDraft(ownerObject, ownerId, looked.get())
                : owners.reachForChange(ownerObject, ownerId);
        // The cheap questions first: a version that does not exist or is no longer a draft is answered before a grid
        // is parsed for it.
        SheetVersionStore.Row current = store.find(reach.object(), reach.id(), versionNo)
                .orElseThrow(() -> ApiException.notFound(NO_VERSION));
        boolean inReview = SheetStatus.IN_REVIEW.name().equals(current.status());
        if (inReview) {
            // The product owner's rule (decision 13): while a version is being reviewed only an approver may change
            // it, audited. The creator who submitted it may not.
            if (!owners.mayCorrectInReview(reach)) {
                changes.refused("save", "in_review");
                throw new ApiException(ErrorCode.FORBIDDEN, "A revision that is in review can be changed only by the "
                        + "people who approve it.");
            }
        } else if (!SheetStatus.DRAFT.name().equals(current.status())) {
            changes.refused("save", "not_draft");
            throw notADraft();
        }
        SheetGridValidator.Result result = validator.check(raw);
        if (!result.accepted()) {
            changes.refused("save", "invalid");
            throw ApiException.validation(places(result.problems()));
        }
        SheetGridValidator.Checked checked = result.checked();
        boolean sameShape = current.dimension() == checked.grid().dimension()
                && current.rowsMode().equals(checked.grid().rowsMode().name());
        if (!sameShape && (versionNo != 1 || inReview)) {
            changes.refused("save", "shape");
            throw ApiException.validation("grid.dimension", "The shape of a sheet is chosen with its first revision "
                    + "and cannot change.");
        }
        if (inReview && SheetGridWriter.hex(checked.hash()).equals(current.gridHash())
                && version == current.version()) {
            // Nothing to correct: the same grid is not a correction, and counting it would be a lie (the database
            // counts a review edit only when the grid changes). The answer is the version as it is.
            changes.refused("save", "unchanged");
            return summaryOf(current);
        }
        SheetVersionStore.Row saved;
        try {
            saved = (inReview
                    ? store.updateGridInReview(reach.object(), reach.id(), versionNo, version, checked, reach.userId())
                    : store.updateGrid(reach.object(), reach.id(), versionNo, version, checked, reach.userId()))
                    .orElse(null);
        } catch (DuplicateKeyException e) {
            changes.refused("save", "conflict");
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (saved == null) {
            // Changed since it was read, or left draft between the read above and the update.
            Optional<SheetVersionStore.Row> now = store.find(reach.object(), reach.id(), versionNo);
            changes.refused("save", "conflict");
            if (now.isPresent() && !now.get().status().equals(current.status())) {
                throw notADraft();
            }
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (inReview) {
            // Into the history of the request and the audit trail, by the member and the record, never a value.
            locks.overridden(reach.object(), reach.id(), reach.userId());
            changes.reviewEdited(reach.userId(), reach.object(), reach.id(), versionNo, saved.version(),
                    saved.reviewEdits(), checked);
        } else {
            changes.saved(reach.userId(), reach.object(), reach.id(), versionNo, saved.version(), checked);
        }
        return summaryOf(saved);
    }

    // ---- rules ----

    /** The first version is blank and names its shape. */
    private SheetVersionStore.Row firstVersion(SheetOwners.Reach reach, NewSheetVersionRequest request, int number) {
        if (request.basedOn() != null) {
            throw ApiException.validation("basedOn", "There is no revision to copy yet.");
        }
        if (request.dimension() == null) {
            throw ApiException.validation("dimension", "Choose 1 (the filler adds the rows) or 2 (the sheet names "
                    + "the rows).");
        }
        RowsMode mode = request.rowsMode() == null
                ? (request.dimension() == 1 ? RowsMode.REPEATABLE : RowsMode.FIXED)
                : RowsMode.of(request.rowsMode()).orElseThrow(() -> ApiException.validation("rowsMode",
                        "Choose FIXED, REPEATABLE or FIXED_AND_REPEATABLE."));
        SheetGridValidator.Result result = validator.check(BlankGrids.of(request.dimension(), mode));
        if (!result.accepted()) {
            // The only way a blank grid is refused is a shape that does not go with the dimension.
            throw ApiException.validation("rowsMode", result.problems().get(0).message());
        }
        return store.insert(reach.object(), reach.id(), number, result.checked(), reach.userId());
    }

    /** The version a new draft is copied from; the shape of the sheet cannot be changed by asking for another. */
    private int baseOf(SheetOwners.Reach reach, NewSheetVersionRequest request) {
        SheetVersionStore.Row first = store.first(reach.object(), reach.id()).orElseThrow();
        if (request.dimension() != null && request.dimension() != first.dimension()) {
            throw ApiException.validation("dimension", "The shape of a sheet is chosen with its first revision and "
                    + "cannot change.");
        }
        if (request.rowsMode() != null && !request.rowsMode().equals(first.rowsMode())) {
            throw ApiException.validation("rowsMode", "The shape of a sheet is chosen with its first revision and "
                    + "cannot change.");
        }
        if (request.basedOn() != null) {
            return request.basedOn();
        }
        return store.defaultBase(reach.object(), reach.id()).orElseThrow();
    }

    /**
     * What a member who may read the record sees (FA8, ADR-0202): the version in use, and the one that ended with the
     * owner; the versions that were replaced only for the people who keep the history. A member who may change the
     * record sees all of them.
     */
    private static boolean visible(SheetOwners.Reach reach, SheetVersionStore.Row row) {
        if (reach.canChange()) {
            return true;
        }
        SheetStatus status = SheetStatus.valueOf(row.status());
        return status == SheetStatus.ACTIVE || status == SheetStatus.RETIRED
                || (status == SheetStatus.SUPERSEDED && reach.history());
    }

    /**
     * Whether the person is asked to decide the request that reviews this version (still in review), or the request a
     * draft was made for.
     */
    private boolean underReviewOf(SheetOwners.Caller caller, SheetVersionStore.Row row) {
        if (SheetStatus.DRAFT.name().equals(row.status())) {
            return owners.mayReadHeld(caller, row);
        }
        return SheetStatus.IN_REVIEW.name().equals(row.status())
                && owners.holdsOpenTask(row.approvalRequestId(), caller.userId());
    }

    /** The fixed words of the first rule of the owner's module that is against a new draft, or null. */
    String refusedByOwner(String ownerObject, UUID ownerId) {
        return draftRules.orderedStream().map(rule -> rule.refuseNewDraft(ownerObject, ownerId))
                .flatMap(Optional::stream).findFirst().orElse(null);
    }

    private static ApiException notADraft() {
        return new ApiException(ErrorCode.CONFLICT, "This revision is no longer a draft and cannot be changed. "
                + "Start a new revision from it.");
    }

    /** The problems of the first stage that found any, as places with the words for each. */
    private static Map<String, List<String>> places(List<SheetGridValidator.Problem> problems) {
        Map<String, List<String>> places = new LinkedHashMap<>();
        for (SheetGridValidator.Problem problem : problems) {
            places.computeIfAbsent(problem.place(), place -> new ArrayList<>()).add(problem.message());
        }
        return places;
    }

    SheetVersionSummaryView summaryOf(SheetVersionStore.Row row) {
        return summary(row, names(java.util.Collections.singletonList(row.updatedBy())));
    }

    private static SheetVersionSummaryView summary(SheetVersionStore.Row row, Map<UUID, String> names) {
        return new SheetVersionSummaryView(row.versionNo(), row.status(), row.dimension(), row.rowsMode(),
                row.gridBytes(), row.gridHash(), row.version(), row.createdAt(), row.updatedAt(), row.submittedAt(),
                row.decidedAt(), row.reviewEdits(), row.updatedBy() == null ? null : names.get(row.updatedBy()),
                row.revisionNo(), SheetStatus.DRAFT.name().equals(row.status()) && row.approvalRequestId() != null);
    }

    /**
     * The display names of the people who last changed versions: active members only, through the same contract the
     * other lists use. A person who left, and the platform's own actor, get none (the screen shows a dash); an
     * identifier is never sent.
     */
    private Map<UUID, String> names(Collection<UUID> actors) {
        Set<UUID> people = new HashSet<>();
        for (UUID actor : actors) {
            if (actor != null && !PLATFORM_ACTOR.equals(actor)) {
                people.add(actor);
            }
        }
        if (people.isEmpty()) {
            return Map.of();
        }
        return directories.stream().filter(directory -> "User".equals(directory.object())).findFirst()
                .map(directory -> directory.labels(people)).orElse(Map.of());
    }
}
