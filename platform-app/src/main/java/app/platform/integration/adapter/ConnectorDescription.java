package app.platform.integration.adapter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a connector type is: a closed, versioned description an adapter gives about itself (ADR-0207). The framework
 * reads everything it needs about the external system from here, so nothing in the core names one.
 *
 * @param key the permanent key of the type (lower case letters, digits and hyphens)
 * @param label what a person sees
 * @param description one sentence on what it connects to
 * @param version the version of this description; raised by the adapter when a setting changes meaning
 * @param authKinds the ways of authenticating it supports; at least one
 * @param settings the settings it asks for
 * @param platformEvents the platform events a connection of this type may listen for
 */
public record ConnectorDescription(String key, String label, String description, int version,
        List<AuthKind> authKinds, List<SettingSpec> settings, List<EventSpec> platformEvents) {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    /**
     * A platform event with the words a person sees.
     *
     * @param key the event, for example {@code record.created}
     * @param label the words
     */
    public record EventSpec(String key, String label) {
    }

    public ConnectorDescription {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid connector key");
        }
        if (label == null || label.isBlank() || description == null || version < 1) {
            throw new IllegalArgumentException("Invalid connector description");
        }
        authKinds = List.copyOf(authKinds);
        settings = List.copyOf(settings);
        platformEvents = List.copyOf(platformEvents);
        if (authKinds.isEmpty()) {
            throw new IllegalArgumentException("A connector supports at least one way of authenticating");
        }
        Set<String> seen = new HashSet<>();
        for (SettingSpec setting : settings) {
            if (!seen.add(setting.key())) {
                throw new IllegalArgumentException("A setting is declared twice");
            }
        }
        Set<String> events = new HashSet<>();
        for (EventSpec event : platformEvents) {
            if (!events.add(event.key())) {
                throw new IllegalArgumentException("An event is declared twice");
            }
        }
    }
}
