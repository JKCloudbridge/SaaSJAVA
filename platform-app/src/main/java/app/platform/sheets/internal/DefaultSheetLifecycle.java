package app.platform.sheets.internal;

import app.platform.sheets.SheetLifecycle;
import app.platform.sheets.SheetMoveRefusal;
import app.platform.sheets.SheetMoveRefusal.Reason;
import app.platform.sheets.SheetStatus;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The moves of a version, for the module that owns the thing a sheet belongs to (FA4, ADR-0188). It is called inside
 * the caller's transaction: the submit and the end of a review run inside the approval engine's own, so a refusal here
 * undoes the decision that asked.
 *
 * <p>Every move first takes the lock of the sheet that starting a version takes, so two moves of one sheet (an approval
 * and a publish, two instances) are decided one after the other, and then names the row it means by its status: a move
 * that no longer applies finds nothing and says so. The database refuses the moves the lifecycle lacks whatever this
 * class does ({@code SheetVersionGuardIT}).
 */
@Service
@Transactional
class DefaultSheetLifecycle implements SheetLifecycle {

    private static final Set<SheetStatus> ENDINGS = Set.of(SheetStatus.APPROVED, SheetStatus.REJECTED,
            SheetStatus.WITHDRAWN);

    private final SheetVersionStore store;
    private final SheetGridValidator validator;
    private final SheetChanges changes;
    private final SheetProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultSheetLifecycle(SheetVersionStore store, SheetGridValidator validator, SheetChanges changes,
            SheetProperties properties) {
        this.store = store;
        this.validator = validator;
        this.changes = changes;
        this.properties = properties;
    }

    @Override
    public int submit(String ownerObject, UUID ownerId, UUID requestId, UUID actor) {
        store.lock(ownerObject, ownerId);
        SheetVersionStore.State state = store.state(ownerObject, ownerId);
        if (state.draft() == null) {
            throw refused("submit", Reason.NO_DRAFT);
        }
        SheetVersionStore.WithGrid draft = store.findGrid(ownerObject, ownerId, state.draft()).orElseThrow();
        // The stored grid is checked again as it is now: the limits are settings and may have been lowered since it was
        // saved, and a draft must be a sheet the fillers can use.
        SheetGridValidator.Result result = validator.check(draft.grid().getBytes(StandardCharsets.UTF_8));
        if (!result.accepted()) {
            SheetGridValidator.Problem first = result.problems().get(0);
            throw refused("submit", Reason.INVALID, first.message() + " (" + first.place() + ")");
        }
        if (empty(result.checked().grid())) {
            throw refused("submit", Reason.EMPTY);
        }
        SheetVersionStore.Row moved;
        try {
            moved = store.submit(ownerObject, ownerId, requestId, actor).orElseThrow(
                    () -> refused("submit", Reason.NO_DRAFT));
        } catch (DuplicateKeyException e) {
            // The request engine allows one pending request per record, so this is the database's backstop only.
            throw refused("submit", Reason.ALREADY_IN_REVIEW);
        }
        changes.submitted(actor, ownerObject, ownerId, moved.versionNo(), requestId, moved.gridBytes());
        return moved.versionNo();
    }

    @Override
    public boolean reviewed(String ownerObject, UUID ownerId, UUID requestId, SheetStatus result, UUID actor) {
        if (!ENDINGS.contains(result)) {
            throw new IllegalArgumentException("A review ends as approved, rejected or withdrawn");
        }
        store.lock(ownerObject, ownerId);
        Optional<SheetVersionStore.Row> moved = store.end(ownerObject, ownerId, requestId, result.name(), actor);
        moved.ifPresent(row -> changes.reviewed(actor, ownerObject, ownerId, row.versionNo(), result.name()));
        return moved.isPresent();
    }

    @Override
    public int publish(String ownerObject, UUID ownerId, int versionNo, UUID actor) {
        store.lock(ownerObject, ownerId);
        Optional<SheetVersionStore.Row> wanted = store.find(ownerObject, ownerId, versionNo);
        if (wanted.isEmpty() || !SheetStatus.APPROVED.name().equals(wanted.get().status())) {
            throw refused("publish", Reason.NOT_APPROVED);
        }
        // The one that is in use goes first: the database allows one active version and checks it at every statement.
        int replaced = store.supersedeActive(ownerObject, ownerId, SheetStatus.SUPERSEDED.name(), actor).orElse(0);
        store.activate(ownerObject, ownerId, versionNo, actor).orElseThrow(
                () -> refused("publish", Reason.NOT_APPROVED));
        changes.published(actor, ownerObject, ownerId, versionNo, replaced);
        return replaced;
    }

    @Override
    public int unpublish(String ownerObject, UUID ownerId, UUID actor) {
        store.lock(ownerObject, ownerId);
        int version = store.supersedeActive(ownerObject, ownerId, SheetStatus.APPROVED.name(), actor).orElseThrow(
                () -> refused("unpublish", Reason.NOTHING_ACTIVE));
        changes.unpublished(actor, ownerObject, ownerId, version);
        return version;
    }

    @Override
    public int retire(String ownerObject, UUID ownerId, UUID actor) {
        store.lock(ownerObject, ownerId);
        int version = store.supersedeActive(ownerObject, ownerId, SheetStatus.RETIRED.name(), actor).orElse(0);
        if (version != 0) {
            changes.retired(actor, ownerObject, ownerId, version);
        }
        return version;
    }

    @Override
    public Started startDraft(String ownerObject, UUID ownerId, int basedOn, UUID actor) {
        store.lock(ownerObject, ownerId);
        SheetVersionStore.State state = store.state(ownerObject, ownerId);
        if (state.draft() != null) {
            // One draft at a time and none is ever deleted: the caller goes on with the one that exists.
            return new Started(state.draft(), false);
        }
        if (state.count() >= properties.limits().maxVersionsPerOwner()) {
            throw refused("start", Reason.TOO_MANY_VERSIONS);
        }
        int versionNo = state.highest() + 1;
        SheetVersionStore.Row row = store.insertCopy(ownerObject, ownerId, versionNo, basedOn, actor)
                .orElseThrow(() -> refused("start", Reason.NO_VERSION));
        changes.created(actor, ownerObject, ownerId, row.versionNo(), basedOn, row.dimension());
        return new Started(row.versionNo(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SheetStatus> statusOf(String ownerObject, UUID ownerId, int versionNo) {
        return store.find(ownerObject, ownerId, versionNo).map(row -> SheetStatus.valueOf(row.status()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Layout> layoutOf(String ownerObject, UUID ownerId, int versionNo) {
        return store.findGrid(ownerObject, ownerId, versionNo).map(found -> {
            // The stored grid passed the schema when it was saved, so the reader may trust its shape.
            SheetGrid grid = SheetGridReader.read(json.readTree(found.grid()));
            Map<String, String> columns = new LinkedHashMap<>();
            grid.columns().forEach(column -> columns.put(column.id(), label(column.label())));
            Map<String, String> rows = new LinkedHashMap<>();
            grid.headerRows().forEach(line -> rows.put(line.id(), label(line.label())));
            grid.rows().forEach(line -> rows.put(line.id(), label(line.label())));
            return new Layout(SheetStatus.valueOf(found.row().status()), columns, rows);
        });
    }

    private static String label(String text) {
        return text == null ? "" : text;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Summary> summaries(String ownerObject, Collection<UUID> ownerIds) {
        Map<UUID, Summary> found = new LinkedHashMap<>();
        for (SheetVersionStore.Summary row : store.summaries(ownerObject, ownerIds)) {
            found.put(row.owner(), new Summary(row.active(), row.approved(), row.inReview(), row.draft(),
                    row.retired(), row.highest(), row.total()));
        }
        return found;
    }

    /**
     * Whether the grid still is what a new sheet starts as: one column and nothing else. A sheet with a second column,
     * a header band, a named row or a cell has been worked on.
     */
    static boolean empty(SheetGrid grid) {
        return grid.columns().size() <= 1 && grid.headerRows().isEmpty() && grid.rows().isEmpty()
                && grid.cells().isEmpty();
    }

    private SheetMoveRefusal refused(String operation, Reason reason) {
        changes.refused(operation, reason.name().toLowerCase(java.util.Locale.ROOT));
        return new SheetMoveRefusal(reason);
    }

    private SheetMoveRefusal refused(String operation, Reason reason, String detail) {
        changes.refused(operation, reason.name().toLowerCase(java.util.Locale.ROOT));
        return new SheetMoveRefusal(reason, detail);
    }
}
