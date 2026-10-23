package app.platform.sheets;

/**
 * The status of one fill-in (FF1, ADR-0192). Stored by name. The database enforces which moves are allowed; this type
 * names the statuses for the module that moves a fill-in (the approval hook of the cluster).
 */
public enum FillStatus {

    /** Being filled in by the person who started it. The cells can change. */
    DRAFT,

    /** Sent for review; the cells are fixed except for the counted correction of a person who may correct. */
    SUBMITTED,

    /** The review sent it back to its filler. The cells can change again, and it can be sent again. */
    RETURNED,

    /** The review approved it. The cells are fixed except for the counted correction. */
    APPROVED;

    /** Whether the person who started the fill-in may change its cells in this status. */
    public boolean fillerMayChange() {
        return this == DRAFT || this == RETURNED;
    }
}
