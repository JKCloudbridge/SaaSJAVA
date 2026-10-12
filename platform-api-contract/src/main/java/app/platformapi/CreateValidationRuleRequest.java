package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds a validation rule to an object (Sprint 13). The text is typed by a person, so it never prints.
 *
 * @param name the name to build the API name from; the platform stores it with the ending {@code __c}
 * @param label the label people read
 * @param description what the rule is for, optional
 * @param expression the condition in the expression language: <strong>when it is true the save is rejected</strong>;
 *        it is parsed and checked against the fields of the object and must give true or false
 * @param errorMessage the message the person who is rejected reads (at most 255 characters)
 * @param errorField the field the message is shown on, optional
 * @param active whether the rule is judged when a record is saved (default true)
 */
public record CreateValidationRuleRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 80) String label,
        @Size(max = 500) String description, @NotBlank @Size(max = 4000) String expression,
        @NotBlank @Size(max = 255) String errorMessage, @Size(max = 60) String errorField, Boolean active) {

    @Override
    public String toString() {
        return "CreateValidationRuleRequest[redacted]";
    }
}
