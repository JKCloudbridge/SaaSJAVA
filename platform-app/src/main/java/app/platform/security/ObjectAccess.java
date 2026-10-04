package app.platform.security;

import java.util.Set;
import java.util.TreeSet;

/**
 * What a member may do with one object and its fields, in one answer, for the data engine's lists and forms ("which
 * fields of this object may I show?"). An object the member may not use, and one that does not exist, both give
 * {@link #none}, so the answer reveals nothing.
 *
 * <p>Fields follow the object: a field can be read only when the object can be read, and edited only when the object
 * can
 * be created or updated, in addition to the field's own permission.
 *
 * @param object the object key
 * @param actions what the member may do with the object (implied actions written out)
 * @param readableFields the keys of the fields the member may read (bare keys, without the object)
 * @param editableFields the keys of the fields the member may edit
 */
public record ObjectAccess(String object, Set<ObjectAction> actions, Set<String> readableFields,
        Set<String> editableFields) {

    /** Copies what is given. */
    public ObjectAccess {
        actions = actions.isEmpty() ? Set.of() : Set.copyOf(actions);
        readableFields = Set.copyOf(new TreeSet<>(readableFields));
        editableFields = Set.copyOf(new TreeSet<>(editableFields));
    }

    /** No access to the object, whether or not it exists. */
    public static ObjectAccess none(String object) {
        return new ObjectAccess(object, Set.of(), Set.of(), Set.of());
    }

    /** Whether the member may do the action with the object. */
    public boolean can(ObjectAction action) {
        return actions.contains(action);
    }
}
