package app.platform.data.internal;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that turns the values of a record into JSON text and back (ADR-0084). Decimals are read as decimals
 * and never as floating point numbers, so a stored number comes back exactly as it went in.
 */
final class RecordJson {

    private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() { };

    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** The JSON text of values the store checked (a map, or a map of names to nothing for a patch). */
    String write(Object values) {
        return json.writeValueAsString(values);
    }

    /** The values stored in a JSON object text. */
    Map<String, Object> read(String text) {
        return json.readValue(text, VALUES);
    }
}
