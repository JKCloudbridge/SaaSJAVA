package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** The numbering series available in the active organization. */
public record NumberingSettingsView(@NotNull List<NumberingSeriesView> series) {
    public NumberingSettingsView {
        series = List.copyOf(series);
    }
}
