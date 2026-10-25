package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Preview a candidate format or, with no format, the current effective format. Never reserves a number. */
public record PreviewNumberingRequest(@NotNull NumberingSettingKind kind,
        @NotNull @Size(max = 10) String selectorValue, @Size(max = 100) String format,
        @NotNull @Size(max = 5) Map<@Size(max = 60) String, @Size(max = 10) String> values) {
    public PreviewNumberingRequest {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    @Override
    public String toString() {
        return "PreviewNumberingRequest[redacted]";
    }
}
