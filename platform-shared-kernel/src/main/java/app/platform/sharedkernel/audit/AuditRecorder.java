package app.platform.sharedkernel.audit;

/**
 * Writes one audit record. The contract of "audit v0" (Sprint 3): security-relevant facts go here, in the same
 * database transaction as the change they describe when there is one, and nowhere else.
 *
 * <p>The implementation lives in the {@code audit} module, so no business module depends on it. It stores the record
 * even when no tenant context is open (sign-in on the platform host has no tenant), adds the request and trace IDs
 * from the logging context, and never fails the caller's request because a record could not be written: a failure is
 * reported through the error tracking hook and the caller continues. A record written inside a transaction that rolls
 * back disappears with it, which is the intended behaviour for a change that did not happen.
 */
public interface AuditRecorder {

    /** Stores the record. Never throws for a storage failure (see the class comment). */
    void record(AuditRecord record);
}
