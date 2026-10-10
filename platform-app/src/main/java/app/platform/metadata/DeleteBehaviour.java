package app.platform.metadata;

/**
 * What happens to a record that points at another record when that other record is removed (ADR-0063). The rule is
 * stored with the reference field; the data engine (Milestone 4) carries it out, and until records exist it is what the
 * object manager shows and what the dependency checks of the metadata module know.
 */
public enum DeleteBehaviour {

    /** The pointer is emptied and the record stays. The default of a lookup. */
    CLEAR,

    /** The other record cannot be removed while any record points at it. */
    REFUSE,

    /** The pointing record is removed together with the other one. Always the behaviour of a master-detail. */
    CASCADE
}
