package app.platform.security.internal;

import app.platformapi.QueryFilterNode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the condition of a criteria-based sharing rule as JSON text (ADR-0116). The condition is the query
 * filter model of the API, kept as data; nothing in it is ever run as text. A decimal stays a decimal, as in a record.
 */
@Component
final class CriterionCodec {

    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** The JSON text of a condition. */
    String write(QueryFilterNode criterion) {
        return json.writeValueAsString(criterion);
    }

    /** The condition in a JSON text that {@link #write} made. */
    QueryFilterNode read(String text) {
        return json.readValue(text, QueryFilterNode.class);
    }
}
