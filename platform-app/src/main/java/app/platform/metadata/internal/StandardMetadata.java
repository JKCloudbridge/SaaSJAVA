package app.platform.metadata.internal;

import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The standard objects and fields of the platform (ADR-0059): what the platform itself defines, the same for every
 * organization. It is read once, from the files under {@code metadata/standard} inside the application, checked
 * completely, and then only read. There is no way to write to it while the application runs, which is what makes the
 * standard definitions protected: an organization's request has nothing to change.
 *
 * <p>Each object here already carries the system fields every object has, followed by its own standard fields.
 * The fields an organization adds to a standard object are added to the object when the catalogue is assembled.
 */
final class StandardMetadata {

    private final List<ObjectDefinition> objects;
    private final List<FieldDefinition> systemFields;
    private final Map<String, ObjectDefinition> byName = new LinkedHashMap<>();

    StandardMetadata(List<ObjectDefinition> objects, List<FieldDefinition> systemFields) {
        this.objects = List.copyOf(objects);
        this.systemFields = List.copyOf(systemFields);
        this.objects.forEach(object -> byName.put(object.apiName(), object));
    }

    /** Every standard object, by API name. */
    List<ObjectDefinition> objects() {
        return objects;
    }

    /** The standard object with the API name (matched exactly). */
    Optional<ObjectDefinition> object(String apiName) {
        return Optional.ofNullable(byName.get(apiName));
    }

    /** The fields every object has, defined once. */
    List<FieldDefinition> systemFields() {
        return systemFields;
    }
}
