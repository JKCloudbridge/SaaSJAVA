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
     * @param object the API name of the record's object, so that a rule that serves several objects (FA13B,
     *        ADR-0226) knows which one the record belongs to
     * @param fileId the file taken off for a {@link Act#REMOVE}; null for an {@link Act#ADD}, whose file does not
     *        exist yet. An identifier only: nothing else about the file is handed over
     */
    record Change(Act act, UUID recordId, UUID userId, UUID membershipId, String object, UUID fileId) {

        /** A change that names no file (an addition). */
        public Change(Act act, UUID recordId, UUID userId, UUID membershipId, String object) {
            this(act, recordId, userId, membershipId, object, null);
        }
    }

    /** The API name of the object this rule serves; of the first, when it serves several ({@link #objects()}). */
    String object();

    /**
     * The API names of every object this rule serves (FA13B, ADR-0226). A rule that serves the objects of several
     * applications of one kind is found for each of them; two rules for one object still stop the start.
     */
    default java.util.Collection<String> objects() {
        return java.util.List.of(object());
    }

    /**
     * Called before the act.
     *
     * @throws RecordFileRefusal to refuse, with the fixed words the person is shown
     */
    void check(Change change);

    /**
     * Called after a file was taken off the record, <strong>in the same transaction</strong> (SH5, ADR-0254): a rule
     * whose owner names files elsewhere (the cells of a fill-in) takes the identifier out of them, so that no
     * identifier names a file that is gone. It cannot refuse: the removal has happened, and a failure undoes both.
     */
    default void removed(Change change) {
    }
}
