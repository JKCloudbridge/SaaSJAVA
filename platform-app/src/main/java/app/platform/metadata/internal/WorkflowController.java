package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CheckWorkflowConditionRequest;
import app.platformapi.CreateWorkflowRequest;
import app.platformapi.UpdateWorkflowRequest;
import app.platformapi.WorkflowActivationRequest;
import app.platformapi.WorkflowCatalogueView;
import app.platformapi.WorkflowConditionCheckView;
import app.platformapi.WorkflowView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workflows of an object (Sprint 22, ADR-0136). Thin: the rules are in {@link WorkflowService}. Answered only on an
 * organization host; reading needs {@code workflows.view}; a change is made live at once, which is a publication of one
 * change, so it needs {@code workflows.manage} and {@code metadata.publish} (ADR-0068); trying a condition needs
 * {@code workflows.manage}. Several changes that belong together go through a change set instead, where the same
 * abilities are asked when the set is checked or published.
 */
@RestController
@Tag(name = "Workflows")
class WorkflowController {

    private static final List<Ability> LIVE = List.of(Ability.WORKFLOWS_MANAGE, Ability.METADATA_PUBLISH);
    private static final String WORKFLOWS = ApiPaths.METADATA_WORKFLOWS;
    private static final String DEFINITION_NOTE = "The trigger, the condition and the actions are closed data: the "
            + "condition is in the expression language (true or false, it may use TODAY() and NOW()), and the actions "
            + "are of the types of this release. The request is checked here (a VALIDATION_ERROR names every place); "
            + "whether the fields it names exist is checked after the change and a CONFLICT names what is wrong. "
            + "Executions run after the commit of the record change, never inside it.";

    private final MetadataAdministration administration;
    private final WorkflowService service;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    WorkflowController(MetadataAdministration administration, WorkflowService service, MetadataLifecycle lifecycle,
            ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    @GetMapping(WORKFLOWS)
    @Operation(
            operationId = "listWorkflows",
            summary = "The workflows of the organization",
            description = "For members who may view workflows. Name an object to list only its workflows. NOT_FOUND "
                    + "for an object the organization does not have.")
    ApiResponse<List<WorkflowView>> list(@RequestParam(name = "object", required = false) String objectApiName) {
        return ApiResponse.of(administration.run("metadata.workflow.list", Ability.WORKFLOWS_VIEW,
                caller -> service.workflows(objectApiName)));
    }

    @GetMapping(ApiPaths.METADATA_WORKFLOW_CATALOGUE)
    @Operation(
            operationId = "getWorkflowCatalogue",
            summary = "What a workflow can be made of",
            description = "The types of action with the settings each takes, the kinds of trigger with the properties "
                    + "each uses, and the limits the platform enforces. The screens build their palette and forms "
                    + "from this and hold no list of their own. For members who may view workflows.")
    ApiResponse<WorkflowCatalogueView> catalogue() {
        return ApiResponse.of(administration.run("metadata.workflow.catalogue", Ability.WORKFLOWS_VIEW,
                caller -> service.catalogue()));
    }

    @GetMapping(WORKFLOWS + "/{objectApiName}/{workflowApiName}")
    @Operation(
            operationId = "getWorkflow",
            summary = "One workflow",
            description = "NOT_FOUND for a workflow the object does not have.")
    ApiResponse<WorkflowView> get(@PathVariable String objectApiName, @PathVariable String workflowApiName) {
        return ApiResponse.of(administration.run("metadata.workflow.get", Ability.WORKFLOWS_VIEW,
                caller -> service.workflow(objectApiName, workflowApiName)));
    }

    @PostMapping(WORKFLOWS + "/{objectApiName}")
    @Operation(
            operationId = "createWorkflow",
            summary = "Add a workflow to an object",
            description = "Live at once, as a release of one change. The name is turned into the permanent API name "
                    + "with the ending __c. " + DEFINITION_NOTE + " CONFLICT for an object whose records belong to "
                    + "the platform, at the limit, when the plan has no workflows, or when the result would break a "
                    + "dependency. Audited by name and counts, never by the text.")
    ResponseEntity<ApiResponse<WorkflowView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateWorkflowRequest body) {
        WorkflowView created = administration.runAll("metadata.workflow.create", LIVE,
                caller -> lifecycle.applyNow(caller, codec.now(Change.Kind.CREATE_WORKFLOW, objectApiName, null, body),
                        WorkflowView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(WORKFLOWS + "/{objectApiName}/{workflowApiName}")
    @Operation(
            operationId = "updateWorkflow",
            summary = "Change a workflow",
            description = "Live at once, as a release of one change. The API name and the object never change. A "
                    + "change of the trigger, the condition or the actions makes a new version of the definition; "
                    + "executions already queued keep the version they were created under. " + DEFINITION_NOTE
                    + " CONCURRENT_MODIFICATION when someone changed it since it was read.")
    ApiResponse<WorkflowView> update(@PathVariable String objectApiName, @PathVariable String workflowApiName,
            @Valid @RequestBody UpdateWorkflowRequest body) {
        return ApiResponse.of(administration.runAll("metadata.workflow.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_WORKFLOW, objectApiName, workflowApiName, body),
                        WorkflowView.class)));
    }

    @PostMapping(WORKFLOWS + "/{objectApiName}/{workflowApiName}/activation")
    @Operation(
            operationId = "activateWorkflow",
            summary = "Switch a workflow on or off",
            description = "Live at once, as a release of one change, and without a new version of the definition. A "
                    + "workflow that is switched off starts no new execution and also stops the queued ones "
                    + "(they end as SKIPPED with the reason workflow_inactive). CONFLICT when the plan has no "
                    + "workflows and the workflow is being switched on. CONCURRENT_MODIFICATION when someone changed "
                    + "it since it was read.")
    ApiResponse<WorkflowView> activate(@PathVariable String objectApiName, @PathVariable String workflowApiName,
            @Valid @RequestBody WorkflowActivationRequest body) {
        return ApiResponse.of(administration.runAll("metadata.workflow.activate", LIVE, caller -> {
            WorkflowView current = service.workflow(objectApiName, workflowApiName);
            UpdateWorkflowRequest same = new UpdateWorkflowRequest(current.label(), current.description(),
                    body.active(), current.trigger(), current.condition(), current.actions(), body.version());
            return lifecycle.applyNow(caller, codec.now(Change.Kind.UPDATE_WORKFLOW, objectApiName, workflowApiName,
                    same), WorkflowView.class);
        }));
    }

    @DeleteMapping(WORKFLOWS + "/{objectApiName}/{workflowApiName}")
    @Operation(
            operationId = "deleteWorkflow",
            summary = "Remove a workflow",
            description = "Live at once, as a release of one change. Its queued executions end as CANCELLED with the "
                    + "reason workflow_removed; finished ones stay in the history until the clean-up. The undo of the "
                    + "release makes it again, as a new workflow.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable String workflowApiName) {
        administration.runAll("metadata.workflow.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_WORKFLOW, objectApiName, workflowApiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(WORKFLOWS + "/{objectApiName}/check")
    @Operation(
            operationId = "checkWorkflowCondition",
            summary = "Try a condition on sample values, and the actions on the object",
            description = "Answers what would happen to a record with these values: RUNS (the condition is true, or "
                    + "there is none), SKIPPED (false or no value) or ERROR with the place when the evaluation "
                    + "itself fails. A condition that is not valid is a VALIDATION_ERROR naming the place. There are "
                    + "no records yet, so the values are typed as text for the type of each field. Name actions to "
                    + "learn, by place, which fields they name do not exist or cannot be set, which literals do not "
                    + "fit and which required field of a created record is missing. Nothing is stored and nothing "
                    + "is logged. Needs workflows.manage.")
    ApiResponse<WorkflowConditionCheckView> check(@PathVariable String objectApiName,
            @Valid @RequestBody CheckWorkflowConditionRequest body) {
        return ApiResponse.of(administration.run("metadata.workflow.check", Ability.WORKFLOWS_MANAGE,
                caller -> service.check(objectApiName, body)));
    }
}
