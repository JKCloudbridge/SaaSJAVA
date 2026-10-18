package app.platform.approval;

import java.util.Objects;

/**
 * A request could not be started by the platform (ADR-0150). It carries a stable reason and nothing else: no message,
 * no
 * name of a process or a person, no value. Every reason is permanent for a workflow step: nothing a retry does changes
 * it (a replay after the data or the definition changed is the answer).
 */
public final class ApprovalStartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a request could not be started. */
    public enum Reason {
        /** The criteria of the process are not met by the record. */
        ENTRY_CRITERIA_NOT_MET("entry_criteria_not_met"),
        /** The criteria of the process cannot be evaluated for the record. */
        ENTRY_CRITERIA_NOT_EVALUABLE("entry_criteria_not_evaluable"),
        /** The first step names nobody who can decide, and the process has no fallback approver. */
        NO_APPROVER("no_approver"),
        /** The plan of the organization does not have approvals. */
        FEATURE_OFF("feature_off"),
        /** The record does not exist. */
        RECORD_GONE("record_gone"),
        /** The record is in the recycle bin. */
        RECORD_IN_BIN("record_in_bin"),
        /** The process is switched off. */
        PROCESS_INACTIVE("process_inactive"),
        /** The object has no process with this name. */
        PROCESS_MISSING("process_missing");

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        /** The stable key stored with a step. */
        public String key() {
            return key;
        }
    }

    private final Reason reason;

    /** Refuses with this reason; the message is the reason's key. */
    public ApprovalStartException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").key(), null, false, false);
        this.reason = reason;
    }

    /** The reason. */
    public Reason reason() {
        return reason;
    }
}
