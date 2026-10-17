package app.platform.metadata;

/**
 * The verdict of a workflow condition on one record (Sprint 22, ADR-0137). A condition that cannot be evaluated is
 * reported as such and never as "false" in silence: the execution ends with a stable reason that says so.
 */
public enum ConditionOutcome {

    /** The condition is true, or the workflow has none: the actions run. */
    TRUE,

    /** The condition is false, or has no value for this record: the execution is skipped. */
    FALSE,

    /**
     * The condition cannot be evaluated for this record (a division by zero, a value of the wrong kind, a field that no
     * longer exists): the execution is skipped with its own reason and is never lost.
     */
    NOT_EVALUABLE
}
