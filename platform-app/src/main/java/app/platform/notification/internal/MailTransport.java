package app.platform.notification.internal;

/** Hands one finished message to the mail server. */
interface MailTransport {

    /**
     * Sends the message.
     *
     * @throws MailTransportException when the server could not take it; only the failure's type is kept by callers,
     *         because library messages can quote the address and the server's reply
     */
    void send(OutgoingMessage message);

    /** The server could not take the message (unreachable, refused, timed out). Retrying later may work. */
    final class MailTransportException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MailTransportException(Throwable cause) {
            super("The mail server could not take the message", cause);
        }

        /** The simple name of the underlying failure, for the queue's record (never its message). */
        String causeType() {
            return getCause() == null ? "Unknown" : getCause().getClass().getSimpleName();
        }
    }
}
