package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The text of one kind of notice or mail as the setup screen shows it (Sprint 25, ADR-0155): what the platform says by
 * itself and what an administrator wrote instead, with the placeholders the kind allows.
 *
 * @param kind the kind of notice, for example {@code APPROVAL_TASK}
 * @param category the category a member's preference switches
 * @param summary when the notice is created, in words
 * @param custom whether an administrator wrote the text; false means the platform's own text is used
 * @param title the title in use
 * @param body the body in use
 * @param defaultTitle the platform's own title, to start from or to go back to
 * @param defaultBody the platform's own body
 * @param placeholders the placeholders the text may use
 * @param version the version to send back when changing the text, or null when the platform's text is in use
 */
public record NotificationTemplateView(@NotNull String kind, @NotNull String category, @NotNull String summary,
        @NotNull Boolean custom, @NotNull String title, @NotNull String body, @NotNull String defaultTitle,
        @NotNull String defaultBody, @NotNull List<NotificationPlaceholderView> placeholders, Long version) {

    /** Copies the list. */
    public NotificationTemplateView {
        placeholders = List.copyOf(placeholders);
    }

    /** Prints the kind only: the text is written by a person. */
    @Override
    public String toString() {
        return "NotificationTemplateView[" + kind + "]";
    }
}
