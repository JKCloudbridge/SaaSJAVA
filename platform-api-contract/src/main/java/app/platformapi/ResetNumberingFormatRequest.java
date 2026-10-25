package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Go back to the platform's format for one setting: which one, and the version last read. Never logged. */
public record ResetNumberingFormatRequest(@NotNull NumberingSettingKind kind,
        @NotNull @Size(max = 10) String selectorValue, @NotNull @Positive Long version) {
    @Override
    public String toString() {
        return "ResetNumberingFormatRequest[redacted]";
    }
}
