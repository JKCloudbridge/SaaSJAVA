package app.platform.security;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What one container (a profile, an access policy, an individual grant) or a whole member may do with data: actions on
 * objects and actions on fields, each by key (ADR-0049, ADR-0050). An object is named by its key (for example
 * {@code object-a}), a field by {@code <object key>.<field key>}.
 *
 * <p>Combining is a plain union (ADR-0040): {@link #union} never removes anything and gives the same result in any
 * order
 * and grouping. {@code everything} stands for "every object and every field", which is what the administrator profile
 * holds; it is not a list because the objects of an organization are not known to the calculator.
 *
 * <p>The implications between actions ({@link ObjectAction#implied()}, {@link FieldAction#implied()}) are part of the
 * questions: {@link #allows} and {@link #allowsField} answer yes for an action that a held action implies, so a value
 * that was never "closed" gives the same answers as one that was ({@link #closed()} only spells them out for display).
 *
 * @param everything whether the holder may do everything with every object and field
 * @param objects the actions on each object key
 * @param fields the actions on each field key
 */
public record DataAccess(boolean everything, Map<String, Set<ObjectAction>> objects,
        Map<String, Set<FieldAction>> fields) {

    private static final DataAccess NONE = new DataAccess(false, Map.of(), Map.of());
    private static final DataAccess EVERYTHING = new DataAccess(true, Map.of(), Map.of());

    /** Copies what is given, so the value can never change afterwards. */
    public DataAccess {
        objects = copy(objects);
        fields = copy(fields);
    }

    /** Nothing at all. */
    public static DataAccess none() {
        return NONE;
    }

    /** Every action on every object and field (the administrator profile). */
    public static DataAccess fullAccess() {
        return EVERYTHING;
    }

    /** What the given actions allow; empty entries are left out. */
    public static DataAccess of(Map<String, Set<ObjectAction>> objects, Map<String, Set<FieldAction>> fields) {
        return new DataAccess(false, objects, fields);
    }

    /** Whether there is nothing in it. */
    public boolean isEmpty() {
        return !everything && objects.isEmpty() && fields.isEmpty();
    }

    /** The union of this and the other: everything either allows, nothing less. */
    public DataAccess union(DataAccess other) {
        if (other.isEmpty()) {
            return this;
        }
        Map<String, Set<ObjectAction>> mergedObjects = new HashMap<>(objects);
        other.objects.forEach((key, actions) -> mergedObjects.merge(key, actions, DataAccess::merge));
        Map<String, Set<FieldAction>> mergedFields = new HashMap<>(fields);
        other.fields.forEach((key, actions) -> mergedFields.merge(key, actions, DataAccess::merge));
        return new DataAccess(everything || other.everything, mergedObjects, mergedFields);
    }

    /** This with every implied action written out (modify-all lists all six). Answers do not change. */
    public DataAccess closed() {
        Map<String, Set<ObjectAction>> closedObjects = new HashMap<>();
        objects.forEach((key, actions) -> closedObjects.put(key, closeObject(actions)));
        Map<String, Set<FieldAction>> closedFields = new HashMap<>();
        fields.forEach((key, actions) -> closedFields.put(key, closeField(actions)));
        return new DataAccess(everything, closedObjects, closedFields);
    }

    /** Whether the holder may do the action with the object (implied actions count). */
    public boolean allows(String objectKey, ObjectAction action) {
        if (everything) {
            return true;
        }
        Set<ObjectAction> held = objects.get(objectKey);
        return held != null && closeObject(held).contains(action);
    }

    /** Whether the holder may do the action with the field, named {@code object.field} (implied actions count). */
    public boolean allowsField(String fieldKey, FieldAction action) {
        if (everything) {
            return true;
        }
        Set<FieldAction> held = fields.get(fieldKey);
        return held != null && closeField(held).contains(action);
    }

    /**
     * Whether everything the other allows, this allows too (implied actions count on both sides). Used to refuse giving
     * away more than one holds.
     */
    public boolean covers(DataAccess other) {
        if (everything) {
            return true;
        }
        if (other.everything) {
            return false;
        }
        for (Map.Entry<String, Set<ObjectAction>> entry : other.objects.entrySet()) {
            for (ObjectAction action : closeObject(entry.getValue())) {
                if (!allows(entry.getKey(), action)) {
                    return false;
                }
            }
        }
        for (Map.Entry<String, Set<FieldAction>> entry : other.fields.entrySet()) {
            for (FieldAction action : closeField(entry.getValue())) {
                if (!allowsField(entry.getKey(), action)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The keys of the objects that have an entry, sorted. */
    public Set<String> objectKeys() {
        return new TreeSet<>(objects.keySet());
    }

    private static Set<ObjectAction> closeObject(Set<ObjectAction> held) {
        Set<ObjectAction> result = EnumSet.noneOf(ObjectAction.class);
        for (ObjectAction action : held) {
            result.add(action);
            result.addAll(action.implied());
        }
        return result;
    }

    private static Set<FieldAction> closeField(Set<FieldAction> held) {
        Set<FieldAction> result = EnumSet.noneOf(FieldAction.class);
        for (FieldAction action : held) {
            result.add(action);
            result.addAll(action.implied());
        }
        return result;
    }

    private static <E extends Enum<E>> Set<E> merge(Set<E> left, Set<E> right) {
        Set<E> result = new TreeSet<>(left);
        result.addAll(right);
        return result;
    }

    private static <E extends Enum<E>> Map<String, Set<E>> copy(Map<String, Set<E>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<E>> sorted = new TreeMap<>();
        source.forEach((key, actions) -> {
            if (key != null && actions != null && !actions.isEmpty()) {
                sorted.put(key, Set.copyOf(actions));
            }
        });
        return Map.copyOf(sorted);
    }
}
