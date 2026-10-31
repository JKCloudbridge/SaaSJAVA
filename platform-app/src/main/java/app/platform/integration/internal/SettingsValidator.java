package app.platform.integration.internal;

import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.SettingSpec;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the settings of a connection against the description of its connector type (ADR-0208). The connector type is
 * a closed schema, so a value is never text to be run: it is a path, a choice, a number, a yes or no or a short line of
 * plain characters. A problem is a fixed sentence at a place; it never repeats what was typed, not even the name of a
 * setting the type does not have.
 */
final class SettingsValidator {

    /**
     * The outcome: the settings to store, with every default filled in, or the problems by place.
     *
     * @param values the normalized settings; empty when there are problems
     * @param problems the sentences by place ({@code settings} or {@code settings.<key>})
     */
    record Result(Map<String, String> values, Map<String, List<String>> problems) {

        Result {
            values = Map.copyOf(values);
            problems = Map.copyOf(problems);
        }

        boolean ok() {
            return problems.isEmpty();
        }
    }

    private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9 ._-]{1,100}");
    private static final Pattern WHOLE = Pattern.compile("-?[0-9]{1,18}");
    private static final Pattern URI = Pattern.compile("urn:[A-Za-z0-9._:-]{1,90}|https?://[A-Za-z0-9._~/-]{1,200}");

    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    // The root, or names joined by single slashes; a name is never made only of dots (checked beside this pattern).
    private static final Pattern DIRECTORY = Pattern.compile("/|(/[A-Za-z0-9._-]{1,64}){1,20}");

    private SettingsValidator() {
    }

    static Result validate(ConnectorDescription description, Map<String, String> given) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        description.settings().forEach(spec -> known.add(spec.key()));
        if (given.keySet().stream().anyMatch(key -> !known.contains(key))) {
            problems.put("settings", List.of("A setting was sent that this kind of connection does not have."));
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (SettingSpec spec : description.settings()) {
            String raw = given.get(spec.key());
            String value = raw == null || raw.isBlank() ? spec.defaultValue() : raw.strip();
            if (value == null) {
                if (spec.required()) {
                    problems.put("settings." + spec.key(), List.of("A value is needed."));
                }
                continue;
            }
            String problem = problem(spec, value);
            if (problem != null) {
                problems.put("settings." + spec.key(), List.of(problem));
            } else {
                values.put(spec.key(), normalized(spec, value));
            }
        }
        return problems.isEmpty() ? new Result(values, Map.of()) : new Result(Map.of(), problems);
    }

    private static String problem(SettingSpec spec, String value) {
        return switch (spec.kind()) {
            case TEXT -> TEXT.matcher(value).matches() ? null
                    : "Use letters, digits, spaces, dots, dashes and underscores, up to 100 characters.";
            case PATH -> EndpointRules.validPath(value) ? null
                    : "Use a path that starts with a slash and has no dots, spaces or query.";
            case CHOICE -> spec.choices().stream().anyMatch(choice -> choice.key().equals(value)) ? null
                    : "Choose one of the offered values.";
            case URI -> URI.matcher(value).matches() ? null
                    : "Use a urn: name or a web address without a query, up to 200 characters.";
            case INTEGER -> integerProblem(spec, value);
            case BOOLEAN -> value.equals("true") || value.equals("false") ? null : "Choose yes or no.";
            case FINGERPRINT -> FINGERPRINT.matcher(value).matches() ? null
                    : "Use the fingerprint as the usual tools print it: SHA256: and 43 characters.";
            case IDENTIFIER -> IDENTIFIER.matcher(value).matches() ? null
                    : "Use lower case letters, digits and dashes, starting with a letter, up to 40 characters.";
            case DIRECTORY -> directoryProblem(value);
        };
    }

    /** Slash separated names of plain characters; no empty or dot segment, no trailing slash except the root. */
    private static String directoryProblem(String value) {
        return DIRECTORY.matcher(value).matches() && value.length() <= 200 && !value.contains("/./")
                && !value.contains("/../") && !value.endsWith("/.") && !value.endsWith("/..") ? null
                : "Use a directory that starts with a slash and has names of letters, digits, dots, dashes and "
                        + "underscores, with no dot-only names.";
    }

    private static String integerProblem(SettingSpec spec, String value) {
        if (!WHOLE.matcher(value).matches()) {
            return "Use a whole number.";
        }
        long number = Long.parseLong(value);
        return number >= spec.min() && number <= spec.max() ? null
                : "Use a number from " + spec.min() + " to " + spec.max() + ".";
    }

    private static String normalized(SettingSpec spec, String value) {
        return spec.kind() == SettingSpec.Kind.INTEGER ? Long.toString(Long.parseLong(value)) : value;
    }
}
