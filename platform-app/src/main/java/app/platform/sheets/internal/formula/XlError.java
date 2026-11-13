package app.platform.sheets.internal.formula;

/**
 * An error value of a formula, shown as its code and never hidden (SH2). The codes are the spreadsheet program's, plus
 * one of the platform: {@code #LIMIT!} when a bound of {@link FormulaLimits} was reached while calculating.
 *
 * @param code the code as it is shown
 */
public record XlError(String code) {

    /** A value of the wrong kind. */
    public static final XlError VALUE = new XlError("#VALUE!");
    /** A division by zero. */
    public static final XlError DIV0 = new XlError("#DIV/0!");
    /** A number that cannot be, or is out of range. */
    public static final XlError NUM = new XlError("#NUM!");
    /** An unknown name (never given by this evaluator for an allowed function). */
    public static final XlError NAME = new XlError("#NAME?");
    /** A value that is not available. */
    public static final XlError NA = new XlError("#N/A");
    /** A bound of the platform was reached. */
    public static final XlError LIMIT = new XlError("#LIMIT!");
    /** A reference that cannot be followed (a cell that depends on itself in stored data that was not checked). */
    public static final XlError REF = new XlError("#REF!");

    @Override
    public String toString() {
        return code;
    }
}
