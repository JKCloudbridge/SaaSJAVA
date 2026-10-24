package app.platform.sheets.internal;

import app.platform.sharedkernel.approval.RecordLocks;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sheets.SheetStatus;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.NewSheetVersionRequest;
import app.platformapi.SheetLimitsView;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
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
    private static final String NO_VERSION = "This version does not exist.";
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
    private final JsonMapper json = JsonMapper.builder().build();

    SheetVersions(SheetOwners owners, SheetVersionStore store, SheetGridValidator validator, SheetChanges changes,
            SheetProperties properties, ImportProperties importProperties, RecordLocks locks,
            List<ReferenceLabels> directories) {
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
        if (reach.isPresent()) {
            rows = store.list(ownerObject, ownerId, reach.get().canChange());
            canChange = reach.get().canChange();
        } else {
            SheetOwners.Caller caller = owners.caller(ownerObject);
            rows = store.list(ownerObject, ownerId, true).stream()
                    .filter(row -> underReviewOf(caller, row)).toList();
            if (rows.isEmpty()) {
                throw ApiException.notFound(NO_SHEET);
            }
            canChange = false;
        }
        Map<UUID, String> names = names(rows.stream().map(SheetVersionStore.Row::updatedBy).toList());
        List<SheetVersionSummaryView> versions = rows.stream().map(row -> summary(row, names)).toList();
        SheetProperties.Limits limits = properties.limits();
        return new SheetVersionsView(versions, canChange, new SheetLimitsView(limits.maxBytes(),
                limits.maxRows(), limits.maxColumns(), limits.maxHeaderRows(), limits.maxCellChars(),
                limits.maxLabelChars(), limits.maxPicklistValues(), limits.maxMerges(),
                importProperties.maxFileBytes()));
    }

    /** One version with its grid. */
    @Transactional(readOnly = true)
    SheetVersionView get(String ownerObject, UUID ownerId, int versionNo) {
        Optional<SheetOwners.Reach> reach = owners.reachIfReadable(ownerObject, ownerId);
        SheetOwners.Caller caller = owners.caller(ownerObject);
        SheetVersionStore.WithGrid found = store.findGrid(ownerObject, ownerId, versionNo)
                .filter(version -> reach.map(r -> visible(r, version.row())).orElse(false)
                        || underReviewOf(caller, version.row()))
                .orElseThrow(() -> ApiException.notFound(NO_VERSION));
        return new SheetVersionView(summaryOf(found.row()), json.readValue(found.grid(), GRID));
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
        if (state.draft() != null) {
            changes.refused("create", "draft_exists");
            throw new ApiException(ErrorCode.CONFLICT, "A draft already exists: it is version " + state.draft()
                    + ". Continue with it.");
        }
        if (state.count() >= properties.limits().maxVersionsPerOwner()) {
            changes.refused("create", "most_versions");
            throw new ApiException(ErrorCode.CONFLICT, "This sheet has the most versions it can have ("
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
                row = store.insertCopy(reach.object(), reach.id(), versionNo, basedOn, reach.userId())
                        .orElseThrow(() -> ApiException.validation("basedOn", "There is no such version to copy."));
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
        SheetOwners.Reach reach = owners.reachForChange(ownerObject, ownerId);
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
                throw new ApiException(ErrorCode.FORBIDDEN, "A version that is in review can be changed only by the "
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
            throw ApiException.validation("grid.dimension", "The shape of a sheet is chosen with its first version "
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
            throw ApiException.validation("basedOn", "There is no version to copy yet.");
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
            throw ApiException.validation("dimension", "The shape of a sheet is chosen with its first version and "
                    + "cannot change.");
        }
        if (request.rowsMode() != null && !request.rowsMode().equals(first.rowsMode())) {
            throw ApiException.validation("rowsMode", "The shape of a sheet is chosen with its first version and "
                    + "cannot change.");
        }
        if (request.basedOn() != null) {
            return request.basedOn();
        }
        return store.defaultBase(reach.object(), reach.id()).orElseThrow();
    }

    private static boolean visible(SheetOwners.Reach reach, SheetVersionStore.Row row) {
        return reach.canChange() || SheetStatus.valueOf(row.status()).readableByReaders();
    }

    /** Whether the person is asked to decide the request that reviews this version, which is still in review. */
    private boolean underReviewOf(SheetOwners.Caller caller, SheetVersionStore.Row row) {
        return SheetStatus.IN_REVIEW.name().equals(row.status())
                && owners.holdsOpenTask(row.approvalRequestId(), caller.userId());
    }

    private static ApiException notADraft() {
        return new ApiException(ErrorCode.CONFLICT, "This version is no longer a draft and cannot be changed. "
                + "Start a new version from it.");
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
                row.decidedAt(), row.reviewEdits(), row.updatedBy() == null ? null : names.get(row.updatedBy()));
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
