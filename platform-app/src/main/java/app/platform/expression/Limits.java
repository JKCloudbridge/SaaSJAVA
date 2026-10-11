package app.platform.expression;

/**
 * The bounds of one expression (ADR-0076): how long it may be, how many parts it may have, how deeply it may nest, how
 * much work one evaluation may do, how long a text may get and how long an evaluation may take. A bound that is
 * reached is a clear error that names the bound; it is never a crash and never an endless run.
 *
 * @param maxLength the most characters of the text (the 4000 of a formula)
 * @param maxNodes the most parts (values, fields, operators, calls) of the tree
 * @param maxDepth the deepest nesting of brackets, calls and prefix operators
 * @param maxSteps the most steps one evaluation may take; every part is a step and long texts cost more
 * @param maxTextLength the most characters of a text value, an input or a result
 * @param maxMillis the most milliseconds one evaluation may take: the last line of defence behind the step bound
 */
public record Limits(int maxLength, int maxNodes, int maxDepth, int maxSteps, int maxTextLength, long maxMillis) {

    /** The length of a formula, the same as the field's limit. */
    public static final int FORMULA_LENGTH = 4000;

    /** Checks that every bound is positive. */
    public Limits {
        if (maxLength < 1 || maxNodes < 1 || maxDepth < 1 || maxSteps < 1 || maxTextLength < 1 || maxMillis < 1) {
            throw new IllegalArgumentException("Every expression limit must be at least 1");
        }
    }

    /** The bounds the platform uses unless a setting says otherwise. */
    public static Limits standard() {
        return new Limits(FORMULA_LENGTH, 500, 32, 10_000, 100_000, 50);
    }
}
