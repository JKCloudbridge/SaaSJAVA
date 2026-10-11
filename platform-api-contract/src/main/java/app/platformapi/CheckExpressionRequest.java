package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Asks whether an expression is valid for an object, without storing anything (Sprint 13). The text is typed by a
 * person, so it never prints.
 *
 * @param objectApiName the object whose fields the expression may use
 * @param expression the expression text
 * @param purpose {@code FORMULA} or {@code VALIDATION_RULE}
 * @param resultType for a formula: the type it must give ({@code TEXT}, {@code NUMBER}, {@code DECIMAL},
 *        {@code CURRENCY}, {@code PERCENT}, {@code BOOLEAN}, {@code DATE}, {@code DATETIME} or {@code TIME}); optional
 */
public record CheckExpressionRequest(@NotBlank @Size(max = 60) String objectApiName,
        @NotBlank @Size(max = 4000) String expression, @NotBlank @Size(max = 20) String purpose,
        @Size(max = 20) String resultType) {

    @Override
    public String toString() {
        return "CheckExpressionRequest[redacted]";
    }
}
