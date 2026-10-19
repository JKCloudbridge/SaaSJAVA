package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One part of the health of automation (Sprint 25, ADR-0163).
 *
 * @param key a stable name, for example {@code workflows}
 * @param title the words for the part
 * @param items the counts
 */
public record AutomationHealthSectionView(@NotNull String key, @NotNull String title,
        @NotNull List<AutomationHealthItemView> items) {

    /** Copies the list. */
    public AutomationHealthSectionView {
        items = List.copyOf(items);
    }
}
