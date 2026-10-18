package app.platform.expression;

/**
 * What an expression is for. The purpose decides what it may use: a rule is judged when a record is saved, so it may
 * ask for the time of the save; a formula is stored, so it may not (a stored value would silently go stale).
 */
public enum Purpose {

    /** The calculation of a formula field: no clock, any result type. */
    FORMULA(false),

    /** The condition of a validation rule: it must give true or false, and it may use the clock. */
    VALIDATION_RULE(true),

    /**
     * The condition of a workflow (Sprint 22, ADR-0136): it must give true or false, and it may use the clock because
     * it is judged when the execution runs, not stored.
     */
    WORKFLOW_CONDITION(true),

    /**
     * The entry criteria of an approval process (Sprint 24, ADR-0145): it must give true or false, and it may use the
     * clock because it is judged when a record is submitted, not stored.
     */
    APPROVAL_ENTRY(true);

    private final boolean clockAllowed;

    Purpose(boolean clockAllowed) {
        this.clockAllowed = clockAllowed;
    }

    /** Whether {@code TODAY()} and {@code NOW()} may be used. */
    public boolean clockAllowed() {
        return clockAllowed;
    }
}
