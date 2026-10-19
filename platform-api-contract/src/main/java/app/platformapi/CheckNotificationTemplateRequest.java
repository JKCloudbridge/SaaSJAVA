package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A text to check before it is saved (Sprint 25, ADR-0155). Nothing is stored, audited or logged.
 *
 * @param kind the kind the text is for
 * @param title the title to check
 * @param body the body to check
 */
public record CheckNotificationTemplateRequest(@NotBlank @Size(max = 40) String kind,
        @Size(max = 1000) String title, @Size(max = 2000) String body) {

    @Override
    public String toString() {
        return "CheckNotificationTemplateRequest[redacted]";
    }
}
