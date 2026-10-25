package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** A field a number may read and its current code values, supplied by the server. */
public record NumberingFieldView(@NotNull String apiName, @NotNull String label,
        @NotNull List<PicklistOption> values) {
    public NumberingFieldView {
        values = List.copyOf(values);
    }
}
