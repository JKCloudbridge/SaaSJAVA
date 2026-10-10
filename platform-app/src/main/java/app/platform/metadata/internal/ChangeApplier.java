package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platformapi.ApiException;
import app.platformapi.CreateFieldRequest;
import app.platformapi.CreateObjectRequest;
import app.platformapi.CreateRecordTypeRequest;
import app.platformapi.FieldView;
import app.platformapi.ObjectView;
import app.platformapi.RecordTypeView;
import app.platformapi.ReleaseItemView;
import app.platformapi.UpdateFieldRequest;
import app.platformapi.UpdateObjectRequest;
import app.platformapi.UpdateRecordTypeRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Applies one {@link Change} to the published metadata by calling the very same services the live endpoints call, so a
 * change in a change set, a change made at once and the undo of a release are judged by one set of rules (ADR-0066).
 * Next to the result it works out the changes that would undo what it did and the items to show in the history; both
 * are read before the change is made, because afterwards the old values are gone.
 */
@Component
class ChangeApplier {

    /**
     * What applying a change did.
     *
     * @param result the view the live endpoint returns (an {@code ObjectView}, {@code FieldView},
     *        {@code RecordTypeView}, or {@code Boolean.TRUE} for a removal)
     * @param undo the changes that put things back, to be applied in this order; empty when nothing changed
     * @param items what was added, changed or removed
     */
    record Applied(Object result, List<Change> undo, List<ReleaseItemView> items) {
    }

    private final MetadataService metadata;
    private final RecordTypeService recordTypes;
    private final ChangeCodec codec;

    ChangeApplier(MetadataService metadata, RecordTypeService recordTypes, ChangeCodec codec) {
        this.metadata = metadata;
        this.recordTypes = recordTypes;
        this.codec = codec;
    }

    /**
     * Applies the change in the running transaction.
     *
     * @throws ApiException when a rule refuses it
     * @throws ProtectedDefinition when it is about something the platform defines
     */
    Applied apply(Caller caller, Change change) {
        return switch (change.kind()) {
            case CREATE_OBJECT -> createObject(caller, change);
            case UPDATE_OBJECT -> updateObject(caller, change);
            case DELETE_OBJECT -> deleteObject(caller, change);
            case CREATE_FIELD -> createField(caller, change);
            case UPDATE_FIELD -> updateField(caller, change);
            case DELETE_FIELD -> deleteField(caller, change);
            case CREATE_RECORD_TYPE -> createRecordType(caller, change);
            case UPDATE_RECORD_TYPE -> updateRecordType(caller, change);
            case DELETE_RECORD_TYPE -> deleteRecordType(caller, change);
        };
    }

    // ---- objects ----

    private Applied createObject(Caller caller, Change change) {
        ObjectView made = metadata.createObject(caller, codec.read(change, CreateObjectRequest.class));
        return new Applied(made, List.of(codec.removeObject(made.apiName())),
                List.of(item("ADDED", "OBJECT", made.apiName(), null)));
    }

    private Applied updateObject(Caller caller, Change change) {
        ObjectView before = metadata.object(change.object());
        ObjectView after = metadata.updateObject(caller, change.object(),
                codec.read(change, UpdateObjectRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreObject(before, after.version())),
                List.of(item("CHANGED", "OBJECT", after.apiName(), null)));
    }

    private Applied deleteObject(Caller caller, Change change) {
        ObjectView before = metadata.object(change.object());
        List<RecordTypeView> types = before.editable() ? recordTypes.list(change.object()) : List.of();
        metadata.deleteObject(caller, change.object());
        List<Change> undo = new ArrayList<>();
        undo.add(codec.makeObject(before));
        before.fields().stream().filter(FieldView::editable).forEach(field ->
                undo.add(codec.makeField(before.apiName(), field)));
        types.forEach(type -> undo.add(codec.makeRecordType(before.apiName(), type)));
        return new Applied(Boolean.TRUE, undo, List.of(item("REMOVED", "OBJECT", before.apiName(), null)));
    }

    // ---- fields ----

    private Applied createField(Caller caller, Change change) {
        FieldView made = metadata.createField(caller, change.object(), codec.read(change, CreateFieldRequest.class));
        return new Applied(made, List.of(codec.removeField(change.object(), made.apiName())),
                List.of(item("ADDED", "FIELD", change.object(), made.apiName())));
    }

    private Applied updateField(Caller caller, Change change) {
        FieldView before = field(change.object(), change.item());
        FieldView after = metadata.updateField(caller, change.object(), change.item(),
                codec.read(change, UpdateFieldRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreField(change.object(), before, after.version())),
                List.of(item("CHANGED", "FIELD", change.object(), after.apiName())));
    }

    private Applied deleteField(Caller caller, Change change) {
        FieldView before = field(change.object(), change.item());
        metadata.deleteField(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeField(change.object(), before)),
                List.of(item("REMOVED", "FIELD", change.object(), change.item())));
    }

    private FieldView field(String object, String field) {
        return metadata.object(object).fields().stream().filter(view -> view.apiName().equals(field)).findFirst()
                .orElseThrow(() -> ApiException.notFound("This field does not exist."));
    }

    // ---- record types ----

    private Applied createRecordType(Caller caller, Change change) {
        RecordTypeView previousDefault = defaultOf(change.object(), null);
        RecordTypeView made = recordTypes.create(caller, change.object(),
                codec.read(change, CreateRecordTypeRequest.class));
        List<Change> undo = new ArrayList<>();
        undo.add(codec.removeRecordType(change.object(), made.apiName()));
        giveDefaultBack(undo, change.object(), made, previousDefault);
        return new Applied(made, undo, List.of(item("ADDED", "RECORD_TYPE", change.object(), made.apiName())));
    }

    private Applied updateRecordType(Caller caller, Change change) {
        RecordTypeView before = recordTypes.get(change.object(), change.item());
        RecordTypeView previousDefault = defaultOf(change.object(), change.item());
        RecordTypeView after = recordTypes.update(caller, change.object(), change.item(),
                codec.read(change, UpdateRecordTypeRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        List<Change> undo = new ArrayList<>();
        undo.add(codec.restoreRecordType(change.object(), before, after.version()));
        giveDefaultBack(undo, change.object(), after, previousDefault);
        return new Applied(after, undo, List.of(item("CHANGED", "RECORD_TYPE", change.object(), after.apiName())));
    }

    /** The default record type of the object, other than the one named, or null. */
    private RecordTypeView defaultOf(String object, String except) {
        return recordTypes.list(object).stream()
                .filter(type -> type.defaultType() && !type.apiName().equals(except)).findFirst().orElse(null);
    }

    /**
     * Making a record type the default takes the mark off the old default; undoing it must put the mark back, so the
     * old default is restored after the changed one is.
     */
    private void giveDefaultBack(List<Change> undo, String object, RecordTypeView changed,
            RecordTypeView previousDefault) {
        if (previousDefault != null && changed.defaultType()) {
            RecordTypeView now = recordTypes.get(object, previousDefault.apiName());
            undo.add(codec.restoreRecordType(object, previousDefault, now.version()));
        }
    }

    private Applied deleteRecordType(Caller caller, Change change) {
        RecordTypeView before = recordTypes.get(change.object(), change.item());
        recordTypes.delete(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeRecordType(change.object(), before)),
                List.of(item("REMOVED", "RECORD_TYPE", change.object(), change.item())));
    }

    private static ReleaseItemView item(String action, String kind, String object, String item) {
        return new ReleaseItemView(action, kind, object, item);
    }
}
