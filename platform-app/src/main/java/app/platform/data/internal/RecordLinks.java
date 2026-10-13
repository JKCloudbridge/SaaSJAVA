package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.metadata.DeleteBehaviour;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The rules about links between records that are not about one request (Sprint 16, ADR-0106): which fields of an
 * object are links, what removing the record a link points at does to the record that holds it, whether an object is a
 * junction, and the upkeep of the table of pointers.
 *
 * <p>The pointers are kept in the transaction of the write that sets, changes or clears a link, so that the question
 * "who points at this record?" never needs to read the records of other objects. A link to an object that a platform
 * module owns (a user) is recorded too.
 */
@Component
class RecordLinks {

    /** The identifier that stands for "no record" where a statement needs one. */
    static final UUID NONE = new UUID(0L, 0L);

    private final RecordLinkStore store;
    private final Metadata metadata;

    RecordLinks(RecordLinkStore store, Metadata metadata) {
        this.store = store;
        this.metadata = metadata;
    }

    /** The lookup and master-detail fields of an object. */
    static List<FieldDefinition> referenceFields(ObjectDefinition object) {
        return object.fields().stream().filter(field -> field.configuration() instanceof ReferenceConfiguration)
                .toList();
    }

    /**
     * What removing the record a field points at does to the record that holds the field: a master-detail removes it
     * with its master; a lookup clears the pointer or refuses, as the field says, and a required lookup always refuses
     * (a record without its required value would not be valid).
     */
    static DeleteBehaviour behaviourOf(FieldDefinition field) {
        if (field.type() == FieldType.MASTER_DETAIL) {
            return DeleteBehaviour.CASCADE;
        }
        if (field.required()) {
            return DeleteBehaviour.REFUSE;
        }
        return field.configuration() instanceof ReferenceConfiguration reference ? reference.onDelete()
                : DeleteBehaviour.CLEAR;
    }

    /** Whether the object is a junction between two others: it has exactly two master-detail fields. */
    static boolean isJunction(ObjectDefinition object) {
        return masterDetails(object).size() == 2;
    }

    /** The two master-detail fields of a junction, in the order of their API names. */
    static List<FieldDefinition> masterDetails(ObjectDefinition object) {
        return object.fields().stream().filter(field -> field.type() == FieldType.MASTER_DETAIL)
                .sorted(Comparator.comparing(FieldDefinition::apiName)).toList();
    }

    /**
     * Keeps the pointers of a record right after a write: a link given a value points at that record, a link that was
     * emptied points at nothing.
     *
     * @param set the values the write stored
     * @param cleared the fields the write emptied
     */
    void sync(ObjectDefinition object, UUID recordId, Map<String, Object> set, Collection<String> cleared,
            UUID actor) {
        for (FieldDefinition field : referenceFields(object)) {
            if (cleared.contains(field.apiName())) {
                store.remove(recordId, field.apiName());
            } else if (set.containsKey(field.apiName())) {
                pointAt(object, recordId, field, set.get(field.apiName()), actor);
            }
        }
    }

    /** Makes the pointers of a whole record match its values (a record that comes back from the recycle bin). */
    void syncAll(ObjectDefinition object, DataRecord record, UUID actor) {
        for (FieldDefinition field : referenceFields(object)) {
            Object value = record.fields().get(field.apiName());
            if (value == null) {
                store.remove(record.id(), field.apiName());
            } else {
                pointAt(object, record.id(), field, value, actor);
            }
        }
    }

    private void pointAt(ObjectDefinition object, UUID recordId, FieldDefinition field, Object value, UUID actor) {
        UUID target = uuidOf(value);
        if (target == null) {
            store.remove(recordId, field.apiName());
            return;
        }
        ReferenceConfiguration reference = (ReferenceConfiguration) field.configuration();
        store.upsert(object.apiName(), recordId, field.apiName(), reference.targetObject(), target, actor);
    }

    /**
     * Whether the pointers to records of this object are not all known yet: some link field that points at it is still
     * being filled in for older records. Until they are, nobody can say who points at a record, and removing one is
     * refused.
     */
    boolean incomplete(String targetObject) {
        List<RecordLinkStore.Index> indexes = store.indexes();
        for (ObjectDefinition object : metadata.objects()) {
            if (object.managedBy() != null) {
                continue;
            }
            for (FieldDefinition field : referenceFields(object)) {
                ReferenceConfiguration reference = (ReferenceConfiguration) field.configuration();
                if (!reference.targetObject().equals(targetObject)) {
                    continue;
                }
                boolean complete = indexes.stream().anyMatch(index -> index.objectApiName().equals(object.apiName())
                        && index.fieldApiName().equals(field.apiName())
                        && index.state() == RecordLinkStore.State.FILLED);
                if (!complete) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The pointers at a record, at most {@code limit}. */
    List<RecordLinkStore.Incoming> incoming(UUID target, int limit) {
        return store.incoming(target, limit);
    }

    /**
     * Whether another record of the junction links the same two parents. Takes a lock on the pair for the rest of the
     * transaction (see {@link RecordLinkStore#pairTaken}).
     */
    boolean pairTaken(ObjectDefinition junction, Map<String, Object> values, UUID except) {
        List<FieldDefinition> fields = masterDetails(junction);
        UUID left = uuidOf(values.get(fields.get(0).apiName()));
        UUID right = uuidOf(values.get(fields.get(1).apiName()));
        if (left == null || right == null) {
            return false;
        }
        return store.pairTaken(junction.apiName(), fields.get(0).apiName(), left, fields.get(1).apiName(), right,
                except == null ? NONE : except);
    }

    /** The identifier a link value holds, or null. */
    static UUID uuidOf(Object value) {
        if (value instanceof String text && text.length() == 36) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
