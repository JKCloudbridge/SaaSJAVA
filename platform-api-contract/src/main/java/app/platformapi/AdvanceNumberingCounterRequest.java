package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Raise, never lower, the next count of the current effective format's scope. */
public record AdvanceNumberingCounterRequest(
        @NotNull @Size(max = 5) Map<@Size(max = 60) String, @Size(max = 10) String> values,
        @NotNull @Min(1) @Max(999999999999L) Long nextValue) {
    public AdvanceNumberingCounterRequest {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    @Override
    public String toString() {
        return "AdvanceNumberingCounterRequest[redacted]";
    }
}
