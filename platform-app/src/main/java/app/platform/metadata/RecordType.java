package app.platform.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A variant of an object (ADR-0064): "New business" and "Renewal" for an opportunity. It says which fields a record of
 * that variant offers and which picklist values it allows; the page layout it uses is a placeholder until Sprint 12.
 * Record types are published metadata like objects and fields: what the runtime reads is what is published.
 *
 * @param objectApiName the object the record type belongs to
 * @param apiName the permanent name used by code and integrations ({@code Renewal__c})
 * @param label the label people read
 * @param description what the variant is for
 * @param active whether new records may use it (an inactive one still describes the records that have it)
 * @param defaultType whether new records get it when nothing else is chosen (at most one per object, always active)
 * @param layout the page layout it uses, or empty while layouts do not exist (Sprint 12)
 * @param allFields whether every field of the object is available; when true {@code availableFields} is empty
 * @param availableFields the API names of the fields a record of this type offers (system fields always are)
 * @param picklistValues for a picklist field, the values this type allows; a picklist that is not named allows all
 *        its values
 * @param version the version, for concurrent changes
 */
public record RecordType(String objectApiName, String apiName, String label, String description, boolean active,
        boolean defaultType, String layout, boolean allFields, List<String> availableFields,
        Map<String, List<String>> picklistValues, long version) {

    /** Copies what is given. */
    public RecordType {
        availableFields = List.copyOf(availableFields);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        picklistValues.forEach((field, values) -> copy.put(field, List.copyOf(values)));
        picklistValues = Collections.unmodifiableMap(copy);
    }

    /** Prints the identity only: labels and descriptions are text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "RecordType[" + objectApiName + "." + apiName + "]";
    }
}
