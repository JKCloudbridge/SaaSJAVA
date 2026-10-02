package app.platform.sharedkernel.mail;

import java.util.UUID;

/**
 * Queues an e-mail for sending (ADR-0024). The contract of the notification module's v0: a module that needs to tell a
 * person something puts a {@link MailRequest} here and carries on; the message is built and sent later, by a relay,
 * with retries, so a slow or unreachable mail server never slows or fails the request.
 *
 * <p>The implementation lives in the {@code notification} module, so no business module depends on it (the same
 * pattern as the audit contract). The request is written in the caller's database transaction when there is one, so a
 * rolled-back change leaves no mail behind; without a transaction it is written on its own. The queue works without a
 * tenant context: the requests of sign-up and password reset come from the platform host.
 */
public interface MailQueue {

    /** Queues the request. */
    void enqueue(MailRequest request);

    /**
     * Whether a mail of this kind for this user has already been queued within the given number of seconds. Lets a
     * caller keep a notification to at most one per period, for example the lock notice.
     */
    boolean queuedRecently(MailTemplate template, UUID userId, long withinSeconds);
}
