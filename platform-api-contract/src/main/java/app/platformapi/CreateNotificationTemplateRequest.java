package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Writes the text of a kind of notice for the first time (Sprint 25, ADR-0155). The text is typed by a person, so it
 * never prints.
 *
 * @param kind the kind, for example {@code WORKFLOW_NOTICE}
 * @param title the title, one line, at most 80 characters
 * @param body the body, at most 500 characters; placeholders in braces from the list of the kind
 */
public record CreateNotificationTemplateRequest(@NotBlank @Size(max = 40) String kind,
        @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 1000) String body) {

    @Override
    public String toString() {
        return "CreateNotificationTemplateRequest[redacted]";
    }
}
