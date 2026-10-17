package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionException;
import app.platform.expression.Purpose;
import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.licensing.Entitlements;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platformapi.ApiException;
import app.platformapi.CheckWorkflowConditionRequest;
import app.platformapi.CreateWorkflowRequest;
import app.platformapi.ErrorCode;
import app.platformapi.UpdateWorkflowRequest;
import app.platformapi.WorkflowActionSpec;
import app.platformapi.WorkflowConditionCheckView;
import app.platformapi.WorkflowTriggerSpec;
import app.platformapi.WorkflowView;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The rules of workflows (ADR-0136). Like {@link ValidationRuleService} it runs inside the transaction that
 * {@link MetadataAdministration} opened, and it changes published definitions only. What it checks is what can be
 * checked of the request alone ({@link WorkflowRules}); whether the fields exist, whether the condition gives true or
 * false and whether the selection is valid is checked by the lifecycle after the change ({@link WorkflowDependencies}).
 *
 * <p><strong>Versions.</strong> A change of the trigger, the condition or the actions writes a new immutable version
 * and points the workflow at it; a change of the label, the description or only whether the workflow is active does
 * not. Executions already queued keep the version they were created under (ADR-0136).
 *
 * <p><strong>Who may.</strong> Whoever applies a workflow change needs {@code workflows.manage}, also when the change
 * comes out of a change set (which asks only for the metadata abilities); and a workflow may be switched on, or
 * changed while it is on, only when the organization's plan has the feature {@code workflows}. Switching off,
 * removing and changing a workflow that is off are never refused for the plan.
 */
@Service
class WorkflowService {

    /** The feature that gates workflows (the licensing catalogue of Sprint 6). */
    static final String FEATURE = "workflows";

    private final WorkflowStore store;
    private final WorkflowCodec codec;
    private final WorkflowRules rules;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final MetadataAudit audit;
    private final MetadataProperties properties;
    private final Permissions permissions;
    private final Entitlements entitlements;
    private final ExpressionSupport support;
    private final Clock clock;

    WorkflowService(WorkflowStore store, WorkflowCodec codec, WorkflowRules rules, MetadataCatalogue catalogue,
            MetadataVersions versions, MetadataAudit audit, MetadataProperties properties, Permissions permissions,
            Entitlements entitlements, ExpressionSupport support, Clock clock) {
        this.support = support;
        this.clock = clock;
        this.store = store;
        this.codec = codec;
        this.rules = rules;
        this.catalogue = catalogue;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
        this.permissions = permissions;
        this.entitlements = entitlements;
    }

    // ---- reading ----

    /** The workflows of one object, or of every object when none is named. */
    List<WorkflowView> workflows(String objectApiName) {
        if (objectApiName != null) {
            requireObject(objectApiName);
        }
        return store.all().stream().filter(row -> objectApiName == null || row.objectApiName().equals(objectApiName))
                .map(codec::view).toList();
    }

    WorkflowView workflow(String objectApiName, String apiName) {
        requireObject(objectApiName);
        return codec.view(store.find(objectApiName, apiName).orElseThrow(WorkflowService::missing));
    }

    // ---- trying a condition ----

    /**
     * What a condition would do for a record with the sample values. The real engine answers; nothing is stored,
     * audited or logged, and no part of the request is repeated in the answer.
     */
    WorkflowConditionCheckView check(String objectApiName, CheckWorkflowConditionRequest request) {
        ObjectDefinition object = requireObject(objectApiName);
        if (request.condition() == null || request.condition().isBlank()) {
            return new WorkflowConditionCheckView("RUNS", null);
        }
        Compiled compiled;
        try {
            compiled = support.engine().compile(request.condition().strip(), ExpressionSupport.schemaOf(object),
                    Purpose.WORKFLOW_CONDITION);
        } catch (ExpressionException problem) {
            throw ApiException.validation("condition", problem.getMessage());
        }
        Map<String, Object> values = support.valuesOf(object, request.values());
        try {
            return Boolean.TRUE.equals(support.engine().evaluate(compiled, values, clock.instant()))
                    ? new WorkflowConditionCheckView("RUNS", null)
                    : new WorkflowConditionCheckView("SKIPPED", null);
        } catch (ExpressionException problem) {
            return new WorkflowConditionCheckView("ERROR", ExpressionSupport.problem(problem));
        }
    }

    // ---- changing ----

    WorkflowView create(Caller caller, String objectApiName, CreateWorkflowRequest request) {
        requireManage(caller);
        eligible(objectApiName);
        Problems problems = new Problems();
        String apiName = problems.check(() -> NameRules.objectApiName(request.name()));
        WorkflowRules.Checked checked = rules.check(problems, request.label(), request.description(),
                request.trigger(), request.condition(), request.actions(), true);
        problems.throwIfAny();
        if (store.count(objectApiName) >= properties.limits().maxWorkflowsPerObject()) {
            throw new ApiException(ErrorCode.CONFLICT, "The object has reached the most workflows it may have ("
                    + properties.limits().maxWorkflowsPerObject() + ").");
        }
        if (store.nameTaken(objectApiName, apiName)) {
            throw ApiException.validation("name", "A workflow with this name exists already on this object.");
        }
        boolean active = request.active() == null || request.active();
        if (active) {
            requireFeature();
        }
        versions.wrote();
        WorkflowStore.Row row;
        try {
            row = store.insert(objectApiName, apiName, checked.label(), checked.description(), active,
                    codec.writeTrigger(checked.trigger()), checked.condition(), codec.writeActions(checked.actions()),
                    caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("name", "A workflow with this name exists already on this object.");
        }
        audit.workflowCreated(caller.userId(), objectApiName, apiName, active, checked.trigger().kind(),
                checked.actions().size());
        return codec.view(row);
    }

    WorkflowView update(Caller caller, String objectApiName, String apiName, UpdateWorkflowRequest request) {
        requireManage(caller);
        eligible(objectApiName);
        WorkflowStore.Row row = store.find(objectApiName, apiName).orElseThrow(WorkflowService::missing);
        WorkflowTriggerSpec oldTrigger = codec.readTrigger(row.trigger());
        List<WorkflowActionSpec> oldActions = codec.readActions(row.actions());
        Problems problems = new Problems();
        // A one-time moment must lie ahead only when it is being set; an unchanged one that has passed already ran.
        boolean momentChanged = request.trigger() != null && request.trigger().at() != null
                && !request.trigger().at().strip().equals(oldTrigger.at());
        WorkflowRules.Checked checked = rules.check(problems, request.label(), request.description(),
                request.trigger(), request.condition(), request.actions(), momentChanged);
        problems.throwIfAny();
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        boolean definitionChanged = !codec.writeTrigger(checked.trigger()).equals(codec.writeTrigger(oldTrigger))
                || !Objects.equals(checked.condition(), row.condition())
                || !codec.writeActions(checked.actions()).equals(codec.writeActions(oldActions));
        List<String> changed = new ArrayList<>();
        addIf(changed, "label", !checked.label().equals(row.label()));
        addIf(changed, "description", !checked.description().equals(row.description()));
        addIf(changed, "active", request.active() != row.active());
        addIf(changed, "definition", definitionChanged);
        if (changed.isEmpty()) {
            return codec.view(row);
        }
        if (request.active() && (!row.active() || definitionChanged)) {
            requireFeature();
        }
        int definitionVersion = row.definitionVersion();
        versions.wrote();
        if (definitionChanged) {
            definitionVersion++;
            if (definitionVersion > properties.limits().maxWorkflowVersions()) {
                throw new ApiException(ErrorCode.CONFLICT, "This workflow has been through the most definitions it "
                        + "may have (" + properties.limits().maxWorkflowVersions() + "). Make a new workflow "
                        + "instead.");
            }
            store.insertVersion(row.id(), definitionVersion, codec.writeTrigger(checked.trigger()),
                    checked.condition(), codec.writeActions(checked.actions()), caller.userId());
        }
        if (!store.update(row.id(), request.version(), checked.label(), checked.description(), request.active(),
                definitionVersion, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (changed.equals(List.of("active"))) {
            audit.workflowActivated(caller.userId(), objectApiName, apiName, request.active());
        } else {
            audit.workflowUpdated(caller.userId(), objectApiName, apiName, String.join(",", changed),
                    request.active(), definitionVersion);
        }
        return codec.view(store.find(objectApiName, apiName).orElseThrow(WorkflowService::missing));
    }

    void delete(Caller caller, String objectApiName, String apiName) {
        requireManage(caller);
        requireObject(objectApiName);
        WorkflowStore.Row row = store.find(objectApiName, apiName).orElseThrow(WorkflowService::missing);
        versions.wrote();
        store.delete(row.id(), caller.userId());
        audit.workflowDeleted(caller.userId(), objectApiName, apiName);
    }

    // ---- helpers ----

    private void requireManage(Caller caller) {
        if (!permissions.has(caller.membershipId(), Ability.WORKFLOWS_MANAGE)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireFeature() {
        if (!entitlements.enabled(FEATURE)) {
            throw new ApiException(ErrorCode.CONFLICT, "Workflows are not part of this organization's plan.");
        }
    }

    private ObjectDefinition requireObject(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private void eligible(String objectApiName) {
        ObjectDefinition object = requireObject(objectApiName);
        if (!RecordTypeRules.allowedOn(object)) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform, so it "
                    + "cannot have a workflow.");
        }
    }

    private static void addIf(List<String> changed, String what, boolean differs) {
        if (differs) {
            changed.add(what);
        }
    }

    private static ApiException missing() {
        return ApiException.notFound("This workflow does not exist.");
    }
}
