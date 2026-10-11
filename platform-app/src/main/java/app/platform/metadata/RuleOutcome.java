package app.platform.metadata;

/**
 * The verdict of one validation rule on one record (Sprint 13). A rule that could not be evaluated (a division by zero,
 * a value of the wrong kind) is reported as such and never as a pass in silence: what a save does about it is the write
 * pipeline's decision (Sprint 15), not this contract's.
 *
 * @param rejects whether the condition is true, so that the save must be refused with the rule's message
 * @param problem why the rule could not be evaluated, in words with the place, or null when it could. It may repeat a
 *        field name; it never repeats a typed text
 */
public record RuleOutcome(boolean rejects, String problem) {

    /** The rule was evaluated and does not reject. */
    public static RuleOutcome passes() {
        return new RuleOutcome(false, null);
    }

    /** The rule was evaluated and rejects. */
    public static RuleOutcome rejected() {
        return new RuleOutcome(true, null);
    }

    /** The rule could not be evaluated. */
    public static RuleOutcome failed(String problem) {
        return new RuleOutcome(false, problem);
    }

    /** Whether the rule could be evaluated. */
    public boolean evaluated() {
        return problem == null;
    }
}
