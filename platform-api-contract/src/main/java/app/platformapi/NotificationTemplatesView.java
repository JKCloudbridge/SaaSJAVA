package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Every kind of notice whose text an administrator may write, with the text in use (Sprint 25, ADR-0155), and the
 * bounds the screen shows. The screen holds none of it.
 *
 * @param templates one entry per kind
 * @param titleMax the longest title
 * @param bodyMax the longest body
 */
public record NotificationTemplatesView(@NotNull List<NotificationTemplateView> templates, @NotNull Integer titleMax,
        @NotNull Integer bodyMax) {

    /** Copies the list. */
    public NotificationTemplatesView {
        templates = List.copyOf(templates);
    }
}
