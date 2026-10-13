package app.platform.data.internal;

import app.platform.metadata.SlotClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One uniqueness constraint of the record table (Sprint 16A, ADR-0111, migration V043): either one slot column (the
 * value of a unique field) or a pair of link slots (the two parents of a junction record). The only place that knows
 * the number of a constraint, the columns it covers and the name of the index the database holds for it.
 *
 * <p>The bit is the position in {@code object_record.unique_mask}: 0 to 21 are the slot columns in the order of
 * {@link SlotColumn}, 22 to 27 are the six pairs of link slots in the order 1+2, 1+3, 1+4, 2+3, 2+4, 3+4. A test
 * compares this list with the indexes the migration made.
 *
 * @param bit the position in the mask
 * @param columns the slot columns it covers: one, or two link slots (the lower one first)
 */
record UniqueKey(int bit, List<SlotColumn> columns) {

    /** The first bit of the pairs of link slots. */
    static final int FIRST_PAIR_BIT = SlotColumn.values().length;

    private static final List<UniqueKey> ALL = all();

    UniqueKey {
        columns = List.copyOf(columns);
    }

    private static List<UniqueKey> all() {
        List<UniqueKey> keys = new ArrayList<>();
        for (SlotColumn slot : SlotColumn.values()) {
            keys.add(new UniqueKey(slot.ordinal(), List.of(slot)));
        }
        List<SlotColumn> links = SlotColumn.of(SlotClass.REFERENCE);
        int bit = FIRST_PAIR_BIT;
        for (int first = 0; first < links.size(); first++) {
            for (int second = first + 1; second < links.size(); second++) {
                keys.add(new UniqueKey(bit++, List.of(links.get(first), links.get(second))));
            }
        }
        return List.copyOf(keys);
    }

    /** Every constraint, by bit. */
    static List<UniqueKey> every() {
        return ALL;
    }

    /** The constraint on the value of one slot. */
    static UniqueKey of(SlotColumn slot) {
        return ALL.get(slot.ordinal());
    }

    /** The constraint on a pair of link slots (in either order), or empty when they are not two link slots. */
    static Optional<UniqueKey> pair(SlotColumn one, SlotColumn other) {
        return ALL.stream().filter(key -> key.isPair() && key.columns.contains(one) && key.columns.contains(other)
                && !one.equals(other)).findFirst();
    }

    /** The constraint with this bit, if there is one. */
    static Optional<UniqueKey> ofBit(int bit) {
        return bit >= 0 && bit < ALL.size() ? Optional.of(ALL.get(bit)) : Optional.empty();
    }

    /** The constraint whose index has this name, if there is one. */
    static Optional<UniqueKey> ofIndex(String indexName) {
        return ALL.stream().filter(key -> key.indexName().equals(indexName)).findFirst();
    }

    /** Whether the constraint covers a pair of link slots. */
    boolean isPair() {
        return columns.size() == 2;
    }

    /** The value of the bit in the mask. */
    int mask() {
        return 1 << bit;
    }

    /** The name of the unique index of the database (V043). */
    String indexName() {
        return isPair() ? "object_record_u_p_" + columns.get(0).column().substring(2) + "_"
                + columns.get(1).column().substring(2) : "object_record_u_" + columns.get(0).column();
    }

    /** Every bit of the constraints that cover the slot, as a mask: what emptying the slot must switch off. */
    static int maskTouching(SlotColumn slot) {
        int mask = 0;
        for (UniqueKey key : ALL) {
            if (key.columns.contains(slot)) {
                mask |= key.mask();
            }
        }
        return mask;
    }
}
