package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A stored formula that does not pass the expression language (Sprint 13, ADR-0077): it was saved before formulas were
 * checked. It keeps its text and nothing is removed, but it is not calculated and it cannot be saved again until its
 * text is fixed.
 *
 * @param objectApiName the object it belongs to
 * @param fieldApiName the formula field
 * @param problem what is wrong and where
 */
public record FormulaReportItemView(@NotNull String objectApiName, @NotNull String fieldApiName,
        @NotNull String problem) {

    @Override
    public String toString() {
        return "FormulaReportItemView[redacted]";
    }
}
