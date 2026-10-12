package app.platform.metadata.internal;

import app.platform.metadata.RecordType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored form of a record type's field list and picklist subsets (ADR-0064): a JSON array of field API names, or
 * nothing at all for "every field", and a JSON object from a picklist field's API name to the values it allows. Written
 * and read by this class only; what is stored was checked by {@link RecordTypeRules} first.
 */
final class RecordTypeCodec {

    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, List<String>>> MAP = new TypeReference<>() {
    };

    private final JsonMapper json = JsonMapper.builder().build();

    /** The JSON array to store, or null when every field is available. */
    String writeFields(boolean allFields, List<String> fields) {
        return allFields ? null : json.writeValueAsString(fields);
    }

    /** The JSON object to store. */
    String writePicklists(Map<String, List<String>> picklists) {
        return json.writeValueAsString(picklists);
    }

    /** The record type a stored row describes. */
    RecordType read(RecordTypeStore.Row row) {
        boolean all = row.availableFields() == null;
        List<String> fields = all ? List.of() : json.readValue(row.availableFields(), LIST);
        Map<String, List<String>> picklists = new LinkedHashMap<>();
        if (row.picklistValues() != null && !row.picklistValues().isBlank()) {
            Map<String, List<String>> stored = json.readValue(row.picklistValues(), MAP);
            stored.forEach((field, values) -> picklists.put(field, new ArrayList<>(values)));
        }
        return new RecordType(row.objectApiName(), row.apiName(), row.label(), row.description(), row.active(),
                row.defaultType(), row.layoutRef() == null ? "" : row.layoutRef(), all, fields, picklists,
                row.version());
    }
}
