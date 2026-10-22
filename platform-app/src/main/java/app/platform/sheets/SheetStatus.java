package app.platform.sheets;

/**
 * The status of one version of a sheet (ADR-0174 section 1). Stored by name. The database enforces which moves are
 * allowed; this type names the statuses for the modules that move a version (the approval sprints).
 */
public enum SheetStatus {

    /** Being written; the only status whose grid can change. At most one per sheet. */
    DRAFT,

    /** Sent for approval; the grid is fixed except for the counted edit of an approver. At most one per sheet. */
    IN_REVIEW,

    /** Approved, not yet in use. */
    APPROVED,

    /** The version in use. At most one per sheet. */
    ACTIVE,

    /** Was active, replaced by a newer active version. */
    SUPERSEDED,

    /** A step of the approval rejected it. */
    REJECTED,

    /** Recalled by the person who sent it, or cancelled by an administrator. */
    WITHDRAWN,

    /** Was active when the sheet's owner was retired. */
    RETIRED;

    /**
     * Whether a caller who may only read the owner record may read a version with this status. A version that is in use
     * or was is part of the record's history; a version still being written or reviewed is not (ADR-0174 section 3),
     * so a share of the owner record can never show a draft.
     */
    public boolean readableByReaders() {
        return this == ACTIVE || this == SUPERSEDED || this == RETIRED;
    }
}
