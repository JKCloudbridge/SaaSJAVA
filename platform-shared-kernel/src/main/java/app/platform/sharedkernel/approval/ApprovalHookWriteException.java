package app.platform.sharedkernel.approval;

import java.util.Objects;

/**
 * The write of a hook was refused by the record store (FA4A, ADR-0187). It carries the store's stable reason key and
 * nothing else: no value, no rule text, no field name that is not shaped like one. A hook may catch it and turn it into
 * an {@link ApprovalHookRefusal}; if it lets it through, the decision is rolled back and the person sees a plain
 * failure.
 */
public final class ApprovalHookWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /** Refuses with the store's stable reason key; the message is the key. */
    public ApprovalHookWriteException(String reason) {
        super(Objects.requireNonNull(reason, "reason"), null, false, false);
        this.reason = reason;
    }

    /** The stable reason key. */
    public String reason() {
        return reason;
    }
}
