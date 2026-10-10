package app.platform.metadata.internal;

import app.platform.metadata.FieldType;
import app.platformapi.ApiException;
import app.platformapi.ChangeRequest;
import app.platformapi.CreateFieldRequest;
import app.platformapi.CreateObjectRequest;
import app.platformapi.CreateRecordTypeRequest;
import app.platformapi.FieldSettings;
import app.platformapi.FieldView;
import app.platformapi.ObjectView;
import app.platformapi.RecordTypeView;
import app.platformapi.UpdateFieldRequest;
import app.platformapi.UpdateObjectRequest;
import app.platformapi.UpdateRecordTypeRequest;
import app.platformapi.ReleaseItemView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the request of the API into a stored {@link Change} and back (ADR-0066), and builds the changes that undo an
 * applied one (ADR-0067). The stored form is the JSON of the very request record the live endpoint takes, so a change
 * waiting in a change set is judged later by the rules a change made at once is judged by.
 */
final class ChangeCodec {

    private static final String EMPTY = "{}";
    private static final TypeReference<List<Map<String, String>>> CHANGES = new TypeReference<>() {
    };
    private static final TypeReference<List<ReleaseItemView>> ITEMS = new TypeReference<>() {
    };

    private final JsonMapper json = JsonMapper.builder().build();

    /** The stored form of an API request; refuses one that does not say what it needs to. */
    Change fromRequest(ChangeRequest request) {
        Change.Kind kind = Change.Kind.fromCode(request.kind())
                .orElseThrow(() -> ApiException.validation("kind", "Choose one of the kinds of change."));
        String object = request.objectApiName() == null ? "" : request.objectApiName().strip();
        String item = request.itemApiName() == null || request.itemApiName().isBlank() ? null
                : request.itemApiName().strip();
        if (kind.namesItem() && item == null) {
            throw ApiException.validation("itemApiName", "Name the field or record type this change is about.");
        }
        if (!kind.namesItem()) {
            item = null;
        }
        return switch (kind) {
            case CREATE_OBJECT -> {
                CreateObjectRequest body = required(request.createObject(), "createObject", kind);
                String apiName = NameRules.objectApiName(body.name());
                if (!apiName.equals(object)) {
                    throw ApiException.validation("objectApiName", "Must be the API name the request makes: "
                            + apiName + ".");
                }
                yield new Change(kind, apiName, null, write(body));
            }
            case UPDATE_OBJECT -> new Change(kind, object, null,
                    write(required(request.updateObject(), "updateObject", kind)));
            case DELETE_OBJECT, DELETE_FIELD, DELETE_RECORD_TYPE -> new Change(kind, object, item, EMPTY);
            case CREATE_FIELD -> new Change(kind, object, null,
                    write(required(request.createField(), "createField", kind)));
            case UPDATE_FIELD -> new Change(kind, object, item,
                    write(required(request.updateField(), "updateField", kind)));
            case CREATE_RECORD_TYPE -> new Change(kind, object, null,
                    write(required(request.createRecordType(), "createRecordType", kind)));
            case UPDATE_RECORD_TYPE -> new Change(kind, object, item,
                    write(required(request.updateRecordType(), "updateRecordType", kind)));
        };
    }

    private static <T> T required(T body, String field, Change.Kind kind) {
        if (body == null) {
            throw ApiException.validation(field, "A change of kind " + kind.name() + " needs this request.");
        }
        return body;
    }

    String write(Object request) {
        return json.writeValueAsString(request);
    }

    /** A change made at once by a live endpoint, carrying the request as it arrived ({@code null}: a removal). */
    Change now(Change.Kind kind, String object, String item, Object request) {
        return new Change(kind, object, item, request == null ? EMPTY : write(request));
    }

    <T> T read(Change change, Class<T> type) {
        return json.readValue(change.payload(), type);
    }

    /** The stored form of a list of changes (the undo list of a release). */
    String writeChanges(List<Change> changes) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Change change : changes) {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("kind", change.kind().name());
            one.put("object", change.object());
            one.put("item", change.item());
            one.put("payload", change.payload());
            out.add(one);
        }
        return json.writeValueAsString(out);
    }

    List<Change> readChanges(String stored) {
        List<Map<String, String>> in = json.readValue(stored, CHANGES);
        List<Change> changes = new ArrayList<>();
        for (Map<String, String> one : in) {
            changes.add(new Change(Change.Kind.valueOf(one.get("kind")), one.get("object"), one.get("item"),
                    one.get("payload")));
        }
        return changes;
    }

    List<ReleaseItemView> readItems(String stored) {
        return json.readValue(stored, ITEMS);
    }

    // ---- the changes that undo an applied one ----

    /** Removes the object that a creation made. */
    Change removeObject(String object) {
        return new Change(Change.Kind.DELETE_OBJECT, object, null, EMPTY);
    }

    Change removeField(String object, String field) {
        return new Change(Change.Kind.DELETE_FIELD, object, field, EMPTY);
    }

    Change removeRecordType(String object, String recordType) {
        return new Change(Change.Kind.DELETE_RECORD_TYPE, object, recordType, EMPTY);
    }

    /** Makes the object again as it was. */
    Change makeObject(ObjectView view) {
        return new Change(Change.Kind.CREATE_OBJECT, view.apiName(), null, write(new CreateObjectRequest(
                withoutSuffix(view.apiName()), view.label(), view.pluralLabel(), view.description())));
    }

    /** Puts the labels of the object back, to be applied on top of {@code afterVersion}. */
    Change restoreObject(ObjectView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_OBJECT, before.apiName(), null, write(new UpdateObjectRequest(
                before.label(), before.pluralLabel(), before.description(), afterVersion)));
    }

    /** Makes the field again as it was. */
    Change makeField(String object, FieldView view) {
        return new Change(Change.Kind.CREATE_FIELD, object, null, write(new CreateFieldRequest(
                withoutSuffix(view.apiName()), view.label(), view.description(), view.type(), view.required(),
                view.unique(), view.defaultValue(), settingsToSend(view))));
    }

    /** Puts the field back as it was, to be applied on top of {@code afterVersion}. */
    Change restoreField(String object, FieldView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_FIELD, object, before.apiName(), write(new UpdateFieldRequest(
                before.label(), before.description(), before.required(), before.unique(), before.defaultValue(),
                settingsToSend(before), afterVersion)));
    }

    Change makeRecordType(String object, RecordTypeView view) {
        return new Change(Change.Kind.CREATE_RECORD_TYPE, object, null, write(new CreateRecordTypeRequest(
                withoutSuffix(view.apiName()), view.label(), view.description(), view.active(), view.defaultType(),
                fieldsToSend(view), view.picklistSubsets())));
    }

    Change restoreRecordType(String object, RecordTypeView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_RECORD_TYPE, object, before.apiName(), write(
                new UpdateRecordTypeRequest(before.label(), before.description(), before.active(),
                        before.defaultType(), fieldsToSend(before), before.picklistSubsets(), afterVersion)));
    }

    private static List<String> fieldsToSend(RecordTypeView view) {
        return view.allFields() ? null : view.availableFields();
    }

    private static String withoutSuffix(String apiName) {
        return apiName.endsWith(NameRules.CUSTOM_SUFFIX)
                ? apiName.substring(0, apiName.length() - NameRules.CUSTOM_SUFFIX.length()) : apiName;
    }

    /** The settings of the view that a request of this field's type may carry (a view shows a few more). */
    private static FieldSettings settingsToSend(FieldView view) {
        Optional<FieldType> type = FieldType.fromCode(view.type());
        List<String> takes = type.map(FieldType::settings).orElse(List.of());
        FieldSettings s = view.settings();
        return new FieldSettings(keep(takes, "maxLength", s.maxLength()), keep(takes, "digits", s.digits()),
                keep(takes, "precision", s.precision()), keep(takes, "scale", s.scale()),
                keep(takes, "values", s.values()), keep(takes, "targetObject", s.targetObject()),
                keep(takes, "expression", s.expression()), keep(takes, "resultType", s.resultType()),
                keep(takes, "prefix", s.prefix()), keep(takes, "startAt", s.startAt()),
                keep(takes, "width", s.width()), keep(takes, "onDelete", s.onDelete()),
                keep(takes, "reparentable", s.reparentable()), keep(takes, "listLabel", s.listLabel()));
    }

    private static <T> T keep(List<String> takes, String setting, T value) {
        return takes.contains(setting) ? value : null;
    }
}
