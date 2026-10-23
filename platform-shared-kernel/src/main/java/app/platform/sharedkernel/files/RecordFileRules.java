package app.platform.sharedkernel.files;

import java.util.UUID;

/**
 * What a module that owns an object may require before a file is added to or removed from a record of that object
 * (FF2, ADR-0193). The file service of the data module calls it and a business module implements it: the same inversion
 * as the approval hooks, so {@code data} never depends on the module that implements it.
 *
 * <p>It runs <strong>in addition to</strong> the platform's own checks, never instead of them: a file is added or
 * removed only by a member who may change the record, on a record no approval is locking (or who may override that
 * lock), and then by this rule. It can only refuse; it grants nothing the platform does not already grant. It is
 * called inside the transaction of the check, so it must be quick and must not write anything but its own audit
 * record.
 *
 * <p><strong>Found by object.</strong> A rule serves exactly one object, named by {@link #object()}; two for the same
 * object stop the start. An object with no rule costs one map lookup.
 *
 * <p>Nothing about the file (its name, its type, its content) is handed over: the rule decides by the record and the
 * person alone.
 */
public interface RecordFileRules {

    /** What is about to happen to a file. */
    enum Act {
        /** A file is added to the record. */
        ADD,
        /** A file is taken off the record. */
        REMOVE
    }

    /**
     * Who does what to which record.
     *
     * @param act the act
     * @param recordId the record
     * @param userId the person
     * @param membershipId the person's membership in the organization
     */
    record Change(Act act, UUID recordId, UUID userId, UUID membershipId) {
    }

    /** The API name of the object this rule serves. */
    String object();

    /**
     * Called before the act.
     *
     * @throws RecordFileRefusal to refuse, with the fixed words the person is shown
     */
    void check(Change change);
}
