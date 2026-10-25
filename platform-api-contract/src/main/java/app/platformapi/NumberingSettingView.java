package app.platformapi;

import jakarta.validation.constraints.NotNull;

/** An effective setting and the version to send when saving an organization override. Not for logs. */
public record NumberingSettingView(@NotNull NumberingSettingKind kind, @NotNull String selectorValue,
        @NotNull String label, @NotNull Boolean active, @NotNull String format, @NotNull String source,
        @NotNull Long version, @NotNull Integer width) {
    @Override
    public String toString() {
        return "NumberingSettingView[redacted]";
    }
}
