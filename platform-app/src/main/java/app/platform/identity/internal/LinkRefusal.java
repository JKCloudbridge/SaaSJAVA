package app.platform.identity.internal;

/**
 * Raised inside a transaction to undo it when a link cannot be used. The caller catches it after the transaction ended,
 * records the refusal (a record written inside the transaction would roll back with it) and answers with the one
 * public message.
 */
final class LinkRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    LinkRefusal(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    /** The internal reason code, for the audit record only. */
    String reason() {
        return reason;
    }
}
