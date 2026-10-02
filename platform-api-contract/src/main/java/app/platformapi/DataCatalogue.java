package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a permission matrix can be about (Sprint 8): the objects that exist, with their fields, and the actions the
 * platform knows. Objects themselves arrive with the metadata engine (Sprint 10); until then the catalogue lists only
 * what a local or test configuration defines, and an organization sees an empty list.
 *
 * @param objects the objects with their fields
 * @param objectActions the actions on an object, with what each one implies
 * @param fieldActions the actions on a field, with what each one implies
 */
public record DataCatalogue(@NotNull List<DataObject> objects, @NotNull List<DataAction> objectActions,
        @NotNull List<DataAction> fieldActions) {

    public DataCatalogue {
        objects = objects == null ? List.of() : List.copyOf(objects);
        objectActions = objectActions == null ? List.of() : List.copyOf(objectActions);
        fieldActions = fieldActions == null ? List.of() : List.copyOf(fieldActions);
    }

    @Override
    public String toString() {
        return "DataCatalogue[redacted]";
    }

    /**
     * An object that exists.
     *
     * @param key the object key
     * @param label the name for people
     * @param fields the fields of the object
     */
    public record DataObject(@NotNull String key, @NotNull String label, @NotNull List<DataField> fields) {

        public DataObject {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "DataObject[redacted]";
        }
    }

    /**
     * A field of an object.
     *
     * @param key the field key (without the object); the key of its permission is {@code <object key>.<field key>}
     * @param label the name for people
     */
    public record DataField(@NotNull String key, @NotNull String label) {

        @Override
        public String toString() {
            return "DataField[redacted]";
        }
    }

    /**
     * An action a permission can allow.
     *
     * @param key the action key
     * @param title the name for people
     * @param implies the keys of the actions it includes, for example update includes read
     */
    public record DataAction(@NotNull String key, @NotNull String title, @NotNull List<String> implies) {

        public DataAction {
            implies = implies == null ? List.of() : List.copyOf(implies);
        }
    }
}
