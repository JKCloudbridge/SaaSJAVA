package app.platform.security;

/**
 * The answer to "may this member do this?" (ADR-0050). The reason is for tests and for the code that asks; it is never
 * put into a response or a message for a person: a caller who may not know whether an object or a field exists must
 * see the same "no" for an unknown object and for one they may not use.
 *
 * @param allowed whether the member may do it
 * @param reason why, in the platform's own words
 */
public record Decision(boolean allowed, Reason reason) {

    /** Why the answer is what it is. */
    public enum Reason {
        /** Something the member holds allows it. */
        ALLOWED,
        /** The object does not exist (or is not known to the catalogue). */
        UNKNOWN_OBJECT,
        /** The field does not exist on the object. */
        UNKNOWN_FIELD,
        /** Nothing the member holds allows the action on the object (also: no such member, no licence). */
        OBJECT_NOT_ALLOWED,
        /** The object is allowed but nothing the member holds allows the action on the field. */
        FIELD_NOT_ALLOWED
    }

    /** The member may. */
    public static Decision allow() {
        return new Decision(true, Reason.ALLOWED);
    }

    /** The member may not, for the reason. */
    public static Decision deny(Reason reason) {
        return new Decision(false, reason);
    }
}
