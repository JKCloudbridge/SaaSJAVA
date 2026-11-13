package app.platform.sheets.internal.formula;

/**
 * The bounds of the sheet formulas (SH2, ADR-0252): the same kind of bounds the expression engine has (ADR-0076,
 * ADR-0081), kept in this module because {@code sheets} may not depend on {@code expression}. They are settings
 * under {@code platform.sheets.formulas.*} and are told to the screen by the list of versions.
 *
 * @param maxChars the longest formula in characters, checked before any parser sees the text
 * @param maxDepth the deepest nesting of brackets, calls and signs
 * @param maxNodes the most parts of one formula
 * @param maxSteps the most steps one formula may take when it is calculated (parts met, and cells read in ranges)
 * @param maxTextChars the longest text a formula may give
 * @param maxCalculationMillis the longest one calculation of a whole sheet may take, checked between formulas
 * @param maxChainDepth the longest chain of formulas that depend on each other
 * @param maxFormulas the most formulas (cells and calculated columns) in one sheet
 */
public record FormulaLimits(int maxChars, int maxDepth, int maxNodes, int maxSteps, int maxTextChars,
        int maxCalculationMillis, int maxChainDepth, int maxFormulas) {

    /** The defaults of the settings. */
    public static final FormulaLimits DEFAULTS = new FormulaLimits(4000, 32, 1000, 100_000, 100_000, 2000, 10, 500);

    /** Checks that every bound is positive. */
    public FormulaLimits {
        if (maxChars < 1 || maxDepth < 1 || maxNodes < 1 || maxSteps < 1 || maxTextChars < 1
                || maxCalculationMillis < 1 || maxChainDepth < 1 || maxFormulas < 1) {
            throw new IllegalArgumentException("every formula limit must be at least 1");
        }
    }
}
