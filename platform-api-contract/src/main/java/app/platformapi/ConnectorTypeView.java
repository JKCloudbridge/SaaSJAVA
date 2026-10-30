package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One kind of external system the platform can connect to, as the adapter that provides it describes itself
 * (Sprint 26, ADR-0207). The screens draw the settings form, the authentication choices and the events from this and
 * hold no list of their own.
 *
 * @param key the permanent key of the connector type
 * @param label what a person sees
 * @param description one sentence on what it connects to
 * @param version the version of this description; a connection remembers the one it was made under
 * @param authKinds the ways of authenticating the type supports, with the words a person sees
 * @param settings the settings the type asks for, in the order they are shown
 * @param platformEvents the platform events a connection of this type may listen for
 */
public record ConnectorTypeView(@NotNull String key, @NotNull String label, @NotNull String description,
        @NotNull Integer version, @NotNull List<ConnectorChoice> authKinds, @NotNull List<Setting> settings,
        @NotNull List<ConnectorChoice> platformEvents) {

    public ConnectorTypeView {
        authKinds = List.copyOf(authKinds);
        settings = List.copyOf(settings);
        platformEvents = List.copyOf(platformEvents);
    }

    /**
     * One setting of a connector type.
     *
     * @param key the name of the setting
     * @param label what a person sees
     * @param hint a short sentence on what to type
     * @param kind {@code TEXT}, {@code PATH}, {@code CHOICE}, {@code INTEGER} or {@code BOOLEAN}
     * @param required whether a value must be given
     * @param defaultValue the value used when none is given, or null
     * @param choices the allowed values of a {@code CHOICE}; empty for the other kinds
     * @param min the smallest value of an {@code INTEGER}, or null
     * @param max the largest value of an {@code INTEGER}, or null
     */
    public record Setting(@NotNull String key, @NotNull String label, @NotNull String hint, @NotNull String kind,
            @NotNull Boolean required, String defaultValue, @NotNull List<ConnectorChoice> choices, Long min,
            Long max) {

        public Setting {
            choices = List.copyOf(choices);
        }
    }

    /**
     * A key with the words a person sees.
     *
     * @param key the permanent key
     * @param label the words
     */
    public record ConnectorChoice(@NotNull String key, @NotNull String label) {
    }
}
