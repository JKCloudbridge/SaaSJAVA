package app.platform.security;

/**
 * What a member may do with one record, as record-level security sees it (ADR-0116). It is asked after the member
 * holds the matching permission on the object ({@link ObjectAction}); the two are separate mechanisms.
 *
 * <p>The levels contain each other: a member who may delete a record may edit it, and one who may edit it may read it.
 * Sharing rules and explicit shares give {@link #READ} or {@link #EDIT} and never {@link #DELETE}; the owner, the
 * organization-wide default "Public Read/Write" and the object flag "modify all" give the right to delete.
 */
public enum RecordAction {

    /** See the record. */
    READ,

    /** Change the record. */
    EDIT,

    /** Move the record to the recycle bin (and take it out again). */
    DELETE;

    /** The record-level action that goes with an action on the object, or null for one that has none. */
    public static RecordAction of(ObjectAction action) {
        return switch (action) {
            case READ -> READ;
            case UPDATE -> EDIT;
            case DELETE -> DELETE;
            default -> null;
        };
    }
}
