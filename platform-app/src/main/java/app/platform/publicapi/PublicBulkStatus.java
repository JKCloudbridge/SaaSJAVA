package app.platform.publicapi;

/** What happened to one record of a bulk call (Sprint 30, ADR-0245). */
public enum PublicBulkStatus {

    /** The record was created. */
    CREATED,
    /** The record was changed. */
    UPDATED,
    /** The record was moved to the recycle bin. */
    DELETED,
    /** The record was refused; see `error`. Nothing was saved for it. */
    FAILED,
    /** The record was fine, but the call was all-or-none and another record failed, so nothing was saved. */
    ROLLED_BACK
}
