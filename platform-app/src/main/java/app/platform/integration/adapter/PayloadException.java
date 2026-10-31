package app.platform.integration.adapter;

/**
 * A payload an adapter was handed cannot be read or written (Sprint 28, ADR-0223). It carries a fixed message and no
 * cause: a parser's message could quote the payload.
 */
public final class PayloadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Refuses with a fixed message. */
    public PayloadException(String message) {
        super(message, null, false, false);
    }
}
