package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One field an outcome of an approval sets (Sprint 24, ADR-0149): a literal written as text the way the default value
 * of a
 * field is (the same rules: {@code true}, {@code 2030-01-31}, the digits of a number). There is no copy and no
 * expression;
 * nothing here is run as code.
 *
 * @param field the API name of the field of the object
 * @param value the literal, as text
 */
public record ApprovalFieldSpec(@NotBlank @Size(max = 60) String field, @NotNull @Size(max = 1000) String value) {

    @Override
    public String toString() {
        return "ApprovalFieldSpec[redacted]";
    }
}
