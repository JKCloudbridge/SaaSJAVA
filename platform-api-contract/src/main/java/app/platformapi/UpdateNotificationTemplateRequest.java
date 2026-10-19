package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Changes the text of a kind of notice (Sprint 25, ADR-0155). The text is typed by a person, so it never prints.
 *
 * @param title the title, one line, at most 80 characters
 * @param body the body, at most 500 characters
 * @param version the version the caller last read; a change on top of a newer one is refused
 */
public record UpdateNotificationTemplateRequest(@NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 1000) String body, @NotNull Long version) {

    @Override
    public String toString() {
        return "UpdateNotificationTemplateRequest[redacted]";
    }
}
