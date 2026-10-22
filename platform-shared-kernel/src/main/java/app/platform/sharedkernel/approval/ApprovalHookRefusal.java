package app.platform.sharedkernel.approval;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A hook says "no" on purpose (FA4A, ADR-0187): the submit or the approval is refused and nothing the hook or the
 * engine wrote in that call stays. It carries a stable reason key, which is audited and counted, and fixed words, which
 * the person is shown. Both are chosen by the hook's author at build time; <strong>never</strong> put a typed text, a
 * record value, a comment or an exception message in either.
 */
public final class ApprovalHookRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final Pattern KEY = Pattern.compile("[a-z][a-z_]{0,59}");
    private static final int MOST_WORDS = 300;

    private final String reason;
    private final String words;

    /**
     * Refuses.
     *
     * @param reason a stable key: lower case letters and underscores, starting with a letter, at most 60 characters
     * @param words the fixed sentence the person reads, at most 300 characters
     * @throws IllegalArgumentException when either does not fit (a mistake of the hook's author, found by its first
     *         test)
     */
    public ApprovalHookRefusal(String reason, String words) {
        super(Objects.requireNonNull(reason, "reason"), null, false, false);
        if (!KEY.matcher(reason).matches()) {
            throw new IllegalArgumentException("A hook refusal needs a stable reason key");
        }
        if (words == null || words.isBlank() || words.length() > MOST_WORDS) {
            throw new IllegalArgumentException("A hook refusal needs fixed words of at most 300 characters");
        }
        this.reason = reason;
        this.words = words;
    }

    /** The stable key. */
    public String reason() {
        return reason;
    }

    /** The fixed words the person is shown. */
    public String words() {
        return words;
    }
}
