package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One field an action sets (Sprint 23, ADR-0138): a typed literal or a copy of a field of the triggering record.
 * Exactly one of {@code value} and {@code fromField} is given. A literal is written as text the way the default value
 * of a field is (the same rules: {@code true}, {@code 2030-01-31}, the digits of a number, semicolons between the
 * values of a multi-select). Nothing here is run as code.
 *
 * @param field the API name of the field to set (a field of the triggering record's object, or of the created object)
 * @param value the literal, as text
 * @param fromField the API name of a field of the triggering record whose value is copied
 */
public record WorkflowAssignmentSpec(@NotBlank @Size(max = 60) String field, @Size(max = 1000) String value,
        @Size(max = 60) String fromField) {

    @Override
    public String toString() {
        return "WorkflowAssignmentSpec[redacted]";
    }
}
