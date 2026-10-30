package app.platform.integration.adapter;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One setting a connector type asks for. A closed description: the framework validates every value against it, so an
 * adapter never receives a setting it did not declare, and the screens draw the form from it (ADR-0207).
 *
 * @param key the name of the setting
 * @param label what a person sees
 * @param hint a short sentence on what to type
 * @param kind what kind of value it is
 * @param required whether a value must be given when there is no default
 * @param defaultValue the value used when none is given, or null
 * @param choices the allowed values of a {@link Kind#CHOICE}
 * @param min the smallest value of a {@link Kind#INTEGER}, or null
 * @param max the largest value of a {@link Kind#INTEGER}, or null
 */
public record SettingSpec(String key, String label, String hint, Kind kind, boolean required, String defaultValue,
        List<Choice> choices, Long min, Long max) {

    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");

    /** The kinds of value. Closed: there is no kind that holds text to be run. */
    public enum Kind {
        /** A short line of letters, digits, spaces and a few marks. */
        TEXT,
        /** A path on the external system: starts with a slash, no dot segments, no query. */
        PATH,
        /** One of a fixed list. */
        CHOICE,
        /** A whole number inside bounds. */
        INTEGER,
        /** {@code true} or {@code false}. */
        BOOLEAN
    }

    /**
     * A key with the words a person sees.
     *
     * @param key the stored value
     * @param label the words
     */
    public record Choice(String key, String label) {
    }

    public SettingSpec {
        Objects.requireNonNull(kind, "kind");
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid setting key");
        }
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(hint, "hint");
        choices = List.copyOf(choices);
        if (kind == Kind.CHOICE) {
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("A choice needs choices");
            }
            if (defaultValue != null && choices.stream().noneMatch(choice -> choice.key().equals(defaultValue))) {
                throw new IllegalArgumentException("The default is not a choice");
            }
        } else if (!choices.isEmpty()) {
            throw new IllegalArgumentException("Only a choice has choices");
        }
        if (kind == Kind.INTEGER && (min == null || max == null || min > max)) {
            throw new IllegalArgumentException("An integer needs bounds");
        }
    }

    /** A path setting with a default. */
    public static SettingSpec path(String key, String label, String hint, String defaultValue) {
        return new SettingSpec(key, label, hint, Kind.PATH, false, defaultValue, List.of(), null, null);
    }

    /** A setting that is one of a fixed list. */
    public static SettingSpec choice(String key, String label, String hint, String defaultValue,
            List<Choice> choices) {
        return new SettingSpec(key, label, hint, Kind.CHOICE, false, defaultValue, choices, null, null);
    }

    /** A whole number between the bounds, inclusive. */
    public static SettingSpec integer(String key, String label, String hint, long defaultValue, long min, long max) {
        return new SettingSpec(key, label, hint, Kind.INTEGER, false, Long.toString(defaultValue), List.of(), min,
                max);
    }
}
