package app.platform.sharedkernel.approval;

import java.util.List;
import java.util.UUID;

/**
 * What a module that owns an object may do inside the approval engine's own transaction when a request for a record of
 * that object is submitted, when a person is about to approve, and when the request ends (FA4A, ADR-0187; the need is
 * in ADR-0174 section 5 and ADR-0176 section 2). The approval module calls it and a business module implements it: the
 * same inversion as {@link RecordLocks}, so neither {@code approval} nor {@code data} ever depends on the module that
 * implements it.
 *
 * <p><strong>Found by object.</strong> A hook serves exactly one object, named by {@link #object()}. The engine
 * collects the hooks once at start; two for the same object stop the start and name the object. An object with no hook
 * costs one map lookup per call.
 *
 * <p><strong>Same transaction, all or nothing.</strong> Every call runs inside the transaction of the submit or the
 * decision. A refusal ({@link ApprovalHookRefusal}) or any other exception rolls the whole thing back: the decision,
 * its history, anything the hook wrote and any counter it took. A refusal is shown to the person as a conflict with
 * the hook's fixed words and recorded in the audit by its stable key; any other exception is a plain failure that
 * tells the person nothing (it reaches the error tracking as a failure without the exception's message).
 *
 * <p><strong>Facts only.</strong> A hook is handed identifiers, the object, the process and its version, the step's
 * position and name, the person and the outcome. It is handed no comment and no value of the record: it reads the
 * record itself through the record store, and it writes only through the narrow writer of
 * {@link ApprovalHookWriter}.
 *
 * <p><strong>Time.</strong> A call is not interrupted when it is slow, but it is measured and a call over the
 * configured bound is counted and logged by identifiers (a hook holds the lock of the record while it runs; keep it
 * short).
 *
 * <p>Every method has a default that does nothing, so a hook implements only what it needs.
 */
public interface ApprovalHooks {

    /** The API name of the object this hook serves. */
    String object();

    /**
     * The API names of the fields {@link ApprovalHookWriter} may set; empty when the hook writes nothing. For an
     * object the platform itself defines, each must be a field only the platform writes (checked at start). For any
     * object, the writer refuses a name that is not in this list, on every call.
     */
    default List<String> writes() {
        return List.of();
    }

    /**
     * A request is about to be created for a record. Throw {@link ApprovalHookRefusal} to refuse; nothing is created.
     * The request does not exist yet, and the record is held but not locked (the lock is the request); the context
     * already carries the identifier the request will have.
     */
    default void onSubmit(SubmitContext context) {
    }

    /**
     * A person is about to approve their task (never called for a rejection). It runs after the person's standing and
     * task are checked and before the decision is applied, for every approval of every task, so a step that needs
     * everyone calls it once per person: the hook decides for itself whether it has anything to do. Throw
     * {@link ApprovalHookRefusal} to refuse; the task stays open and nothing changes. The writer is the one write the
     * hook may make to the record, and it works only until this method returns.
     */
    default void beforeApprove(DecisionContext context, ApprovalHookWriter writer) {
    }

    /**
     * The request ended. It runs once, after the request's status is saved (so the record is no longer locked) and
     * before the outcome updates of the process are applied. There is no writer: the record is no longer the request's
     * to change here, and the outcome updates are the process's own way to set fields at the end.
     */
    default void onEnded(EndContext context) {
    }

    /**
     * A request is about to be created.
     *
     * @param requestId the identifier the request will have
     * @param object the object
     * @param recordId the record
     * @param process the API name of the process
     * @param processVersion the number of the definition the request is pinned to
     * @param submitter the person who submits, or null when the platform submits (a workflow)
     */
    record SubmitContext(UUID requestId, String object, UUID recordId, String process, int processVersion,
            UUID submitter) {
    }

    /**
     * A person is about to approve.
     *
     * @param requestId the request
     * @param object the object
     * @param recordId the record
     * @param process the API name of the process
     * @param processVersion the number of the definition the request is pinned to
     * @param requestVersion the version of the request as the person read it
     * @param stepPosition the position of the active step, from 1
     * @param stepName the name of the active step
     * @param approver the person who approves (the person holding the task, a delegate included)
     */
    record DecisionContext(UUID requestId, String object, UUID recordId, String process, int processVersion,
            long requestVersion, int stepPosition, String stepName, UUID approver) {
    }

    /**
     * A request ended.
     *
     * @param requestId the request
     * @param object the object
     * @param recordId the record
     * @param process the API name of the process
     * @param processVersion the number of the definition the request was pinned to
     * @param requestVersion the version of the request as it was before it ended
     * @param outcome how it ended
     * @param endedBy the person whose act ended it (the last approver, the one who rejected, recalled or cancelled), or
     *        null when the platform did
     */
    record EndContext(UUID requestId, String object, UUID recordId, String process, int processVersion,
            long requestVersion, ApprovalOutcome outcome, UUID endedBy) {
    }
}
