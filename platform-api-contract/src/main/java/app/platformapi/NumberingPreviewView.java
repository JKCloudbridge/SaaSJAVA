package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** An observation, not a reservation: another approval can take this number before it is used. */
public record NumberingPreviewView(String example, Long nextValue, @NotNull Integer width,
        @NotNull List<String> fields, @NotNull List<String> missingFields, @NotNull String message) {
    public NumberingPreviewView {
        fields = List.copyOf(fields);
        missingFields = List.copyOf(missingFields);
    }

    @Override
    public String toString() {
        return "NumberingPreviewView[redacted]";
    }
}
