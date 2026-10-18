package app.platform.workflow.internal;

import java.util.Objects;

/**
 * A step of an action failed for a reason the action knows (ADR-0142): a stable key, and whether trying again later
 * could help. It carries nothing else: no message, no value, no setting, and no stack (a failure is a fact of the
 * execution, not a place in the code).
 */
final class StepFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;
    private final boolean transientFailure;

    /**
     * Fails the step.
     *
     * @param reason a stable key, lower case words with underscores
     * @param transientFailure whether a later attempt could succeed without anyone changing data or the definition
     */
    StepFailure(String reason, boolean transientFailure) {
        super(Objects.requireNonNull(reason, "reason"), null, false, false);
        this.reason = reason;
        this.transientFailure = transientFailure;
    }

    /** A failure that no retry can mend. */
    static StepFailure permanent(String reason) {
        return new StepFailure(reason, false);
    }

    /** A failure a later attempt may not have. */
    static StepFailure transientFailure(String reason) {
        return new StepFailure(reason, true);
    }

    String reason() {
        return reason;
    }

    boolean isTransient() {
        return transientFailure;
    }
}
