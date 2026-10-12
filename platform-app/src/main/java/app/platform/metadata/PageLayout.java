package app.platform.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The structure of a record form of one object, as published (Sprint 12, ADR-0069): sections, the fields in them and
 * how each field behaves. {@code definition} is the JSON document the application already checked against the schema
 * {@code PageLayoutDefinition}; nothing in it is ever evaluated.
 *
 * @param objectApiName the object it belongs to
 * @param apiName its permanent name, ending with {@code __c}
 * @param label the label people read
 * @param description what it is for
 * @param definition the sections and fields
 * @param version the version, for concurrent changes
 */
public record PageLayout(String objectApiName, String apiName, String label, String description,
        Map<String, Object> definition, long version) {

    /** Copies what is given. */
    public PageLayout {
        definition = Collections.unmodifiableMap(new LinkedHashMap<>(definition));
    }

    /** Prints the identity only: labels and descriptions are text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "PageLayout[" + objectApiName + "." + apiName + "]";
    }
}
