package app.platform.metadata.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A strict reader of one mapping in a definition file: it knows the keys that are allowed, so a misspelled key is a
 * mistake that stops the application from starting instead of a setting that silently does nothing. Every message names
 * the place in the file, so whoever edited the file sees at once what to fix.
 */
final class YamlNode {

    private final String where;
    private final Map<String, Object> values;

    @SuppressWarnings("unchecked") // the parser gives string keys; a non-mapping is refused below
    YamlNode(String where, Object value, Set<String> allowedKeys) {
        this.where = where;
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(where + ": expected a mapping of names to values");
        }
        this.values = (Map<String, Object>) map;
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(allowedKeys);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(where + ": unknown key " + unknown + "; the keys that exist are "
                    + allowedKeys);
        }
    }

    /** Where in the file this node is, for messages. */
    String where() {
        return where;
    }

    boolean has(String key) {
        return values.get(key) != null;
    }

    String text(String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException(where + ": '" + key + "' is missing");
        }
        if (!(value instanceof String text)) {
            throw new IllegalStateException(where + ": '" + key + "' must be text (put it in quotes if it looks like "
                    + "a number or a yes/no)");
        }
        return text;
    }

    String optionalText(String key) {
        return values.get(key) == null ? null : text(key);
    }

    /** A text, number or yes/no written without quotes, read as the text it was written as; null when absent. */
    String optionalScalar(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw new IllegalStateException(where + ": '" + key + "' must be a single value");
    }

    boolean flag(String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalStateException(where + ": '" + key + "' must be true or false");
        }
        return flag;
    }

    Integer optionalInteger(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number;
        }
        throw new IllegalStateException(where + ": '" + key + "' must be a whole number");
    }

    Long optionalLong(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        throw new IllegalStateException(where + ": '" + key + "' must be a whole number");
    }

    /** The nodes of a list of mappings, each limited to the allowed keys; an absent list is empty. */
    List<YamlNode> nodes(String key, Set<String> allowedKeys) {
        Object value = values.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(where + ": '" + key + "' must be a list");
        }
        List<YamlNode> nodes = new ArrayList<>();
        int index = 1;
        for (Object item : list) {
            nodes.add(new YamlNode(where + " > " + key + " #" + index, item, allowedKeys));
            index++;
        }
        return nodes;
    }

    /** One nested mapping limited to the allowed keys, or null when absent. */
    YamlNode node(String key, Set<String> allowedKeys) {
        Object value = values.get(key);
        return value == null ? null : new YamlNode(where + " > " + key, value, allowedKeys);
    }
}
