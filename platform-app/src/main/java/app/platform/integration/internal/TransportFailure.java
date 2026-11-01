package app.platform.integration.internal;

/**
 * A transport (the secure shell session of file transfer) could not do what was asked, for a stable reason. It carries
 * the reason and nothing else: no message and no cause, because a library's message can quote a path, a user name or a
 * key, and no stack, because it is an expected outcome and not a defect.
 */
final class TransportFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient TestReason reason;

    TransportFailure(TestReason reason) {
        super(reason.key(), null, false, false);
        this.reason = reason;
    }

    /** Why. */
    TestReason reason() {
        return reason;
    }
}
