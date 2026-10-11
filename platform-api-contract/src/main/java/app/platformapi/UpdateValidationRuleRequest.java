package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Changes a validation rule (Sprint 13). The API name and the object never change. The text is typed by a person, so
 * it never prints.
 *
 * @param label the label people read
 * @param description what the rule is for, optional
 * @param expression the condition: when it is true the save is rejected
 * @param errorMessage the message the person who is rejected reads
 * @param errorField the field the message is shown on, optional
 * @param active whether the rule is judged when a record is saved
 * @param version the version the caller last read; a change on top of a newer one is refused
 */
public record UpdateValidationRuleRequest(@NotBlank @Size(max = 80) String label, @Size(max = 500) String description,
        @NotBlank @Size(max = 4000) String expression, @NotBlank @Size(max = 255) String errorMessage,
        @Size(max = 60) String errorField, @NotNull Boolean active, @NotNull Long version) {

    @Override
    public String toString() {
        return "UpdateValidationRuleRequest[redacted]";
    }
}
