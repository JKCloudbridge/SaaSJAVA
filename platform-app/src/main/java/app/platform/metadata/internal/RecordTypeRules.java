package app.platform.metadata.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platformapi.ApiException;
import app.platformapi.PicklistSubset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of a record type (ADR-0064) in one place: which objects may have one, which fields it may offer, which
 * picklist values it may allow. Problems are collected and reported together under the name of the request field they
 * are about, in words a person can act on; nothing typed is repeated in a message.
 */
final class RecordTypeRules {

    private RecordTypeRules() {
    }

    /**
     * The checked and completed form of a record type's settings.
     *
     * @param active whether new records may use it
     * @param defaultType whether new records get it by default
     * @param allFields whether every field is available
     * @param fields the offered fields (empty when {@code allFields})
     * @param picklists the allowed values per picklist field
     */
    record Checked(boolean active, boolean defaultType, boolean allFields, List<String> fields,
            Map<String, List<String>> picklists) {
    }

    /** Whether the object may have record types: the platform's managed objects keep their records to themselves. */
    static boolean allowedOn(ObjectDefinition object) {
        return object.managedBy() == null;
    }

    /**
     * Checks the availability and the defaults of a record type of the object.
     *
     * @throws ApiException {@code VALIDATION_ERROR} listing every problem
     */
    static Checked check(ObjectDefinition object, Boolean active, Boolean defaultType, List<String> availableFields,
            List<PicklistSubset> subsets) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        boolean isActive = active == null || active;
        boolean isDefault = Boolean.TRUE.equals(defaultType);
        if (isDefault && !isActive) {
            add(problems, "defaultType", "The default record type must be active.");
        }
        Set<String> fields = new LinkedHashSet<>();
        boolean all = availableFields == null || availableFields.isEmpty();
        if (!all) {
            for (String name : availableFields) {
                FieldDefinition field = object.field(name).orElse(null);
                if (field == null) {
                    add(problems, "availableFields", "A field in the list does not exist on this object.");
                } else if (field.kind() != DefinitionKind.SYSTEM) {
                    fields.add(name);
                }
            }
        }
        Map<String, List<String>> picklists = new LinkedHashMap<>();
        if (subsets != null) {
            for (PicklistSubset subset : subsets) {
                checkSubset(problems, object, all, fields, picklists, subset);
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return new Checked(isActive, isDefault, all, new ArrayList<>(fields), picklists);
    }

    private static void checkSubset(Map<String, List<String>> problems, ObjectDefinition object, boolean all,
            Set<String> fields, Map<String, List<String>> picklists, PicklistSubset subset) {
        FieldDefinition field = object.field(subset.field()).orElse(null);
        if (field == null || !(field.configuration() instanceof PicklistConfiguration picklist)) {
            add(problems, "picklistSubsets", "A picklist in the list does not exist on this object, or is not a "
                    + "picklist.");
            return;
        }
        if (!all && !fields.contains(field.apiName())) {
            add(problems, "picklistSubsets", "A picklist in the list is not one of the available fields.");
            return;
        }
        if (picklists.containsKey(field.apiName())) {
            add(problems, "picklistSubsets", "A picklist is named twice.");
            return;
        }
        Set<String> active = new LinkedHashSet<>();
        picklist.values().stream().filter(PicklistValue::active).forEach(value -> active.add(value.value()));
        Set<String> chosen = new LinkedHashSet<>();
        for (String value : subset.values()) {
            if (!active.contains(value)) {
                add(problems, "picklistSubsets", "A value in the list is not an active value of its picklist.");
            } else {
                chosen.add(value);
            }
        }
        if (chosen.isEmpty()) {
            add(problems, "picklistSubsets", "A picklist in the list must allow at least one value.");
        }
        picklists.put(field.apiName(), new ArrayList<>(chosen));
    }

    private static void add(Map<String, List<String>> problems, String field, String problem) {
        List<String> list = problems.computeIfAbsent(field, key -> new ArrayList<>());
        if (!list.contains(problem)) {
            list.add(problem);
        }
    }
}
