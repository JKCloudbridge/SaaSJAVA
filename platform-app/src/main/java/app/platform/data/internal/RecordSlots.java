package app.platform.data.internal;

import app.platform.data.SlotCopy;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The typed copies of indexed fields ("slots", ADR-0084, ADR-0091, ADR-0102) and the uniqueness constraints that ride
 * on them (ADR-0111). The write pipeline asks {@link #begin} once per create and per update, checks the values that
 * must be unique, and calls {@link #copyIndexed} with the values the record will have, after every check has passed
 * and before the record is stored; a restore calls {@link #copyIndexed} with the values the record holds. The answer
 * travels to the storage and is written in the same statement as the record.
 *
 * <p>A field gets a copy while its slot is being filled <em>and</em> once it is complete: a record written while the
 * records that already existed are being filled must be right, or the fill would never be able to call itself done. A
 * slot that is being emptied (the field is no longer indexed) is not copied: the write leaves it empty.
 *
 * <p>A record carries the bit of every uniqueness constraint of its object that is being enforced or is enforced, as
 * soon as it has a copy for each column of the constraint. The constraints are read after the lock below, in the same
 * transaction as the write, so a write never misses one that is switched on: a constraint is switched on under the
 * exclusive lock, which waits for every write in flight.
 *
 * <p>{@link #begin} takes, for the rest of the transaction, the shared lock of the object that makes a change of the
 * assignments wait for the writes in flight (ADR-0101). A test proves the seam is called, so the plug cannot be
 * forgotten.
 */
@Component
class RecordSlots {

    /**
     * What a write must know about the copies of its object, read under the lock.
     *
     * @param slots the slots to fill (being filled, or complete)
     * @param constraints the uniqueness constraints that are being enforced or are enforced
     */
    record Active(List<FieldSlotStore.Row> slots, List<UniqueStore.Row> constraints) {

        Active {
            slots = List.copyOf(slots);
            constraints = List.copyOf(constraints);
        }
    }

    /**
     * A value that must be unique and is about to be written: asked of the storage before the record is.
     *
     * @param bit the constraint
     * @param place where a refusal is answered
     * @param pair whether the constraint is the pair of parents of a junction
     * @param values the copy of each column of the constraint, as text
     */
    record Probe(int bit, String place, boolean pair, List<String> values) {
    }

    private final FieldSlotStore store;
    private final UniqueStore constraints;
    private final AtomicLong calls = new AtomicLong();

    RecordSlots(FieldSlotStore store, UniqueStore constraints) {
        this.store = store;
        this.constraints = constraints;
    }

    /** Takes the shared lock of the object and reads the slots and constraints a write must honour. */
    Active begin(ObjectDefinition object) {
        List<FieldSlotStore.Row> slots = store.activeForWrite(object.apiName());
        if (slots.isEmpty()) {
            return new Active(slots, List.of());
        }
        return new Active(slots, constraints.forObject(object.apiName()));
    }

    /**
     * The copies to write with the record.
     *
     * @param object the object of the record
     * @param values the values the record will have after the write, by field API name (never logged)
     */
    SlotCopy copyIndexed(ObjectDefinition object, Map<String, Object> values) {
        return copyIndexed(begin(object), object, values);
    }

    /** As {@link #copyIndexed(ObjectDefinition, Map)}, for a write that has called {@link #begin}. */
    SlotCopy copyIndexed(Active active, ObjectDefinition object, Map<String, Object> values) {
        calls.incrementAndGet();
        if (active.slots().isEmpty()) {
            return SlotCopy.none();
        }
        Map<String, String> copies = copies(active, object, values);
        int mask = 0;
        Map<Integer, String> places = new HashMap<>();
        for (UniqueStore.Row row : active.constraints()) {
            UniqueKey key = row.key();
            if (key.columns().stream().allMatch(column -> copies.containsKey(column.column()))) {
                mask |= key.mask();
                places.put(key.bit(), row.place());
            }
        }
        return new SlotCopy(copies, mask, places);
    }

    /**
     * The values that must be unique and that this write puts into a record: for every constraint, the copies of the
     * record after the write, unless the record keeps the copies it has (a write that does not touch the field must not
     * ask).
     *
     * @param after the values the record will have
     * @param held the values it has now (empty for a record that does not exist yet)
     */
    List<Probe> probes(Active active, ObjectDefinition object, Map<String, Object> after, Map<String, Object> held) {
        if (active.constraints().isEmpty()) {
            return List.of();
        }
        Map<String, String> next = copies(active, object, after);
        Map<String, String> now = copies(active, object, held);
        List<Probe> probes = new ArrayList<>();
        for (UniqueStore.Row row : active.constraints()) {
            UniqueKey key = row.key();
            List<String> values = key.columns().stream().map(column -> next.get(column.column())).toList();
            if (values.stream().anyMatch(Objects::isNull)) {
                continue;
            }
            List<String> before = key.columns().stream().map(column -> now.get(column.column())).toList();
            if (!values.equals(before)) {
                probes.add(new Probe(key.bit(), row.place(), key.isPair(), values));
            }
        }
        return probes;
    }

    private static Map<String, String> copies(Active active, ObjectDefinition object, Map<String, Object> values) {
        Map<String, String> copies = new HashMap<>();
        for (FieldSlotStore.Row row : active.slots()) {
            FieldDefinition field = object.field(row.fieldApiName()).orElse(null);
            String text = field == null ? null : textOf(values.get(row.fieldApiName()));
            if (text != null) {
                copies.put(row.slot().column(), text);
            }
        }
        return copies;
    }

    /**
     * The stored value as the text the database converts to the column's type; null when the field has no value. The
     * text is what {@code payload ->> field} gives for the same value, so a write and a backfill agree.
     */
    static String textOf(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case BigDecimal number -> number.toString();
            case Number number -> new BigDecimal(number.toString()).toString();
            case Boolean flag -> flag.toString();
            default -> null;
        };
    }

    /** How many times the seam was called (tests). */
    long calls() {
        return calls.get();
    }
}
