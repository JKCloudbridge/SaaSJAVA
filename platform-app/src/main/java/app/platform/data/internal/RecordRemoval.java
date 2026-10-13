package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.SlotCopy;
import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What removing a record and bringing it back does to the records that point at it and that belong to it (Sprint 16,
 * ADR-0106). The behaviour is a setting of each link field (ADR-0063):
 * <ul>
 *   <li><strong>clear</strong> (a lookup): the pointer of every record that points at the removed one is emptied, in
 *       the same transaction, and the record keeps everything else;</li>
 *   <li><strong>refuse</strong> (a lookup; always for a required one): the record cannot be removed while a live record
 *       points at it. The answer says that records refer to it and names none of them (they may be records the caller
 *       may not see);</li>
 *   <li><strong>cascade</strong> (a master-detail): the details go to the recycle bin together with the master, marked
 *       with it, so that restoring the master brings back exactly those and not the details a person removed on
 *       purpose before.</li>
 * </ul>
 * The whole removal is planned first and carried out only if the plan is allowed, in one transaction with the removal
 * itself, and it is bounded: a removal that would touch more than {@code platform.data.links.max-affected} other
 * records is refused with that bound, so that one request never holds a huge transaction (a job or an administrator
 * decides what to do about such a record).
 *
 * <p>Removing the details needs the permission to delete in each detail object too: a member must not be able to remove
 * through a master what they could not remove directly. Records in the recycle bin do not count: they neither block the
 * removal nor change; when they come back their links are checked (a pointer to a record that is gone is emptied, a
 * detail whose master is gone is not restored).
 */
@Component
class RecordRemoval {

    static final String REFERRED_TO = "Other records refer to this record, so it cannot be removed. Remove or change "
            + "those records first.";
    /** The batches of the link fill a removal may run itself before it asks the caller to try again. */
    private static final int INLINE_BATCHES = 2;

    static final String NOT_READY = "The links of this kind of record are still being prepared. Try again in a few "
            + "minutes.";
    static final String NOT_ALLOWED = "You may not remove the records that belong to this one.";
    static final String MASTER_GONE = "The record this one belongs to is in the recycle bin or gone, so this record "
            + "cannot come back by itself. Restore the record it belongs to instead.";

    private record Doomed(String object, UUID id) {
    }

    private record Pointer(String object, UUID id, String field) {
    }

    private record Plan(List<Doomed> removedWith, List<Pointer> toClear) {
    }

    private final StorageStrategy storage;
    private final RecordLinks links;
    private final RecordSlots slots;
    private final RecordChanges changes;
    private final Metadata metadata;
    private final Decisions decisions;
    private final RecordProperties.Links limits;
    private final LinkBackfill linkFill;

    RecordRemoval(StorageStrategy storage, RecordLinks links, RecordSlots slots, RecordChanges changes,
            Metadata metadata, Decisions decisions, RecordProperties properties, LinkBackfill linkFill) {
        this.linkFill = linkFill;
        this.storage = storage;
        this.links = links;
        this.slots = slots;
        this.changes = changes;
        this.metadata = metadata;
        this.decisions = decisions;
        this.limits = properties.links();
    }

    // ---- removing ----

    /**
     * Moves a live record to the recycle bin, carrying out what its links say.
     *
     * @return the record as it is in the recycle bin, or null when there is no live record with the identifier
     */
    DataRecord delete(RecordGate.Passed passed, UUID id) {
        DataRecord root = storage.find(passed.object().apiName(), id);
        if (root == null || root.deleted()) {
            return null;
        }
        Plan plan = plan(passed, root);
        for (Doomed doomed : plan.removedWith()) {
            DataRecord removed = storage.softDelete(doomed.object(), doomed.id(), passed.userId(), root.id());
            if (removed != null) {
                changes.removedWithAnother(removed.objectApiName(), removed.id(), removed.version());
            }
        }
        for (Pointer pointer : plan.toClear()) {
            clearPointer(pointer.object(), pointer.id(), pointer.field(), passed.userId());
        }
        DataRecord deleted = storage.softDelete(passed.object().apiName(), id, passed.userId());
        if (deleted != null && (!plan.removedWith().isEmpty() || !plan.toClear().isEmpty())) {
            changes.removalSummary(passed.userId(), deleted.objectApiName(), deleted.id(),
                    plan.removedWith().size(), plan.toClear().size());
        }
        return deleted;
    }

    /** Looks at everything the removal would touch and refuses, or lists what to do. Writes nothing. */
    private Plan plan(RecordGate.Passed passed, DataRecord root) {
        int max = limits.maxAffected();
        List<Doomed> removedWith = new ArrayList<>();
        List<Pointer> toClear = new ArrayList<>();
        Set<UUID> going = new HashSet<>();
        going.add(root.id());
        Deque<Doomed> queue = new ArrayDeque<>();
        queue.add(new Doomed(root.objectApiName(), root.id()));
        while (!queue.isEmpty()) {
            Doomed current = queue.poll();
            if (links.incomplete(current.object())) {
                // A new field, or one on a small object, is finished here; only a large object waits for the sweep.
                linkFill.fill(INLINE_BATCHES);
                if (links.incomplete(current.object())) {
                    throw new ApiException(ErrorCode.CONFLICT, NOT_READY);
                }
            }
            List<RecordLinkStore.Incoming> pointing = links.incoming(current.id(), max + 1);
            Map<String, Set<UUID>> live = liveAmong(pointing);
            for (RecordLinkStore.Incoming row : pointing) {
                if (!live.getOrDefault(row.sourceObject(), Set.of()).contains(row.sourceRecord())
                        || going.contains(row.sourceRecord())) {
                    continue;
                }
                FieldDefinition field = metadata.object(row.sourceObject()).flatMap(o -> o.field(row.field()))
                        .orElse(null);
                if (field == null) {
                    continue;
                }
                switch (RecordLinks.behaviourOf(field)) {
                    case REFUSE -> throw new ApiException(ErrorCode.CONFLICT, REFERRED_TO);
                    case CLEAR -> toClear.add(new Pointer(row.sourceObject(), row.sourceRecord(), row.field()));
                    case CASCADE -> {
                        if (!decisions.can(passed.membershipId(), row.sourceObject(), ObjectAction.DELETE)
                                .allowed()) {
                            throw new ApiException(ErrorCode.FORBIDDEN, NOT_ALLOWED);
                        }
                        going.add(row.sourceRecord());
                        Doomed doomed = new Doomed(row.sourceObject(), row.sourceRecord());
                        removedWith.add(doomed);
                        queue.add(doomed);
                    }
                    default -> throw new IllegalStateException("a delete behaviour this release does not know");
                }
                if (removedWith.size() + toClear.size() > max) {
                    throw new ApiException(ErrorCode.CONFLICT, "Removing this record would remove or change more "
                            + "than " + max + " other records, which is more than one request may do. Ask an "
                            + "administrator.");
                }
            }
        }
        return new Plan(removedWith, toClear);
    }

    /** Which of the pointing records are alive: one question per object. */
    private Map<String, Set<UUID>> liveAmong(List<RecordLinkStore.Incoming> pointing) {
        Map<String, List<UUID>> byObject = new LinkedHashMap<>();
        pointing.forEach(row -> byObject.computeIfAbsent(row.sourceObject(), name -> new ArrayList<>())
                .add(row.sourceRecord()));
        Map<String, Set<UUID>> live = new HashMap<>();
        byObject.forEach((object, ids) -> live.put(object, storage.liveIds(object, ids)));
        return live;
    }

    /** Empties one link of a record, as a change of that record by the platform (a new version, its pointer gone). */
    private void clearPointer(String object, UUID id, String field, UUID actor) {
        DataRecord record = storage.find(object, id);
        if (record == null || record.deleted()) {
            return;
        }
        ObjectDefinition definition = metadata.object(object).orElseThrow();
        Map<String, Object> after = new HashMap<>(record.fields());
        after.remove(field);
        SlotCopy copies = slots.copyIndexed(definition, after);
        DataRecord updated = storage.update(object, id, record.version(), record.recordType(), Map.of(),
                List.of(field), actor, copies);
        if (updated == null) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        links.sync(definition, id, Map.of(), List.of(field), actor);
        changes.pointerCleared(object, id, updated.version(), field);
    }

    // ---- bringing back ----

    /**
     * Takes a record out of the recycle bin, with the records that were removed together with it.
     *
     * @param binned the record, which the caller found in the recycle bin
     * @return the live record, or null when it was no longer in the recycle bin
     */
    DataRecord restore(RecordGate.Passed passed, DataRecord binned) {
        int[] cleared = new int[1];
        DataRecord restored = bringBack(passed.object(), binned, passed.userId(), cleared);
        if (restored == null) {
            return null;
        }
        int[] counts = new int[1];
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(restored.id());
        while (!queue.isEmpty()) {
            for (StorageStrategy.Removed removed : storage.removedWith(queue.poll(), limits.maxAffected() + 1)) {
                if (counts[0] >= limits.maxAffected()) {
                    throw new ApiException(ErrorCode.CONFLICT, "Restoring this record would bring back more than "
                            + limits.maxAffected() + " other records, which is more than one request may do.");
                }
                if (!decisions.can(passed.membershipId(), removed.objectApiName(), ObjectAction.DELETE).allowed()) {
                    throw new ApiException(ErrorCode.FORBIDDEN, NOT_ALLOWED);
                }
                DataRecord detail = storage.find(removed.objectApiName(), removed.id());
                ObjectDefinition definition = metadata.object(removed.objectApiName()).orElse(null);
                if (detail != null && detail.deleted() && definition != null) {
                    DataRecord back = bringBack(definition, detail, passed.userId(), cleared);
                    if (back != null) {
                        counts[0]++;
                        changes.restoredWithAnother(back.objectApiName(), back.id(), back.version());
                        queue.add(back.id());
                    }
                }
            }
        }
        if (counts[0] > 0) {
            changes.restoreSummary(passed.userId(), restored.objectApiName(), restored.id(), counts[0], cleared[0]);
        }
        return restored;
    }

    /**
     * Brings one record back: its links must point at live records (a master-detail whose master is gone refuses; a
     * lookup whose record is gone is emptied afterwards), its typed copies are computed again and its pointers are
     * written again.
     */
    private DataRecord bringBack(ObjectDefinition definition, DataRecord binned, UUID actor, int[] cleared) {
        List<String> dangling = new ArrayList<>();
        for (FieldDefinition field : RecordLinks.referenceFields(definition)) {
            UUID target = RecordLinks.uuidOf(binned.fields().get(field.apiName()));
            if (target != null && !targetIsAlive(field, target)) {
                if (field.type() == FieldType.MASTER_DETAIL) {
                    throw new ApiException(ErrorCode.CONFLICT, MASTER_GONE);
                }
                dangling.add(field.apiName());
            }
        }
        SlotCopy copies = slots.copyIndexed(definition, binned.fields());
        DataRecord restored = storage.restore(definition.apiName(), binned.id(), actor, copies);
        if (restored == null) {
            return null;
        }
        links.syncAll(definition, restored, actor);
        for (String field : dangling) {
            clearPointer(definition.apiName(), restored.id(), field, actor);
            cleared[0]++;
        }
        return dangling.isEmpty() ? restored : storage.find(definition.apiName(), restored.id());
    }

    private boolean targetIsAlive(FieldDefinition field, UUID target) {
        ReferenceConfiguration reference = (ReferenceConfiguration) field.configuration();
        ObjectDefinition targetObject = metadata.object(reference.targetObject()).orElse(null);
        if (targetObject == null) {
            return false;
        }
        if (targetObject.managedBy() != null) {
            return true;
        }
        return storage.liveIds(reference.targetObject(), List.of(target)).contains(target);
    }

}
