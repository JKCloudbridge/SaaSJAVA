package app.platform.sharedkernel.approval;

import java.util.Objects;

/**
 * An {@link ApprovalStarter} could not start the request (FA6E, ADR-0199). The words are fixed by the engine or by a
 * hook's author and never repeat a typed text; the message of the exception is the stable reason key only.
 */
public final class ApprovalStartRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String words;

    /**
     * Refuses.
     *
     * @param reason a stable key, lower case letters and underscores
     * @param words the fixed sentence the person reads
     */
    public ApprovalStartRefusal(String reason, String words) {
        super(Objects.requireNonNull(reason, "reason"), null, false, false);
        this.words = Objects.requireNonNull(words, "words");
    }

    /** The stable key. */
    public String reason() {
        return getMessage();
    }

    /** The fixed sentence for the person. */
    public String words() {
        return words;
    }
}
