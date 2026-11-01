package app.platform.publicapi;

/** What a bulk call does to each of its records (Sprint 30, ADR-0245). */
public enum PublicBulkOperation {

    /** Create each record. */
    CREATE,
    /** Change each record; each item carries the id and the version it was read at. */
    UPDATE,
    /** Move each record to the recycle bin; each item carries the id. */
    DELETE
}
