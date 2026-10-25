package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** A closed format declaration and the version last read. The contents are never logged. */
public record SaveNumberingFormatRequest(@NotNull NumberingSettingKind kind,
        @NotNull @Size(max = 10) String selectorValue, @NotBlank @Size(max = 100) String format,
        @NotNull @PositiveOrZero Long version) {
    @Override
    public String toString() {
        return "SaveNumberingFormatRequest[redacted]";
    }
}
