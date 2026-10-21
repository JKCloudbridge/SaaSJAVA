package app.platform.solution.internal;

import app.platform.sharedkernel.solution.NumberSeriesDeclaration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reads the number series a solution declares, {@code solutions/<key>/numbering.yml} (FA2A, ADR-0176, ADR-0184), and
 * checks the shape of the file. What can only be checked against the objects of the platform (the field, the picklist
 * values, the closed language of the formats) is checked by the module that numbers records when the application
 * starts;
 * that the series belongs to an object of its own solution is {@link ShippedNumberSeries}'s.
 *
 * <p>Every message names the solution and the series, and never repeats a text, so a mistake in a format never copies
 * it
 * into a log. A series has either {@code format} (one format, no selector) or {@code selector} with {@code formats}
 * (a format for each value of the selector).
 */
final class NumberingLoader {

    static final String LOCATION = "classpath:solutions/*/numbering.yml";

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final Set<String> FILE_KEYS = Set.of("series");
    private static final Set<String> SERIES_KEYS = Set.of("key", "object", "field", "selector", "format", "formats");

    /**
     * A declaration with the solution whose folder it came from.
     *
     * @param solution the key of the solution (the name of the folder)
     * @param declaration the series
     */
    record Loaded(String solution, NumberSeriesDeclaration declaration) {
    }

    private NumberingLoader() {
    }

    /** Reads every file the default location finds. */
    static List<Loaded> load() {
        return load(LOCATION);
    }

    /** The same for the files a pattern finds (the tests point it at deliberately broken files). */
    static List<Loaded> load(String location) {
        try {
            List<Loaded> loaded = new ArrayList<>();
            for (Resource file : new PathMatchingResourcePatternResolver().getResources(location)) {
                loaded.addAll(read(file));
            }
            return List.copyOf(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("The number series files could not be read", e);
        }
    }

    private static List<Loaded> read(Resource file) throws IOException {
        String[] parts = file.getURL().toString().split("/");
        String solution = parts.length < 2 ? "?" : parts[parts.length - 2];
        String where = "solution '" + solution + "' numbering";
        Map<?, ?> root = mapping(parse(file, where), where, FILE_KEYS);
        if (!(root.get("series") instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException(where + ": 'series' lists at least one series");
        }
        List<Loaded> loaded = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String part = where + ": series[" + i + "]";
            Map<?, ?> one = mapping(list.get(i), part, SERIES_KEYS);
            String key = text(one, "key", part);
            if (!KEY.matcher(key).matches() || !keys.add(key)) {
                throw new IllegalStateException(part + ": 'key' is a lower case key used once in the file");
            }
            String object = text(one, "object", part);
            String field = text(one, "field", part);
            if (!NAME.matcher(object).matches() || !NAME.matcher(field).matches()) {
                throw new IllegalStateException(part + ": 'object' and 'field' are API names");
            }
            loaded.add(new Loaded(solution, new NumberSeriesDeclaration(key, object, field, selector(one, part),
                    formats(one, part))));
        }
        return loaded;
    }

    private static String selector(Map<?, ?> one, String part) {
        Object selector = one.get("selector");
        if (selector == null) {
            return "";
        }
        if (!(selector instanceof String name) || !NAME.matcher(name).matches()) {
            throw new IllegalStateException(part + ": 'selector' is an API name");
        }
        return name;
    }

    private static Map<String, String> formats(Map<?, ?> one, String part) {
        boolean single = one.get("format") != null;
        boolean several = one.get("formats") != null;
        if (single == several) {
            throw new IllegalStateException(part + ": give either 'format' or 'selector' with 'formats'");
        }
        if (single) {
            if (one.get("selector") != null) {
                throw new IllegalStateException(part + ": a series with one 'format' has no 'selector'");
            }
            return Map.of("", text(one, "format", part));
        }
        if (one.get("selector") == null) {
            throw new IllegalStateException(part + ": 'formats' needs a 'selector'");
        }
        if (!(one.get("formats") instanceof Map<?, ?> table) || table.isEmpty()) {
            throw new IllegalStateException(part + ": 'formats' maps each value of the selector to a format");
        }
        Map<String, String> formats = new TreeMap<>();
        for (Map.Entry<?, ?> entry : table.entrySet()) {
            if (!(entry.getValue() instanceof String format)) {
                throw new IllegalStateException(part + ": every format is text");
            }
            formats.put(String.valueOf(entry.getKey()), format);
        }
        return formats;
    }

    private static String text(Map<?, ?> one, String key, String part) {
        if (!(one.get(key) instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(part + ": '" + key + "' is missing or not text");
        }
        return text.strip();
    }

    private static Map<?, ?> mapping(Object value, String where, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(where + ": expected a mapping");
        }
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalStateException(where + ": '" + key + "' is not a key this part knows");
            }
        }
        return new LinkedHashMap<>(map);
    }

    private static Object parse(Resource file, String where) {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(file);
        try {
            Map<String, Object> map = yaml.getObject();
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            throw new IllegalStateException(where + ": the file is not valid YAML", e);
        }
    }
}
