package app.platform.sheets.internal;

import app.platform.sheets.internal.formula.FormulaFunctions;
import app.platform.sheets.internal.formula.FormulaLimits;
import app.platformapi.SheetFormulaFunctionView;
import app.platformapi.SheetFormulaLimitsView;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The bounds of the formulas of a sheet (SH2, ADR-0252), the same kind of bounds the expression engine has (ADR-0076,
 * ADR-0081). A bound that is reached is a refusal at save that names it, or, while calculating, the error value
 * {@code #LIMIT!}; nothing is cut.
 *
 * @param maxChars the longest formula in its stored form, checked before any parser sees it (4,000)
 * @param maxDepth the deepest nesting of brackets, calls and signs (32)
 * @param maxNodes the most parts of one formula (1,000)
 * @param maxSteps the most steps one formula takes when it is calculated (100,000)
 * @param maxTextChars the longest text a formula may give (100,000)
 * @param maxCalculationMillis the longest one calculation of a sheet may take, in milliseconds (2,000)
 * @param maxChainDepth the longest chain of formulas that depend on each other (10)
 * @param maxFormulas the most formulas, cells and calculated columns together, in one sheet (500)
 */
@ConfigurationProperties("platform.sheets.formulas")
record FormulaProperties(@DefaultValue("4000") int maxChars, @DefaultValue("32") int maxDepth,
        @DefaultValue("1000") int maxNodes, @DefaultValue("100000") int maxSteps,
        @DefaultValue("100000") int maxTextChars, @DefaultValue("2000") int maxCalculationMillis,
        @DefaultValue("10") int maxChainDepth, @DefaultValue("500") int maxFormulas) {

    FormulaProperties {
        range("max-chars", maxChars, 10, 4_000);
        range("max-depth", maxDepth, 2, 32);
        range("max-nodes", maxNodes, 10, 5_000);
        range("max-steps", maxSteps, 100, 10_000_000);
        range("max-text-chars", maxTextChars, 10, 1_000_000);
        range("max-calculation-millis", maxCalculationMillis, 10, 60_000);
        range("max-chain-depth", maxChainDepth, 1, 64);
        range("max-formulas", maxFormulas, 1, 5_000);
    }

    /** The bounds as the formula package takes them. */
    FormulaLimits limits() {
        return new FormulaLimits(maxChars, maxDepth, maxNodes, maxSteps, maxTextChars, maxCalculationMillis,
                maxChainDepth, maxFormulas);
    }

    /**
     * What the formulas of a sheet may be, and the functions they may call, from the server's own table, as the screens
     * read it: the list of versions of a sheet, and the fill-in (SH5.1), whose page shows a preview of the answers
     * while a person types and holds no list or number of its own.
     */
    SheetFormulaLimitsView view() {
        return new SheetFormulaLimitsView(maxChars, maxDepth, maxNodes, maxSteps, maxTextChars, maxChainDepth,
                maxFormulas, FormulaFunctions.all().stream().map(function -> new SheetFormulaFunctionView(
                        function.name(), function.least(), function.most())).toList(),
                FormulaFunctions.TEXT_FORMAT_LIST);
    }

    private static void range(String name, int value, int least, int most) {
        if (value < least || value > most) {
            throw new IllegalArgumentException("platform.sheets.formulas." + name + " must be " + least + " to "
                    + most);
        }
    }
}
