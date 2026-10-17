package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platformapi.ApiException;
import app.platformapi.ApplicationView;
import app.platformapi.CreateApplicationRequest;
import app.platformapi.CreateCustomPageRequest;
import app.platformapi.CreateTabRequest;
import app.platformapi.CustomPageView;
import app.platformapi.TabView;
import app.platformapi.UpdateCustomPageRequest;
import app.platformapi.UpdateTabRequest;
import app.platformapi.CreateFieldRequest;
import app.platformapi.CreateLayoutAssignmentRequest;
import app.platformapi.CreateLayoutRequest;
import app.platformapi.CreateObjectRequest;
import app.platformapi.CreateRecordPageAssignmentRequest;
import app.platformapi.CreateRecordPageRequest;
import app.platformapi.CreateRecordTypeAssignmentRequest;
import app.platformapi.CreateRecordTypeRequest;
import app.platformapi.CreateValidationRuleRequest;
import app.platformapi.CreateWorkflowRequest;
import app.platformapi.UpdateWorkflowRequest;
import app.platformapi.WorkflowView;
import app.platformapi.FieldView;
import app.platformapi.LayoutAssignmentView;
import app.platformapi.LayoutView;
import app.platformapi.ObjectView;
import app.platformapi.RecordPageAssignmentView;
import app.platformapi.RecordPageView;
import app.platformapi.RecordTypeAssignmentView;
import app.platformapi.RecordTypeView;
import app.platformapi.ReleaseItemView;
import app.platformapi.UpdateApplicationRequest;
import app.platformapi.UpdateFieldRequest;
import app.platformapi.UpdateLayoutAssignmentRequest;
import app.platformapi.UpdateLayoutRequest;
import app.platformapi.UpdateObjectRequest;
import app.platformapi.UpdateRecordPageAssignmentRequest;
import app.platformapi.UpdateRecordPageRequest;
import app.platformapi.UpdateRecordTypeAssignmentRequest;
import app.platformapi.UpdateRecordTypeRequest;
import app.platformapi.UpdateValidationRuleRequest;
import app.platformapi.ValidationRuleView;
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
     *        {@code RecordTypeView}, {@code LayoutView}, {@code RecordPageView}, {@code ApplicationView},
     *        {@code LayoutAssignmentView}, {@code RecordPageAssignmentView}, {@code ValidationRuleView}, or
     *        {@code Boolean.TRUE} for a removal)
     * @param undo the changes that put things back, to be applied in this order; empty when nothing changed
     * @param items what was added, changed or removed
     */
    record Applied(Object result, List<Change> undo, List<ReleaseItemView> items) {
    }

    private final MetadataService metadata;
    private final RecordTypeService recordTypes;
    private final DefinitionService definitions;
    private final AssignmentService assignments;
    private final RecordPageAssignmentService recordPageAssignments;
    private final RecordTypeAssignmentService typeAssignments;
    private final ValidationRuleService rules;
    private final WorkflowService workflows;
    private final ChangeCodec codec;

    ChangeApplier(MetadataService metadata, RecordTypeService recordTypes, DefinitionService definitions,
            AssignmentService assignments, RecordPageAssignmentService recordPageAssignments,
            ValidationRuleService rules, RecordTypeAssignmentService typeAssignments, WorkflowService workflows,
            ChangeCodec codec) {
        this.workflows = workflows;
        this.rules = rules;
        this.metadata = metadata;
        this.recordTypes = recordTypes;
        this.definitions = definitions;
        this.assignments = assignments;
        this.recordPageAssignments = recordPageAssignments;
        this.typeAssignments = typeAssignments;
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
            case CREATE_LAYOUT -> createLayout(caller, change);
            case UPDATE_LAYOUT -> updateLayout(caller, change);
            case DELETE_LAYOUT -> deleteLayout(caller, change);
            case CREATE_RECORD_PAGE -> createRecordPage(caller, change);
            case UPDATE_RECORD_PAGE -> updateRecordPage(caller, change);
            case DELETE_RECORD_PAGE -> deleteRecordPage(caller, change);
            case CREATE_APPLICATION -> createApplication(caller, change);
            case UPDATE_APPLICATION -> updateApplication(caller, change);
            case DELETE_APPLICATION -> deleteApplication(caller, change);
            case CREATE_LAYOUT_ASSIGNMENT -> createAssignment(caller, change);
            case UPDATE_LAYOUT_ASSIGNMENT -> updateAssignment(caller, change);
            case DELETE_LAYOUT_ASSIGNMENT -> deleteAssignment(caller, change);
            case CREATE_VALIDATION_RULE -> createRule(caller, change);
            case UPDATE_VALIDATION_RULE -> updateRule(caller, change);
            case DELETE_VALIDATION_RULE -> deleteRule(caller, change);
            case CREATE_RECORD_TYPE_ASSIGNMENT -> createTypeAssignment(caller, change);
            case UPDATE_RECORD_TYPE_ASSIGNMENT -> updateTypeAssignment(caller, change);
            case DELETE_RECORD_TYPE_ASSIGNMENT -> deleteTypeAssignment(caller, change);
            case CREATE_TAB -> createTab(caller, change);
            case UPDATE_TAB -> updateTab(caller, change);
            case DELETE_TAB -> deleteTab(caller, change);
            case CREATE_CUSTOM_PAGE -> createCustomPage(caller, change);
            case UPDATE_CUSTOM_PAGE -> updateCustomPage(caller, change);
            case DELETE_CUSTOM_PAGE -> deleteCustomPage(caller, change);
            case CREATE_RECORD_PAGE_ASSIGNMENT -> createRecordPageAssignment(caller, change);
            case UPDATE_RECORD_PAGE_ASSIGNMENT -> updateRecordPageAssignment(caller, change);
            case DELETE_RECORD_PAGE_ASSIGNMENT -> deleteRecordPageAssignment(caller, change);
            case CREATE_WORKFLOW -> createWorkflow(caller, change);
            case UPDATE_WORKFLOW -> updateWorkflow(caller, change);
            case DELETE_WORKFLOW -> deleteWorkflow(caller, change);
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

    // ---- layouts ----

    private Applied createLayout(Caller caller, Change change) {
        LayoutView made = definitions.createLayout(caller, change.object(), codec.read(change,
                CreateLayoutRequest.class));
        return new Applied(made, List.of(codec.removeLayout(change.object(), made.apiName())),
                List.of(item("ADDED", "LAYOUT", change.object(), made.apiName())));
    }

    private Applied updateLayout(Caller caller, Change change) {
        LayoutView before = definitions.layout(change.object(), change.item());
        LayoutView after = definitions.updateLayout(caller, change.object(), change.item(),
                codec.read(change, UpdateLayoutRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreLayout(before, after.version())),
                List.of(item("CHANGED", "LAYOUT", change.object(), after.apiName())));
    }

    private Applied deleteLayout(Caller caller, Change change) {
        LayoutView before = definitions.layout(change.object(), change.item());
        definitions.deleteLayout(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeLayout(before)),
                List.of(item("REMOVED", "LAYOUT", change.object(), change.item())));
    }

    // ---- record pages ----

    private Applied createRecordPage(Caller caller, Change change) {
        RecordPageView made = definitions.createRecordPage(caller, change.object(), codec.read(change,
                CreateRecordPageRequest.class));
        return new Applied(made, List.of(codec.removeRecordPage(change.object(), made.apiName())),
                List.of(item("ADDED", "RECORD_PAGE", change.object(), made.apiName())));
    }

    private Applied updateRecordPage(Caller caller, Change change) {
        RecordPageView before = definitions.recordPage(change.object(), change.item());
        RecordPageView after = definitions.updateRecordPage(caller, change.object(), change.item(),
                codec.read(change, UpdateRecordPageRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreRecordPage(before, after.version())),
                List.of(item("CHANGED", "RECORD_PAGE", change.object(), after.apiName())));
    }

    private Applied deleteRecordPage(Caller caller, Change change) {
        RecordPageView before = definitions.recordPage(change.object(), change.item());
        definitions.deleteRecordPage(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeRecordPage(before)),
                List.of(item("REMOVED", "RECORD_PAGE", change.object(), change.item())));
    }

    // ---- applications ----

    private Applied createApplication(Caller caller, Change change) {
        ApplicationView made = definitions.createApplication(caller, codec.read(change,
                CreateApplicationRequest.class));
        return new Applied(made, List.of(codec.removeApplication(made.apiName())),
                List.of(item("ADDED", "APPLICATION", "", made.apiName())));
    }

    private Applied updateApplication(Caller caller, Change change) {
        ApplicationView before = definitions.application(change.item());
        ApplicationView after = definitions.updateApplication(caller, change.item(),
                codec.read(change, UpdateApplicationRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreApplication(before, after.version())),
                List.of(item("CHANGED", "APPLICATION", "", after.apiName())));
    }

    private Applied deleteApplication(Caller caller, Change change) {
        ApplicationView before = definitions.application(change.item());
        definitions.deleteApplication(caller, change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeApplication(before)),
                List.of(item("REMOVED", "APPLICATION", "", change.item())));
    }

    // ---- tabs and custom pages (Sprint 18) ----

    private Applied createTab(Caller caller, Change change) {
        TabView made = definitions.createTab(caller, codec.read(change, CreateTabRequest.class));
        return new Applied(made, List.of(codec.removeTab(made.apiName())),
                List.of(item("ADDED", "TAB", "", made.apiName())));
    }

    private Applied updateTab(Caller caller, Change change) {
        TabView before = definitions.tab(change.item());
        TabView after = definitions.updateTab(caller, change.item(), codec.read(change, UpdateTabRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreTab(before, after.version())),
                List.of(item("CHANGED", "TAB", "", after.apiName())));
    }

    private Applied deleteTab(Caller caller, Change change) {
        TabView before = definitions.tab(change.item());
        definitions.deleteTab(caller, change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeTab(before)),
                List.of(item("REMOVED", "TAB", "", change.item())));
    }

    private Applied createCustomPage(Caller caller, Change change) {
        CustomPageView made = definitions.createCustomPage(caller, codec.read(change, CreateCustomPageRequest.class));
        return new Applied(made, List.of(codec.removeCustomPage(made.apiName())),
                List.of(item("ADDED", "CUSTOM_PAGE", "", made.apiName())));
    }

    private Applied updateCustomPage(Caller caller, Change change) {
        CustomPageView before = definitions.customPage(change.item());
        CustomPageView after = definitions.updateCustomPage(caller, change.item(),
                codec.read(change, UpdateCustomPageRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreCustomPage(before, after.version())),
                List.of(item("CHANGED", "CUSTOM_PAGE", "", after.apiName())));
    }

    private Applied deleteCustomPage(Caller caller, Change change) {
        CustomPageView before = definitions.customPage(change.item());
        definitions.deleteCustomPage(caller, change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeCustomPage(before)),
                List.of(item("REMOVED", "CUSTOM_PAGE", "", change.item())));
    }

    // ---- layout assignments ----

    private Applied createAssignment(Caller caller, Change change) {
        LayoutAssignmentView made = assignments.create(caller, change.object(), codec.read(change,
                CreateLayoutAssignmentRequest.class));
        return new Applied(made, List.of(codec.removeAssignment(change.object(), made.key())),
                List.of(item("ADDED", "LAYOUT_ASSIGNMENT", change.object(), made.key())));
    }

    private Applied updateAssignment(Caller caller, Change change) {
        LayoutAssignmentView before = assignments.get(change.object(), change.item());
        LayoutAssignmentView after = assignments.update(caller, change.object(), change.item(),
                codec.read(change, UpdateLayoutAssignmentRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreAssignment(change.object(), before, after.version())),
                List.of(item("CHANGED", "LAYOUT_ASSIGNMENT", change.object(), after.key())));
    }

    private Applied deleteAssignment(Caller caller, Change change) {
        LayoutAssignmentView before = assignments.get(change.object(), change.item());
        assignments.delete(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeAssignment(change.object(), before)),
                List.of(item("REMOVED", "LAYOUT_ASSIGNMENT", change.object(), change.item())));
    }

    // ---- record page assignments (Sprint 20) ----

    private Applied createRecordPageAssignment(Caller caller, Change change) {
        RecordPageAssignmentView made = recordPageAssignments.create(caller, change.object(), codec.read(change,
                CreateRecordPageAssignmentRequest.class));
        return new Applied(made, List.of(codec.removeRecordPageAssignment(change.object(), made.key())),
                List.of(item("ADDED", "RECORD_PAGE_ASSIGNMENT", change.object(), made.key())));
    }

    private Applied updateRecordPageAssignment(Caller caller, Change change) {
        RecordPageAssignmentView before = recordPageAssignments.get(change.object(), change.item());
        RecordPageAssignmentView after = recordPageAssignments.update(caller, change.object(), change.item(),
                codec.read(change, UpdateRecordPageAssignmentRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreRecordPageAssignment(change.object(), before,
                after.version())), List.of(item("CHANGED", "RECORD_PAGE_ASSIGNMENT", change.object(),
                after.key())));
    }

    private Applied deleteRecordPageAssignment(Caller caller, Change change) {
        RecordPageAssignmentView before = recordPageAssignments.get(change.object(), change.item());
        recordPageAssignments.delete(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeRecordPageAssignment(change.object(), before)),
                List.of(item("REMOVED", "RECORD_PAGE_ASSIGNMENT", change.object(), change.item())));
    }

    // ---- record type assignments (Sprint 15A) ----

    private Applied createTypeAssignment(Caller caller, Change change) {
        CreateRecordTypeAssignmentRequest request = codec.read(change, CreateRecordTypeAssignmentRequest.class);
        RecordTypeAssignmentView previousDefault = Boolean.TRUE.equals(request.defaultForProfile())
                ? typeAssignments.defaultOf(change.object(), request.profileId(), request.recordType().strip())
                        .orElse(null) : null;
        RecordTypeAssignmentView made = typeAssignments.create(caller, change.object(), request);
        List<Change> undo = new ArrayList<>();
        undo.add(codec.removeTypeAssignment(change.object(), made.key()));
        giveTypeDefaultBack(undo, change.object(), previousDefault);
        return new Applied(made, undo,
                List.of(item("ADDED", "RECORD_TYPE_ASSIGNMENT", change.object(), made.key())));
    }

    private Applied updateTypeAssignment(Caller caller, Change change) {
        RecordTypeAssignmentView before = typeAssignments.get(change.object(), change.item());
        UpdateRecordTypeAssignmentRequest request = codec.read(change, UpdateRecordTypeAssignmentRequest.class);
        RecordTypeAssignmentView previousDefault = Boolean.TRUE.equals(request.defaultForProfile())
                ? typeAssignments.defaultOf(change.object(), before.profileId(), before.recordType()).orElse(null)
                : null;
        RecordTypeAssignmentView after = typeAssignments.update(caller, change.object(), change.item(), request);
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        List<Change> undo = new ArrayList<>();
        undo.add(codec.restoreTypeAssignment(change.object(), before, after.version()));
        giveTypeDefaultBack(undo, change.object(), previousDefault);
        return new Applied(after, undo,
                List.of(item("CHANGED", "RECORD_TYPE_ASSIGNMENT", change.object(), after.key())));
    }

    private Applied deleteTypeAssignment(Caller caller, Change change) {
        RecordTypeAssignmentView before = typeAssignments.get(change.object(), change.item());
        typeAssignments.delete(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeTypeAssignment(change.object(), before)),
                List.of(item("REMOVED", "RECORD_TYPE_ASSIGNMENT", change.object(), change.item())));
    }

    /**
     * Making an assignment the default of its profile takes the mark off the profile's old default; undoing it must
     * put the mark back, so the old default is restored after the changed one is.
     */
    private void giveTypeDefaultBack(List<Change> undo, String object, RecordTypeAssignmentView previousDefault) {
        if (previousDefault != null) {
            RecordTypeAssignmentView now = typeAssignments.get(object, previousDefault.key());
            undo.add(codec.restoreTypeAssignment(object, previousDefault, now.version()));
        }
    }

    // ---- validation rules ----

    private Applied createRule(Caller caller, Change change) {
        ValidationRuleView made = rules.create(caller, change.object(), codec.read(change,
                CreateValidationRuleRequest.class));
        return new Applied(made, List.of(codec.removeValidationRule(change.object(), made.apiName())),
                List.of(item("ADDED", "VALIDATION_RULE", change.object(), made.apiName())));
    }

    private Applied updateRule(Caller caller, Change change) {
        ValidationRuleView before = rules.rule(change.object(), change.item());
        ValidationRuleView after = rules.update(caller, change.object(), change.item(),
                codec.read(change, UpdateValidationRuleRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreValidationRule(before, after.version())),
                List.of(item("CHANGED", "VALIDATION_RULE", change.object(), after.apiName())));
    }

    private Applied deleteRule(Caller caller, Change change) {
        ValidationRuleView before = rules.rule(change.object(), change.item());
        rules.delete(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeValidationRule(before)),
                List.of(item("REMOVED", "VALIDATION_RULE", change.object(), change.item())));
    }

    // ---- workflows (Sprint 22) ----

    private Applied createWorkflow(Caller caller, Change change) {
        WorkflowView made = workflows.create(caller, change.object(), codec.read(change, CreateWorkflowRequest.class));
        return new Applied(made, List.of(codec.removeWorkflow(change.object(), made.apiName())),
                List.of(item("ADDED", "WORKFLOW", change.object(), made.apiName())));
    }

    private Applied updateWorkflow(Caller caller, Change change) {
        WorkflowView before = workflows.workflow(change.object(), change.item());
        WorkflowView after = workflows.update(caller, change.object(), change.item(),
                codec.read(change, UpdateWorkflowRequest.class));
        if (after.version().equals(before.version())) {
            return new Applied(after, List.of(), List.of());
        }
        return new Applied(after, List.of(codec.restoreWorkflow(before, after.version())),
                List.of(item("CHANGED", "WORKFLOW", change.object(), after.apiName())));
    }

    private Applied deleteWorkflow(Caller caller, Change change) {
        WorkflowView before = workflows.workflow(change.object(), change.item());
        workflows.delete(caller, change.object(), change.item());
        return new Applied(Boolean.TRUE, List.of(codec.makeWorkflow(before)),
                List.of(item("REMOVED", "WORKFLOW", change.object(), change.item())));
    }

    private static ReleaseItemView item(String action, String kind, String object, String item) {
        return new ReleaseItemView(action, kind, object, item);
    }
}
