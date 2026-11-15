package app.platform.solution.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What the application settings endpoints send and take (MC2B, ADR-0261). The words of a setting (its label and its
 * description) are the solution file's own; the value is the organization's. Everything here is for the screen to draw:
 * every request that follows is decided again by the server, and the browser holds no list of settings.
 */
public final class ApplicationSettingViews {

    private ApplicationSettingViews() {
    }

    /**
     * One setting of one application.
     *
     * @param key the name to send
     * @param label what people read
     * @param description what it is for
     * @param kind how the value is typed: {@code PERCENT} is a whole percent
     * @param value the value in force: the organization's own, else the default
     * @param defaultValue what the application starts with
     * @param least the smallest value the kind takes
     * @param most the largest value the kind takes
     * @param changed whether the organization chose a value of its own
     * @param version the version to send when changing or taking back its own value; null when it has none
     */
    public record ApplicationSettingView(@NotNull String key, @NotNull String label, @NotNull String description,
            @NotNull String kind, @NotNull Integer value, @NotNull Integer defaultValue, @NotNull Integer least,
            @NotNull Integer most, @NotNull Boolean changed, Long version) {

        /** Prints nothing chosen. */
        @Override
        public String toString() {
            return "ApplicationSettingView[redacted]";
        }
    }

    /**
     * The settings of one application the organization has.
     *
     * @param application the key to send
     * @param label its name for people
     * @param settings what it offers
     */
    public record ApplicationSettingsGroupView(@NotNull String application, @NotNull String label,
            @NotNull List<ApplicationSettingView> settings) {
    }

    /**
     * Every application the organization has that offers a setting.
     *
     * @param applications the applications, in the order of the grid
     */
    public record ApplicationSettingsView(@NotNull List<ApplicationSettingsGroupView> applications) {
    }

    /**
     * A choice.
     *
     * @param application the application
     * @param key the setting
     * @param value the whole number chosen
     * @param version the version read when the organization already has a value of its own; empty for the first one
     */
    public record SaveApplicationSettingRequest(@NotBlank @Size(max = 60) String application,
            @NotBlank @Size(max = 30) String key, @NotNull Integer value, Long version) {

        /** Prints nothing chosen. */
        @Override
        public String toString() {
            return "SaveApplicationSettingRequest[redacted]";
        }
    }
}
