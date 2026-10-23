package app.platform.sharedkernel.files;

/**
 * A {@link RecordFileRules} refused an act on a file (FF2, ADR-0193). The words are fixed by the rule and are shown to
 * the person as they are, so they must never contain a name, a value or a file name. It has no cause and no stack trace
 * worth reading: it is an answer, not a failure.
 */
public final class RecordFileRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What kind of answer it is. */
    public enum Kind {
        /** The person may not do this (an HTTP 403). */
        FORBIDDEN,
        /** The record is in a state that does not allow it (an HTTP 409). */
        CONFLICT
    }

    private final Kind kind;

    /**
     * Makes a refusal.
     *
     * @param kind what kind of refusal
     * @param words the fixed sentence shown to the person
     */
    public RecordFileRefusal(Kind kind, String words) {
        super(words, null, false, false);
        this.kind = kind;
    }

    /** What kind of refusal this is. */
    public Kind kind() {
        return kind;
    }
}
