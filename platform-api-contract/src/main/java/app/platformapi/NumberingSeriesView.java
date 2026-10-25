package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** One available series. The server supplies all category rows, including switched-off values. */
public record NumberingSeriesView(@NotNull String key, @NotNull String objectApiName, @NotNull String label,
        @NotNull String selector, @NotNull String optionalField, @NotNull List<NumberingFieldView> fields,
        @NotNull List<NumberingSettingView> settings) {
    public NumberingSeriesView {
        fields = List.copyOf(fields);
        settings = List.copyOf(settings);
    }
}
