package app.platform.data.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.SlotClass;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The index policy (Sprint 16, ADR-0101): which field of which object holds which slot of the record table.
 *
 * <p><strong>The flag is intent, the assignment is state.</strong> An administrator flags a field "indexed" in the
 * metadata; this class compares the published flags with the assignments in {@code field_slot} and brings the second
 * in line with the first: a flagged field gets the lowest free slot of its kind (and starts FILLING), a field that is
 * no longer flagged (or no longer exists) starts CLEARING. A field counts as indexed <em>for queries</em> only when its
 * assignment is FILLED. It is called after every published change (the event of the release) and at the start of every
 * pass of the backfill, so a missed event is repaired by the next pass: nothing depends on an event arriving.
 *
 * <p><strong>Safe against writes in flight.</strong> A change of the assignments of an object takes its advisory lock
 * exclusively, which waits for every write in flight (each holds it shared, {@code RecordSlots}); a write that starts
 * later reads the new assignments. A record can therefore never be written without the copy it should have.
 *
 * <p>Must run inside the organization's tenant context and a transaction. Writes audit records by field name and
 * counts, never a value.
 */
@Component
class IndexPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(IndexPolicy.class);
    private static final long LOCK_WAIT_MILLIS = 2000;

    /**
     * What one reconciliation did.
     *
     * @param assigned slots given to fields
     * @param released slots that started to be emptied
     * @param waiting changes that could not be made yet (no free slot, or writes in flight did not end in time)
     */
    record Outcome(int assigned, int released, int waiting) {

        boolean changed() {
            return assigned > 0 || released > 0;
        }
    }

    /**
     * Where one field stands, as an administrator reads it.
     *
     * @param field the field
     * @param state {@code WAITING} (flagged, no slot yet), {@code FILLING}, {@code FILLED} or {@code CLEARING}
     * @param recordsDone how many records the fill or the clearing has looked at so far
     * @param flagged whether the field is flagged now (a CLEARING field is not)
     */
    record Status(String field, String state, long recordsDone, boolean flagged) {
    }

    private final Metadata metadata;
    private final FieldSlotStore store;
    private final UniqueStore uniques;
    private final AuditRecorder audit;
    private final TenantContexts contexts;

    IndexPolicy(Metadata metadata, FieldSlotStore store, UniqueStore uniques, AuditRecorder audit,
            TenantContexts contexts) {
        this.metadata = metadata;
        this.store = store;
        this.uniques = uniques;
        this.audit = audit;
        this.contexts = contexts;
    }

    /**
     * One uniqueness constraint an object should enforce.
     *
     * @param kind the unique value of a field, or the pair of parents of a junction
     * @param field the field (the first master of a pair)
     * @param partner the second master of a pair, or null
     * @param key the constraint
     */
    record Wanted(UniqueStore.Kind kind, String field, String partner, UniqueKey key) {

        boolean matches(UniqueStore.Row row) {
            return row.kind() == kind && row.fieldApiName().equals(field)
                    && Objects.equals(row.partnerApiName(), partner)
                    && row.bit() == key.bit();
        }
    }

    /** Brings the assignments in line with the published flags (see the class description). */
    Outcome reconcile() {
        Map<String, List<FieldSlotStore.Row>> rowsByObject = new LinkedHashMap<>();
        store.all().forEach(row -> rowsByObject.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                .add(row));
        Map<String, List<UniqueStore.Row>> constraintsByObject = new LinkedHashMap<>();
        uniques.all().forEach(row -> constraintsByObject.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                .add(row));
        int[] counts = new int[3];
        Set<String> known = new HashSet<>();
        for (ObjectDefinition object : metadata.objects()) {
            if (object.managedBy() == null) {
                known.add(object.apiName());
                reconcileObject(object, rowsByObject.getOrDefault(object.apiName(), List.of()),
                        constraintsByObject.getOrDefault(object.apiName(), List.of()), counts);
            }
        }
        // An object that no longer exists cannot hold records (it could not be removed with records), so its slots
        // and constraints are simply freed.
        UUID actor = ActorId.SYSTEM.value();
        rowsByObject.forEach((objectName, rows) -> {
            if (!known.contains(objectName)) {
                rows.forEach(row -> store.free(row.id(), actor));
            }
        });
        constraintsByObject.forEach((objectName, rows) -> {
            if (!known.contains(objectName)) {
                rows.forEach(row -> uniques.free(row.id(), actor));
            }
        });
        if (counts[2] > 0) {
            LOG.info("{} index changes are waiting for a free slot or for writes to end", counts[2]);
        }
        return new Outcome(counts[0], counts[1], counts[2]);
    }

    /** One object: look, and only when something must change take the lock, look again and change it. */
    private void reconcileObject(ObjectDefinition object, List<FieldSlotStore.Row> seen,
            List<UniqueStore.Row> seenConstraints, int[] counts) {
        if (toAssign(object, seen).isEmpty() && toRelease(object, seen).isEmpty()
                && constraintsToStart(object, seen, seenConstraints).isEmpty()
                && constraintsToStop(object, seen, seenConstraints).isEmpty()) {
            return;
        }
        if (!store.lockExclusive(object.apiName(), LOCK_WAIT_MILLIS)) {
            counts[2] += toAssign(object, seen).size() + toRelease(object, seen).size()
                    + constraintsToStart(object, seen, seenConstraints).size()
                    + constraintsToStop(object, seen, seenConstraints).size();
            return;
        }
        // What the lock waited for may have changed the picture (another instance got there first): read again.
        List<FieldSlotStore.Row> rows = store.forObject(object.apiName());
        UUID actor = ActorId.SYSTEM.value();
        for (FieldSlotStore.Row row : toRelease(object, rows)) {
            if (store.markClearing(row.id(), actor)) {
                counts[1]++;
                note("data.index.released", object.apiName(), row.fieldApiName());
            }
        }
        Set<SlotColumn> used = new HashSet<>();
        rows.forEach(row -> used.add(row.slot()));
        for (FieldDefinition field : toAssign(object, rows)) {
            Optional<SlotColumn> free = SlotClass.of(field.type()).map(SlotColumn::of).stream()
                    .flatMap(List::stream).filter(slot -> !used.contains(slot)).findFirst();
            if (free.isEmpty()) {
                counts[2]++;
                continue;
            }
            used.add(free.get());
            store.insert(object.apiName(), field.apiName(), free.get(), actor);
            counts[0]++;
            note("data.index.assigned", object.apiName(), field.apiName());
        }
        reconcileConstraints(object, actor);
    }

    /**
     * Brings the uniqueness constraints of an object in line with its unique fields and its junction, under the lock
     * the caller holds. A constraint starts when the copy of the field is complete (every record then has what the
     * constraint compares), and stops when the field is no longer unique or its copy is going away. The bits that
     * records still carry after a constraint stopped are harmless (a record that is written again sets its mask anew,
     * and emptying the slot switches them off), so stopping needs no work on the records.
     */
    private void reconcileConstraints(ObjectDefinition object, UUID actor) {
        List<FieldSlotStore.Row> slots = store.forObject(object.apiName());
        List<UniqueStore.Row> existing = uniques.forObject(object.apiName());
        for (UniqueStore.Row row : constraintsToStop(object, slots, existing)) {
            uniques.free(row.id(), actor);
            note("data.unique.released", object.apiName(), row.fieldApiName());
        }
        // A stopped row is freed, so the starts are judged against what is left.
        List<UniqueStore.Row> kept = uniques.forObject(object.apiName());
        for (Wanted wanted : constraintsToStart(object, slots, kept)) {
            uniques.insert(object.apiName(), wanted.kind(), wanted.field(), wanted.partner(), wanted.key().bit(),
                    actor);
            note("data.unique.enforcing", object.apiName(), wanted.field());
        }
    }

    /**
     * The constraints an object should enforce: one for every unique field whose copy is complete, and one for the pair
     * of parents of a junction whose two copies are complete.
     */
    static List<Wanted> wantedConstraints(ObjectDefinition object, List<FieldSlotStore.Row> slots) {
        Map<String, FieldSlotStore.Row> complete = new LinkedHashMap<>();
        slots.stream().filter(row -> row.state() == FieldSlotStore.State.FILLED)
                .forEach(row -> complete.put(row.fieldApiName(), row));
        List<Wanted> wanted = new ArrayList<>();
        for (FieldDefinition field : object.fields()) {
            FieldSlotStore.Row row = complete.get(field.apiName());
            if (row != null && SlotClass.enforcesUnique(field)) {
                wanted.add(new Wanted(UniqueStore.Kind.VALUE, field.apiName(), null, UniqueKey.of(row.slot())));
            }
        }
        if (RecordLinks.isJunction(object)) {
            List<FieldDefinition> masters = RecordLinks.masterDetails(object);
            FieldSlotStore.Row first = complete.get(masters.get(0).apiName());
            FieldSlotStore.Row second = complete.get(masters.get(1).apiName());
            if (first != null && second != null) {
                UniqueKey.pair(first.slot(), second.slot()).ifPresent(key -> wanted.add(new Wanted(
                        UniqueStore.Kind.PAIR, masters.get(0).apiName(), masters.get(1).apiName(), key)));
            }
        }
        return wanted;
    }

    private static List<Wanted> constraintsToStart(ObjectDefinition object, List<FieldSlotStore.Row> slots,
            List<UniqueStore.Row> existing) {
        return wantedConstraints(object, slots).stream()
                .filter(wanted -> existing.stream().noneMatch(wanted::matches)).toList();
    }

    private static List<UniqueStore.Row> constraintsToStop(ObjectDefinition object, List<FieldSlotStore.Row> slots,
            List<UniqueStore.Row> existing) {
        List<Wanted> wanted = wantedConstraints(object, slots);
        return existing.stream().filter(row -> wanted.stream().noneMatch(one -> one.matches(row))).toList();
    }

    private static List<FieldDefinition> toAssign(ObjectDefinition object, List<FieldSlotStore.Row> rows) {
        return wanted(object).stream()
                .filter(field -> rows.stream().noneMatch(row -> row.fieldApiName().equals(field.apiName())))
                .toList();
    }

    private static List<FieldSlotStore.Row> toRelease(ObjectDefinition object, List<FieldSlotStore.Row> rows) {
        Set<String> kept = new HashSet<>();
        wanted(object).forEach(field -> kept.add(field.apiName()));
        return rows.stream().filter(row -> row.state() != FieldSlotStore.State.CLEARING)
                .filter(row -> !kept.contains(row.fieldApiName())).toList();
    }

    /** The fields of an object that should hold a slot: flagged, and of a kind that can be indexed. */
    private static List<FieldDefinition> wanted(ObjectDefinition object) {
        return object.fields().stream().filter(SlotClass::served).toList();
    }

    /** Where every flagged or assigned field of the object stands (for the administrators' status read). */
    List<Status> statusOf(ObjectDefinition object) {
        Map<String, FieldSlotStore.Row> rows = new LinkedHashMap<>();
        store.forObject(object.apiName()).stream()
                .forEach(row -> rows.put(row.fieldApiName(), row));
        List<Status> statuses = new ArrayList<>();
        for (FieldDefinition field : wanted(object)) {
            FieldSlotStore.Row row = rows.remove(field.apiName());
            statuses.add(row == null ? new Status(field.apiName(), "WAITING", 0, true)
                    : new Status(field.apiName(), row.state().name(), row.recordsDone(), true));
        }
        rows.values().forEach(row -> statuses.add(new Status(row.fieldApiName(), row.state().name(),
                row.recordsDone(), false)));
        return statuses;
    }

    /**
     * Where the enforcement of one unique field (or the pair of parents of a junction) stands, as an administrator
     * reads it.
     *
     * @param field the field (the first master of a pair)
     * @param partner the second master of a pair, or null
     * @param state {@code WAITING} (the copy of the field is not complete yet), {@code ENFORCING}, {@code CONFLICT},
     *        {@code ENFORCED}, or {@code NOT_ENFORCED} (the field is flagged unique but its setup cannot be checked)
     * @param recordsDone how many records the mask has been written for so far
     * @param collidingRecords how many records share a value with another record (in CONFLICT)
     */
    record UniqueStatus(String field, String partner, String state, long recordsDone, long collidingRecords) {
    }

    /** Where the uniqueness of the object's fields stands (for the administrators' status read). */
    List<UniqueStatus> uniqueStatusOf(ObjectDefinition object) {
        List<UniqueStore.Row> rows = uniques.forObject(object.apiName());
        List<UniqueStatus> statuses = new ArrayList<>();
        for (FieldDefinition field : object.fields()) {
            boolean flagged = field.unique() && field.kind() != DefinitionKind.SYSTEM
                    && field.type() != FieldType.AUTO_NUMBER;
            if (!flagged) {
                continue;
            }
            if (!SlotClass.enforcesUnique(field)) {
                statuses.add(new UniqueStatus(field.apiName(), null, "NOT_ENFORCED", 0, 0));
                continue;
            }
            statuses.add(statusOf(rows, UniqueStore.Kind.VALUE, field.apiName(), null));
        }
        if (RecordLinks.isJunction(object)) {
            List<FieldDefinition> masters = RecordLinks.masterDetails(object);
            statuses.add(statusOf(rows, UniqueStore.Kind.PAIR, masters.get(0).apiName(),
                    masters.get(1).apiName()));
        }
        return statuses;
    }

    private static UniqueStatus statusOf(List<UniqueStore.Row> rows, UniqueStore.Kind kind,
            String field, String partner) {
        for (UniqueStore.Row row : rows) {
            if (row.kind() == kind && row.fieldApiName().equals(field)
                    && Objects.equals(row.partnerApiName(), partner)) {
                return new UniqueStatus(field, partner, row.state().name(), row.recordsDone(), row.conflicts());
            }
        }
        return new UniqueStatus(field, partner, "WAITING", 0, 0);
    }

    /** The slots that are complete: the fields a query may count on, by field API name, for the object. */
    Map<String, SlotColumn> completeSlots(String objectApiName) {
        Map<String, SlotColumn> complete = new LinkedHashMap<>();
        store.forObject(objectApiName).stream().filter(row -> row.state() == FieldSlotStore.State.FILLED)
                .forEach(row -> complete.put(row.fieldApiName(), row.slot()));
        return complete;
    }

    private void note(String kind, String object, String field) {
        audit.record(AuditRecord.of(kind, AuditOutcome.SUCCESS)
                .inTenant(contexts.current().map(TenantContext::tenantId).orElse(null))
                .onObject(object + "." + field, null));
    }
}
