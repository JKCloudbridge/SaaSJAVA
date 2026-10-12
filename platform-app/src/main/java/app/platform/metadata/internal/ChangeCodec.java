package app.platform.metadata.internal;

import app.platform.metadata.FieldType;
import app.platform.metadata.LayoutAssignment;
import app.platform.metadata.RecordTypeAssignment;
import app.platformapi.ApiException;
import app.platformapi.ApplicationView;
import app.platformapi.ChangeRequest;
import app.platformapi.CreateApplicationRequest;
import app.platformapi.CreateFieldRequest;
import app.platformapi.CreateLayoutAssignmentRequest;
import app.platformapi.CreateLayoutRequest;
import app.platformapi.CreateObjectRequest;
import app.platformapi.CreateRecordPageRequest;
import app.platformapi.CreateRecordTypeAssignmentRequest;
import app.platformapi.CreateRecordTypeRequest;
import app.platformapi.CreateValidationRuleRequest;
import app.platformapi.FieldSettings;
import app.platformapi.FieldView;
import app.platformapi.LayoutAssignmentView;
import app.platformapi.LayoutView;
import app.platformapi.ObjectView;
import app.platformapi.RecordPageView;
import app.platformapi.RecordTypeView;
import app.platformapi.ReleaseItemView;
import app.platformapi.UpdateApplicationRequest;
import app.platformapi.UpdateFieldRequest;
import app.platformapi.UpdateLayoutAssignmentRequest;
import app.platformapi.UpdateLayoutRequest;
import app.platformapi.UpdateObjectRequest;
import app.platformapi.UpdateRecordPageRequest;
import app.platformapi.RecordTypeAssignmentView;
import app.platformapi.UpdateRecordTypeAssignmentRequest;
import app.platformapi.UpdateRecordTypeRequest;
import app.platformapi.UpdateValidationRuleRequest;
import app.platformapi.ValidationRuleView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
        boolean application = kind.entity() == Change.Entity.APPLICATION;
        String object = request.objectApiName() == null || application ? "" : request.objectApiName().strip();
        if (object.isEmpty() && !application) {
            throw ApiException.validation("objectApiName", "Name the object this change is about.");
        }
        String item = request.itemApiName() == null || request.itemApiName().isBlank() ? null
                : request.itemApiName().strip();
        // The key of a new layout assignment comes from its request, so it is not asked for twice.
        if (kind.namesItem() && item == null && kind != Change.Kind.CREATE_LAYOUT_ASSIGNMENT
                && kind != Change.Kind.CREATE_RECORD_TYPE_ASSIGNMENT) {
            throw ApiException.validation("itemApiName", "Name the field, record type, layout, record page, "
                    + "application or assignment this change is about.");
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
            case DELETE_OBJECT, DELETE_FIELD, DELETE_RECORD_TYPE, DELETE_LAYOUT, DELETE_RECORD_PAGE,
                    DELETE_APPLICATION, DELETE_LAYOUT_ASSIGNMENT, DELETE_VALIDATION_RULE,
                    DELETE_RECORD_TYPE_ASSIGNMENT ->
                    new Change(kind, object, item, EMPTY);
            case CREATE_VALIDATION_RULE -> new Change(kind, object, null,
                    write(required(request.createValidationRule(), "createValidationRule", kind)));
            case UPDATE_VALIDATION_RULE -> new Change(kind, object, item,
                    write(required(request.updateValidationRule(), "updateValidationRule", kind)));
            case CREATE_FIELD -> new Change(kind, object, null,
                    write(required(request.createField(), "createField", kind)));
            case UPDATE_FIELD -> new Change(kind, object, item,
                    write(required(request.updateField(), "updateField", kind)));
            case CREATE_RECORD_TYPE -> new Change(kind, object, null,
                    write(required(request.createRecordType(), "createRecordType", kind)));
            case UPDATE_RECORD_TYPE -> new Change(kind, object, item,
                    write(required(request.updateRecordType(), "updateRecordType", kind)));
            case CREATE_LAYOUT -> new Change(kind, object, null,
                    write(required(request.createLayout(), "createLayout", kind)));
            case UPDATE_LAYOUT -> new Change(kind, object, item,
                    write(required(request.updateLayout(), "updateLayout", kind)));
            case CREATE_RECORD_PAGE -> new Change(kind, object, null,
                    write(required(request.createRecordPage(), "createRecordPage", kind)));
            case UPDATE_RECORD_PAGE -> new Change(kind, object, item,
                    write(required(request.updateRecordPage(), "updateRecordPage", kind)));
            case CREATE_APPLICATION -> new Change(kind, "", null,
                    write(required(request.createApplication(), "createApplication", kind)));
            case UPDATE_APPLICATION -> new Change(kind, "", item,
                    write(required(request.updateApplication(), "updateApplication", kind)));
            case CREATE_LAYOUT_ASSIGNMENT -> {
                CreateLayoutAssignmentRequest body = required(request.createLayoutAssignment(),
                        "createLayoutAssignment", kind);
                String type = body.recordType() == null || body.recordType().isBlank() ? null
                        : body.recordType().strip();
                yield new Change(kind, object, LayoutAssignment.keyOf(body.profileId(), type), write(body));
            }
            case UPDATE_LAYOUT_ASSIGNMENT -> new Change(kind, object, item,
                    write(required(request.updateLayoutAssignment(), "updateLayoutAssignment", kind)));
            case CREATE_RECORD_TYPE_ASSIGNMENT -> {
                CreateRecordTypeAssignmentRequest body = required(request.createRecordTypeAssignment(),
                        "createRecordTypeAssignment", kind);
                if (body.recordType() == null || body.recordType().isBlank()) {
                    throw ApiException.validation("createRecordTypeAssignment.recordType",
                            "Name the record type this assignment is for.");
                }
                yield new Change(kind, object, RecordTypeAssignment.keyOf(body.recordType().strip(),
                        body.profileId()), write(body));
            }
            case UPDATE_RECORD_TYPE_ASSIGNMENT -> new Change(kind, object, item,
                    write(required(request.updateRecordTypeAssignment(), "updateRecordTypeAssignment", kind)));
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

    /** The version a restoring change (an update that puts values back) expects to find on the thing it restores. */
    long expectedVersion(Change restoring) {
        return json.readTree(restoring.payload()).path("version").longValue();
    }

    /** What a restoring change is about, as a key: its kind, object and item. */
    static String restoreKey(Change restoring) {
        return restoring.kind() + "|" + restoring.object() + "|" + restoring.item();
    }

    /** The key of the restoring change that would act on the thing this creation makes again. */
    String restoreKeyOfCreation(Change creation) {
        String name = json.readTree(creation.payload()).path("name").asString();
        return switch (creation.kind()) {
            case CREATE_OBJECT -> restoreKey(new Change(Change.Kind.UPDATE_OBJECT, creation.object(), null, EMPTY));
            case CREATE_FIELD -> restoreKey(new Change(Change.Kind.UPDATE_FIELD, creation.object(),
                    withSuffix(name), EMPTY));
            case CREATE_RECORD_TYPE -> restoreKey(new Change(Change.Kind.UPDATE_RECORD_TYPE, creation.object(),
                    withSuffix(name), EMPTY));
            case CREATE_LAYOUT -> restoreKey(new Change(Change.Kind.UPDATE_LAYOUT, creation.object(),
                    withSuffix(name), EMPTY));
            case CREATE_RECORD_PAGE -> restoreKey(new Change(Change.Kind.UPDATE_RECORD_PAGE, creation.object(),
                    withSuffix(name), EMPTY));
            case CREATE_APPLICATION -> restoreKey(new Change(Change.Kind.UPDATE_APPLICATION, creation.object(),
                    withSuffix(name), EMPTY));
            case CREATE_LAYOUT_ASSIGNMENT -> restoreKey(new Change(Change.Kind.UPDATE_LAYOUT_ASSIGNMENT,
                    creation.object(), creation.item(), EMPTY));
            case CREATE_RECORD_TYPE_ASSIGNMENT -> restoreKey(new Change(Change.Kind.UPDATE_RECORD_TYPE_ASSIGNMENT,
                    creation.object(), creation.item(), EMPTY));
            case CREATE_VALIDATION_RULE -> restoreKey(new Change(Change.Kind.UPDATE_VALIDATION_RULE,
                    creation.object(), withSuffix(name), EMPTY));
            default -> throw new IllegalArgumentException("Not a creation: " + creation.kind());
        };
    }

    /** The same restoring change, expecting another version. */
    Change expecting(Change restoring, long version) {
        ObjectNode request = (ObjectNode) json.readTree(restoring.payload());
        request.put("version", version);
        return new Change(restoring.kind(), restoring.object(), restoring.item(), json.writeValueAsString(request));
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
                fieldsToSend(view), view.picklistSubsets(), view.layout())));
    }

    Change restoreRecordType(String object, RecordTypeView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_RECORD_TYPE, object, before.apiName(), write(
                new UpdateRecordTypeRequest(before.label(), before.description(), before.active(),
                        before.defaultType(), fieldsToSend(before), before.picklistSubsets(), before.layout(),
                        afterVersion)));
    }

    // ---- layouts, record pages, applications and layout assignments (Sprint 12) ----

    Change removeLayout(String object, String layout) {
        return new Change(Change.Kind.DELETE_LAYOUT, object, layout, EMPTY);
    }

    Change makeLayout(LayoutView view) {
        return new Change(Change.Kind.CREATE_LAYOUT, view.objectApiName(), null, write(new CreateLayoutRequest(
                withoutSuffix(view.apiName()), view.label(), view.description(), view.definition())));
    }

    Change restoreLayout(LayoutView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_LAYOUT, before.objectApiName(), before.apiName(), write(
                new UpdateLayoutRequest(before.label(), before.description(), before.definition(), afterVersion)));
    }

    Change removeRecordPage(String object, String page) {
        return new Change(Change.Kind.DELETE_RECORD_PAGE, object, page, EMPTY);
    }

    Change makeRecordPage(RecordPageView view) {
        return new Change(Change.Kind.CREATE_RECORD_PAGE, view.objectApiName(), null, write(
                new CreateRecordPageRequest(withoutSuffix(view.apiName()), view.label(), view.description(),
                        view.definition())));
    }

    Change restoreRecordPage(RecordPageView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_RECORD_PAGE, before.objectApiName(), before.apiName(), write(
                new UpdateRecordPageRequest(before.label(), before.description(), before.definition(),
                        afterVersion)));
    }

    Change removeApplication(String application) {
        return new Change(Change.Kind.DELETE_APPLICATION, "", application, EMPTY);
    }

    Change makeApplication(ApplicationView view) {
        return new Change(Change.Kind.CREATE_APPLICATION, "", null, write(new CreateApplicationRequest(
                withoutSuffix(view.apiName()), view.label(), view.description(), view.definition())));
    }

    Change restoreApplication(ApplicationView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_APPLICATION, "", before.apiName(), write(
                new UpdateApplicationRequest(before.label(), before.description(), before.definition(),
                        afterVersion)));
    }

    Change removeAssignment(String object, String key) {
        return new Change(Change.Kind.DELETE_LAYOUT_ASSIGNMENT, object, key, EMPTY);
    }

    Change makeAssignment(String object, LayoutAssignmentView view) {
        return new Change(Change.Kind.CREATE_LAYOUT_ASSIGNMENT, object, view.key(), write(
                new CreateLayoutAssignmentRequest(view.profileId(), view.recordType(), view.layout())));
    }

    Change restoreAssignment(String object, LayoutAssignmentView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_LAYOUT_ASSIGNMENT, object, before.key(), write(
                new UpdateLayoutAssignmentRequest(before.layout(), afterVersion)));
    }

    // ---- record type assignments (Sprint 15A) ----

    Change removeTypeAssignment(String object, String key) {
        return new Change(Change.Kind.DELETE_RECORD_TYPE_ASSIGNMENT, object, key, EMPTY);
    }

    Change makeTypeAssignment(String object, RecordTypeAssignmentView view) {
        return new Change(Change.Kind.CREATE_RECORD_TYPE_ASSIGNMENT, object, view.key(), write(
                new CreateRecordTypeAssignmentRequest(view.recordType(), view.profileId(), view.defaultForProfile())));
    }

    Change restoreTypeAssignment(String object, RecordTypeAssignmentView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_RECORD_TYPE_ASSIGNMENT, object, before.key(), write(
                new UpdateRecordTypeAssignmentRequest(before.defaultForProfile(), afterVersion)));
    }

    // ---- validation rules (Sprint 13) ----

    Change removeValidationRule(String object, String rule) {
        return new Change(Change.Kind.DELETE_VALIDATION_RULE, object, rule, EMPTY);
    }

    Change makeValidationRule(ValidationRuleView view) {
        return new Change(Change.Kind.CREATE_VALIDATION_RULE, view.objectApiName(), null, write(
                new CreateValidationRuleRequest(withoutSuffix(view.apiName()), view.label(), view.description(),
                        view.expression(), view.errorMessage(), view.errorField(), view.active())));
    }

    Change restoreValidationRule(ValidationRuleView before, long afterVersion) {
        return new Change(Change.Kind.UPDATE_VALIDATION_RULE, before.objectApiName(), before.apiName(), write(
                new UpdateValidationRuleRequest(before.label(), before.description(), before.expression(),
                        before.errorMessage(), before.errorField(), before.active(), afterVersion)));
    }

    private static List<String> fieldsToSend(RecordTypeView view) {
        return view.allFields() ? null : view.availableFields();
    }

    /** The API name of something made again from a stored request, whose name never carries the suffix. */
    private static String withSuffix(String name) {
        return name.endsWith(NameRules.CUSTOM_SUFFIX) ? name : name + NameRules.CUSTOM_SUFFIX;
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
