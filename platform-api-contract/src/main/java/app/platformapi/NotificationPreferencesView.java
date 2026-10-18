package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The signed-in member's choices of which notices and mails they want, with the words the screen shows (Sprint 25,
 * ADR-0156). The screen holds no list of categories or channels: it draws this.
 *
 * @param choices one entry per category and channel, in the order to show them
 */
public record NotificationPreferencesView(@NotNull List<PreferenceChoiceView> choices) {

    /** Copies the list. */
    public NotificationPreferencesView {
        choices = List.copyOf(choices);
    }
}
