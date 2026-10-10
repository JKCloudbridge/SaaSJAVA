package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.ObjectUsage;
import app.platformapi.ApiException;
import app.platformapi.ChangeRequest;
import app.platformapi.ChangeSetReportView;
import app.platformapi.ChangeSetView;
import app.platformapi.ChangeView;
import app.platformapi.CreateChangeSetRequest;
import app.platformapi.ErrorCode;
import app.platformapi.ObjectView;
import app.platformapi.ProblemView;
import app.platformapi.ReleaseItemView;
import app.platformapi.ReleaseView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lifecycle of published metadata (ADR-0066, ADR-0067): change sets that are drafted, checked, previewed and
 * published all together; a single change made at once, which is a publication of one change; the history of
 * publications and the rollback of the latest one.
 *
 * <p>One idea carries all of it: a change is applied by the same rules as always ({@link ChangeApplier}), then the
 * dependency graph of the result is checked ({@link DependencyGraph}), and the transaction decides. Publishing commits
 * it. Checking and previewing roll it back, so a draft never touches the catalogue, the cache or the security version
 * and there is no second copy of the rules to keep equal. It runs inside the transaction that
 * {@link MetadataAdministration} opened after checking the caller's abilities; every publication takes the
 * organization's publication lock first, so publications happen one at a time and releases are numbered without gaps.
 */
@Service
class MetadataLifecycle {

    /** The most releases the history shows. */
    static final int HISTORY_LIMIT = 200;

    /**
     * One reason a publication cannot go ahead.
     *
     * @param kind RULE, DEPENDENCY, CONFLICT or RECORDS
     * @param position the change it comes from (1 is first), or null
     * @param object the object it is about
     * @param item the field or record type, or null
     * @param dependent what depends on it, named precisely, or null
     * @param message what is wrong, in words
     * @param fields for a rule problem, the fields of the request that are wrong
     */
    record Problem(String kind, Integer position, String object, String item, String dependent, String message,
            List<String> fields) {

        ProblemView view() {
            return new ProblemView(kind, position, object, item, dependent, message, fields);
        }
    }

    /** What applying a list of changes found and did. */
    private record Outcome(List<Problem> problems, List<ReleaseItemView> items, List<Change> undo,
            Set<String> touched) {
    }

    private final LifecycleStore store;
    private final ChangeApplier applier;
    private final ChangeCodec codec;
    private final MetadataCatalogue catalogue;
    private final MetadataService metadata;
    private final MetadataAudit audit;
    private final MetadataProperties properties;
    private final List<DependencyGraph.Contributor> contributors;
    private final ObjectProvider<ObjectUsage> usage;
    private final TransactionTemplate nested;

    MetadataLifecycle(LifecycleStore store, ChangeApplier applier, ChangeCodec codec, MetadataCatalogue catalogue,
            MetadataService metadata, MetadataAudit audit, MetadataProperties properties,
            List<DependencyGraph.Contributor> contributors, ObjectProvider<ObjectUsage> usage,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.applier = applier;
        this.codec = codec;
        this.catalogue = catalogue;
        this.metadata = metadata;
        this.audit = audit;
        this.properties = properties;
        this.contributors = contributors;
        this.usage = usage;
        this.nested = new TransactionTemplate(transactions,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_NESTED));
    }

    // ---- a single change, made at once ----

    /**
     * Applies one change now: the same rules, then the dependency check of the result, then a release of one change.
     * A rule that refuses is reported exactly as it always was; a dependency that would break is reported with what
     * depends on it.
     */
    <T> T applyNow(Caller caller, Change change, Class<T> type) {
        store.lockPublications();
        ChangeApplier.Applied applied = applier.apply(caller, change);
        List<Problem> broken = dependencyProblems();
        if (!broken.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, broken.get(0).message()
                    + (broken.size() > 1 ? " (and " + (broken.size() - 1) + " more)" : ""),
                    Map.of("dependencies", broken.stream().map(Problem::message).toList()));
        }
        release(caller, "QUICK", null, null, applied.items(), applied.undo());
        return type.cast(applied.result());
    }

    // ---- change sets ----

    ChangeSetView createSet(Caller caller, CreateChangeSetRequest request) {
        String name = NameRules.label("name", request.name());
        String description = NameRules.description(request.description());
        if (store.countOpenSets() >= properties.limits().maxOpenChangeSets()) {
            throw new ApiException(ErrorCode.CONFLICT, "The organization has reached the most open change sets it "
                    + "may have (" + properties.limits().maxOpenChangeSets() + "). Publish or discard some first.");
        }
        if (store.openSetNameTaken(name)) {
            throw ApiException.validation("name", "An open change set with this name exists already.");
        }
        UUID id = store.insertSet(name, description, caller.userId());
        audit.changeSetCreated(caller.userId(), id);
        return setView(openOrAny(id), true);
    }

    List<ChangeSetView> sets() {
        return store.sets().stream().map(row -> setView(row, false)).toList();
    }

    ChangeSetView set(UUID id) {
        return setView(openOrAny(id), true);
    }

    ChangeSetView addChange(Caller caller, UUID id, ChangeRequest request) {
        LifecycleStore.SetRow set = draft(id);
        if (set.changeCount() >= properties.limits().maxChangesPerSet()) {
            throw new ApiException(ErrorCode.CONFLICT, "The change set has reached the most changes it may hold ("
                    + properties.limits().maxChangesPerSet() + ").");
        }
        Change change = codec.fromRequest(request);
        int position = store.nextPosition(id);
        store.insertChange(id, position, change, caller.userId());
        audit.changeSetChanged(caller.userId(), id, "added", change.kind().name(), position);
        return setView(openOrAny(id), true);
    }

    ChangeSetView removeChange(Caller caller, UUID id, UUID changeId) {
        draft(id);
        LifecycleStore.ChangeRow row = store.changes(id).stream().filter(change -> change.id().equals(changeId))
                .findFirst().orElseThrow(() -> ApiException.notFound("This change does not exist."));
        store.removeChange(id, changeId, caller.userId());
        audit.changeSetChanged(caller.userId(), id, "removed", row.kind(), row.position());
        return setView(openOrAny(id), true);
    }

    void discard(Caller caller, UUID id) {
        LifecycleStore.SetRow set = draft(id);
        if (!store.discardSet(id, set.version(), caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.changeSetDiscarded(caller.userId(), id);
    }

    /**
     * Applies the changes of the set and reports every problem, what would be added, changed or removed, and for a
     * preview the affected objects as they would be. The caller must roll the transaction back
     * ({@link MetadataAdministration#rehearse}): nothing stays.
     */
    ChangeSetReportView rehearseSet(Caller caller, UUID id, boolean preview) {
        LifecycleStore.SetRow set = draft(id);
        List<Change> changes = changesOf(set.id());
        Outcome outcome = applyAll(caller, changes, false);
        return report(outcome, preview);
    }

    /** Puts the change set live, all of it or none of it. */
    ChangeSetView publish(Caller caller, UUID id) {
        store.lockPublications();
        LifecycleStore.SetRow set = draft(id);
        List<Change> changes = changesOf(set.id());
        if (changes.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "The change set has no changes, so there is nothing to "
                    + "publish.");
        }
        Outcome outcome = applyAll(caller, changes, false);
        if (!outcome.problems().isEmpty()) {
            throw new Refused(caller.userId(), "CHANGE_SET", id, outcome.problems());
        }
        long number = writeRelease(caller, "CHANGE_SET", id, null, outcome.items(), outcome.undo());
        store.publishSet(id, number, caller.userId());
        return setView(openOrAny(id), true);
    }

    // ---- releases ----

    List<ReleaseView> releases() {
        List<LifecycleStore.ReleaseRow> rows = store.releases(HISTORY_LIMIT);
        long latest = rows.isEmpty() ? 0 : rows.get(0).number();
        return rows.stream().map(row -> releaseView(row, latest)).toList();
    }

    /** What rolling back the latest release would do; the caller must roll the transaction back, as for a set. */
    ChangeSetReportView rehearseRollback(Caller caller) {
        LifecycleStore.ReleaseRow latest = latestRelease();
        List<Change> undo = codec.readChanges(latest.undo());
        Outcome outcome = applyAll(caller, undo, true);
        return report(outcome, true);
    }

    /** Undoes the latest release as a new release. */
    ReleaseView rollbackLatest(Caller caller) {
        store.lockPublications();
        LifecycleStore.ReleaseRow latest = latestRelease();
        List<Change> undo = codec.readChanges(latest.undo());
        Outcome outcome = applyAll(caller, undo, true);
        if (!outcome.problems().isEmpty()) {
            throw new Refused(caller.userId(), "ROLLBACK", null, outcome.problems());
        }
        long number = writeRelease(caller, "ROLLBACK", null, latest.number(), outcome.items(), outcome.undo());
        store.markRolledBack(latest.number(), number, caller.userId());
        audit.rolledBack(caller.userId(), number, latest.number(), outcome.items().size());
        return store.release(number).map(row -> releaseView(row, number)).orElseThrow();
    }

    // ---- the core: apply, check, report ----

    /**
     * Applies the changes in order, each in a savepoint, so that one that fails does not hide the next. Nothing is
     * decided here: the caller commits (publish) or rolls back (check) the surrounding transaction.
     */
    private Outcome applyAll(Caller caller, List<Change> changes, boolean checkRecords) {
        List<Problem> problems = new ArrayList<>();
        List<ReleaseItemView> items = new ArrayList<>();
        List<List<Change>> undos = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();
        int position = 0;
        for (Change change : changes) {
            position++;
            touched.add(change.object());
            Problem records = checkRecords ? recordsProblem(position, change) : null;
            if (records != null) {
                problems.add(records);
                continue;
            }
            try {
                ChangeApplier.Applied applied = nested.execute(status -> applier.apply(caller, change));
                items.addAll(applied.items());
                undos.add(applied.undo());
            } catch (ProtectedDefinition refused) {
                problems.add(new Problem("RULE", position, change.object(), change.item(), null,
                        MetadataAdministration.PROTECTED_MESSAGE, List.of()));
            } catch (ApiException refused) {
                problems.add(problemOf(position, change, refused));
            } catch (DataIntegrityViolationException refused) {
                // The database guards are the last line of defence; their text is never shown.
                problems.add(new Problem("RULE", position, change.object(), change.item(), null,
                        "The database refused this change because it breaks a rule of the stored definitions.",
                        List.of()));
            }
        }
        if (problems.isEmpty()) {
            problems.addAll(dependencyProblems());
        }
        return new Outcome(problems, items, undoOf(undos), touched);
    }

    /**
     * Orders the undo of a batch so that applying it never asks for something that is not there yet. Undoing a
     * deletion (a creation) must run before undoing a mere update that puts a reference to the recreated thing back
     * (a record type's field list is checked against what exists, immediately, unlike the dependency graph): first
     * every recreation, then every value restored, last every removal of what the batch created. Inside each group
     * the newest change is undone first, as one undoes a stack: a field removed before its object is recreated after
     * the object, and a newer thing is removed before whatever it depended on. A thing changed more than once is
     * restored once, to the values it had before the batch, expecting the version the last change left (each restore
     * carries the version its own change left, which the restore before it would already have moved on). The undo of
     * one change keeps its own order.
     */
    private List<Change> undoOf(List<List<Change>> undos) {
        List<List<Change>> newestFirst = new ArrayList<>(undos);
        Collections.reverse(newestFirst);
        List<Change> recreations = new ArrayList<>();
        Map<String, Change> restorations = new LinkedHashMap<>();
        List<Change> removals = new ArrayList<>();
        for (List<Change> oneUndo : newestFirst) {
            for (Change change : oneUndo) {
                if (change.kind().isCreate()) {
                    recreations.add(change);
                } else if (change.kind().isDelete()) {
                    removals.add(change);
                } else {
                    String key = ChangeCodec.restoreKey(change);
                    Change newer = restorations.get(key);
                    restorations.put(key,
                            newer == null ? change : codec.expecting(change, codec.expectedVersion(newer)));
                }
            }
        }
        // A thing that is recreated starts again at version zero, whatever the batch had made of it before.
        Set<String> recreated = new HashSet<>();
        recreations.forEach(creation -> recreated.add(codec.restoreKeyOfCreation(creation)));
        restorations.replaceAll((key, restoring) ->
                recreated.contains(key) ? codec.expecting(restoring, 0) : restoring);
        List<Change> undo = new ArrayList<>(recreations);
        undo.addAll(restorations.values());
        undo.addAll(removals);
        return undo;
    }

    private List<Problem> dependencyProblems() {
        MetadataSnapshot snapshot = catalogue.snapshot();
        List<Problem> problems = new ArrayList<>();
        for (DependencyGraph.Violation violation : DependencyGraph.of(snapshot, contributors).violations()) {
            problems.add(new Problem("DEPENDENCY", null, violation.needs().object(), violation.needs().item(),
                    violation.dependent().describe(), violation.message(), List.of()));
        }
        return problems;
    }

    /** Records are the data engine's: until it exists nothing has any, and a rollback is never blocked by them. */
    private Problem recordsProblem(int position, Change change) {
        ObjectUsage records = usage.getIfAvailable();
        if (records == null) {
            return null;
        }
        if (change.kind() == Change.Kind.DELETE_OBJECT && records.hasRecords(change.object())) {
            return new Problem("RECORDS", position, change.object(), null, null, "The object " + change.object()
                    + " has records, so this cannot be undone: removing the object would lose them.", List.of());
        }
        if (change.kind() == Change.Kind.DELETE_FIELD && records.hasValues(change.object(), change.item())) {
            return new Problem("RECORDS", position, change.object(), change.item(), null, "Records hold values in "
                    + "the field " + change.object() + "." + change.item() + ", so this cannot be undone: removing "
                    + "the field would lose them.", List.of());
        }
        return null;
    }

    private static Problem problemOf(int position, Change change, ApiException refused) {
        if (refused.code() == ErrorCode.CONCURRENT_MODIFICATION) {
            return new Problem("CONFLICT", position, change.object(), change.item(), null,
                    "Someone changed this since the change was made. Reload and make the change again.", List.of());
        }
        List<String> fields = new ArrayList<>();
        refused.fields().forEach((name, texts) -> texts.forEach(text -> fields.add(name + ": " + text)));
        String message = refused.code() == ErrorCode.VALIDATION_ERROR ? "The change breaks a rule."
                : refused.getMessage();
        return new Problem("RULE", position, change.object(), change.item(), null, message, fields);
    }

    private ChangeSetReportView report(Outcome outcome, boolean preview) {
        List<ObjectView> objects = new ArrayList<>();
        if (preview) {
            for (String name : outcome.touched()) {
                if (catalogue.object(name).isPresent()) {
                    objects.add(metadata.object(name));
                }
            }
        }
        return new ChangeSetReportView(outcome.problems().isEmpty(),
                outcome.problems().stream().map(Problem::view).toList(), outcome.items(), objects);
    }

    // ---- releases ----

    /** Writes a release for applied changes; returns its number, or 0 when nothing changed. */
    private long release(Caller caller, String kind, UUID changeSet, Long undoes, List<ReleaseItemView> items,
            List<Change> undo) {
        if (items.isEmpty()) {
            return 0;
        }
        return writeRelease(caller, kind, changeSet, undoes, items, undo);
    }

    private long writeRelease(Caller caller, String kind, UUID changeSet, Long undoes, List<ReleaseItemView> items,
            List<Change> undo) {
        long number = store.nextReleaseNumber();
        store.insertRelease(number, kind, changeSet, undoes, codec.write(items), codec.writeChanges(undo),
                store.currentMetadataVersion(), caller.userId());
        if (!kind.equals("ROLLBACK")) {
            audit.released(caller.userId(), number, kind, changeSet, items.size());
        }
        return number;
    }

    private LifecycleStore.ReleaseRow latestRelease() {
        List<LifecycleStore.ReleaseRow> latest = store.releases(1);
        if (latest.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "Nothing has been published yet, so there is nothing to "
                    + "roll back.");
        }
        return latest.get(0);
    }

    // ---- helpers ----

    private LifecycleStore.SetRow openOrAny(UUID id) {
        return store.set(id).orElseThrow(() -> ApiException.notFound("This change set does not exist."));
    }

    private LifecycleStore.SetRow draft(UUID id) {
        LifecycleStore.SetRow set = openOrAny(id);
        if (!set.status().equals("DRAFT")) {
            throw new ApiException(ErrorCode.CONFLICT, "This change set was already published or discarded, so it "
                    + "can no longer be changed.");
        }
        return set;
    }

    private List<Change> changesOf(UUID setId) {
        return store.changes(setId).stream().map(row -> new Change(Change.Kind.valueOf(row.kind()),
                row.objectApiName(), row.itemApiName(), row.payload())).toList();
    }

    private ChangeSetView setView(LifecycleStore.SetRow set, boolean withChanges) {
        List<ChangeView> changes = new ArrayList<>();
        if (withChanges) {
            for (LifecycleStore.ChangeRow row : store.changes(set.id())) {
                changes.add(new ChangeView(row.id().toString(), row.position(), row.kind(), row.objectApiName(),
                        row.itemApiName()));
            }
        }
        return new ChangeSetView(set.id().toString(), set.name(), set.description(), set.status(),
                set.releaseNumber(), set.changeCount(), set.createdAt(), set.version(), changes);
    }

    private ReleaseView releaseView(LifecycleStore.ReleaseRow row, long latest) {
        return new ReleaseView(row.number(), row.kind(), row.changeSetName(), row.undoesRelease(),
                row.rolledBackBy(), row.createdAt(), row.number() == latest, codec.readItems(row.summary()));
    }
}
