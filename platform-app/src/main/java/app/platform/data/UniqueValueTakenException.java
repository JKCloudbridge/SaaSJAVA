package app.platform.data;

/**
 * The database refused a write because another live record of the object holds the same value in a unique field (or,
 * for a junction, links the same two parents) (Sprint 16A, ADR-0111). Thrown by the storage; the rules above it turn
 * it into the answer at the field's place. It carries the number of the constraint and nothing else: not the value,
 * not the other record, and not the database's own message (which quotes the value), so nothing can leak by logging
 * it.
 */
public final class UniqueValueTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int constraint;

    /**
     * @param constraint the bit of the constraint that refused ({@link SlotCopy#uniqueMask})
     */
    public UniqueValueTakenException(int constraint) {
        super("A unique value is taken (constraint " + constraint + ")");
        this.constraint = constraint;
    }

    /** The number of the bit of the constraint that refused the write. */
    public int constraint() {
        return constraint;
    }
}
