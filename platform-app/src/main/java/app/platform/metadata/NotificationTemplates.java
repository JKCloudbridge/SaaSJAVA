package app.platform.metadata;

import app.platform.sharedkernel.notice.NoticeKind;
import java.util.Optional;

/**
 * The published text of notices and mails (Sprint 25, ADR-0155), asked by the notification module when it builds the
 * words of a notice or a mail. It answers for the thread's tenant context and runs in the caller's transaction, which
 * must have been opened after the context. The text is read live: it is a handful of short rows, asked once per
 * request or per mail, so it needs no cache and is never older than the last publication.
 */
public interface NotificationTemplates {

    /** The text an administrator wrote for the kind, or empty when the platform's own text is in use. */
    Optional<NotificationTemplate> find(NoticeKind kind);
}
