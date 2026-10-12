package app.platform.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The components of a record page of one object, as published (Sprint 12, ADR-0069): which layout its details show,
 * its components in order with closed visibility rules, and its actions. {@code definition} is the JSON document the
 * application already checked against the schema {@code RecordPageDefinition}; a visibility rule is data from a closed
 * list, never text that is evaluated.
 *
 * @param objectApiName the object it belongs to
 * @param apiName its permanent name, ending with {@code __c}
 * @param label the label people read
 * @param description what it is for
 * @param definition the layout it shows, its components and its actions
 * @param version the version, for concurrent changes
 */
public record RecordPage(String objectApiName, String apiName, String label, String description,
        Map<String, Object> definition, long version) {

    /** Copies what is given. */
    public RecordPage {
        definition = Collections.unmodifiableMap(new LinkedHashMap<>(definition));
    }

    /** Prints the identity only: labels and descriptions are text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "RecordPage[" + objectApiName + "." + apiName + "]";
    }
}
