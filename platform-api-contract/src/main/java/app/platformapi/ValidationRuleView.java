package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One validation rule of an object as the setup screens show it (Sprint 13). The expression and the message are text
 * the organization typed, so the record never prints.
 *
 * @param apiName the permanent name, for example {@code CloseDateRequired__c}
 * @param objectApiName the object it belongs to
 * @param label the label people read
 * @param description what the rule is for
 * @param expression the condition; <strong>when it is true the save is rejected</strong>
 * @param errorMessage the message the person who is rejected reads
 * @param errorField the API name of the field the message is shown on, or null for the record as a whole
 * @param active whether the rule is judged when a record is saved
 * @param version the version to send back when changing it
 */
public record ValidationRuleView(@NotNull String apiName, @NotNull String objectApiName, @NotNull String label,
        @NotNull String description, @NotNull String expression, @NotNull String errorMessage, String errorField,
        @NotNull Boolean active, @NotNull Long version) {

    @Override
    public String toString() {
        return "ValidationRuleView[redacted]";
    }
}
