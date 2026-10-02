package app.platform.security;

import java.util.List;
import java.util.Optional;

/**
 * Which objects, and which fields of them, exist for the organization of the current tenant context (ADR-0049). The
 * security module stores permissions by key and never owns objects: objects and fields are made by the metadata module
 * (Sprint 10), which then provides the implementation of this contract; until then a small configured catalogue stands
 * in (sample objects for the local profile and the tests, none in a deployment). A permission can only be given for
 * something the catalogue knows, and a key the catalogue does not know gets no answer but "no" (ADR-0050), so the
 * answer
 * never reveals whether an object exists.
 *
 * <p>Implementations answer for the thread's tenant context and run in the caller's transaction.
 */
public interface ObjectCatalog {

    /** One object. */
    record ObjectInfo(String key, String label, List<FieldInfo> fields) {

        /** Copies what is given. */
        public ObjectInfo {
            fields = List.copyOf(fields);
        }

        /** The field with the key (the bare key, without the object), if it exists. */
        public Optional<FieldInfo> field(String fieldKey) {
            return fields.stream().filter(field -> field.key().equals(fieldKey)).findFirst();
        }
    }

    /** One field of an object. */
    record FieldInfo(String key, String label) {
    }

    /** Every object that exists, in a stable order. */
    List<ObjectInfo> objects();

    /** The object with the key, if it exists. */
    default Optional<ObjectInfo> object(String key) {
        return objects().stream().filter(object -> object.key().equals(key)).findFirst();
    }
}
