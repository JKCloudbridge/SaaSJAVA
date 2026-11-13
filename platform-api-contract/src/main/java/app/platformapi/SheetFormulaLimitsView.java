package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What the formulas of a sheet may be in this installation (SH2, ADR-0252). The server checks every formula again on
 * every save and calculates every answer; the screen reads this to check a formula while a person types and to say
 * which functions exist, and holds no list or number of its own.
 *
 * @param maxChars the longest formula in its stored form
 * @param maxDepth the deepest nesting of brackets, calls and signs
 * @param maxNodes the most parts of one formula
 * @param maxSteps the most steps one formula takes when it is calculated
 * @param maxTextChars the longest text a formula may give
 * @param maxChainDepth the longest chain of formulas that depend on each other
 * @param maxFormulas the most formulas in one sheet
 * @param functions the functions a formula may call, with the number of arguments each takes
 * @param textFormats the formats the second argument of TEXT may be, as a text in quotes
 */
public record SheetFormulaLimitsView(@NotNull Integer maxChars, @NotNull Integer maxDepth, @NotNull Integer maxNodes,
        @NotNull Integer maxSteps, @NotNull Integer maxTextChars, @NotNull Integer maxChainDepth,
        @NotNull Integer maxFormulas, @NotNull List<SheetFormulaFunctionView> functions,
        @NotNull List<String> textFormats) {

    /** Copies the lists, so that they cannot be changed from outside. */
    public SheetFormulaLimitsView {
        functions = functions == null ? null : List.copyOf(functions);
        textFormats = textFormats == null ? null : List.copyOf(textFormats);
    }
}
