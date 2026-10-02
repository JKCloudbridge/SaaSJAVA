package app.platform.notification.internal;

import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The queue contract of the shared kernel, written to the mail_queue table in the caller's transaction (ADR-0024). It
 * stores what happened and for whom, never a message: no token, link or body.
 */
@Component
class JdbcMailQueue implements MailQueue {

    private final MailStore store;

    JdbcMailQueue(MailStore store) {
        this.store = store;
    }

    @Override
    public void enqueue(MailRequest request) {
        store.insert(request);
    }

    @Override
    public boolean queuedRecently(MailTemplate template, UUID userId, long withinSeconds) {
        return store.queuedRecently(template, userId, withinSeconds);
    }
}
