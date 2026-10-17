package app.platform.workflow.internal;

import app.platform.identity.OrganizationAdministration;
import app.platform.security.Ability;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.Cursors;
import app.platformapi.PageRequest;
import app.platformapi.Pagination;
import app.platformapi.ParkedEventView;
import app.platformapi.WorkflowBulkReplayRequest;
import app.platformapi.WorkflowBulkReplayView;
import app.platformapi.WorkflowDeadLetterSummaryView;
import app.platformapi.WorkflowExecutionView;
import app.platformapi.WorkflowReplayRequest;
import app.platformapi.WorkflowReplayResultView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The history of workflow executions and what a person does about the ones that need them (Sprint 22, ADR-0137; replay,
 * cancel and parked events Sprint 23, ADR-0141, ADR-0143). Answered only on an organization host; the organization is
 * the host's, never a parameter, and the database shows only its rows. Reading needs {@code workflows.view}, cancelling
 * {@code workflows.manage}, replaying and putting back parked events {@code workflows.replay}. The executions hold
 * facts and never a value of the record, the condition or anything a person typed.
 */
@RestController
@Tag(name = "Workflows")
class ExecutionController {

    private static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED",
            "SKIPPED", "DEAD_LETTERED");
    private static final String EXECUTIONS = ApiPaths.WORKFLOW_EXECUTIONS;

    private final ExecutionStore store;
    private final ExecutionActions actions;
    private final OrganizationAdministration administration;

    ExecutionController(ExecutionStore store, ExecutionActions actions, OrganizationAdministration administration) {
        this.store = store;
        this.actions = actions;
        this.administration = administration;
    }

    @GetMapping(EXECUTIONS)
    @Operation(
            operationId = "listWorkflowExecutions",
            summary = "The executions of workflows",
            description = "Newest first, one page at a time, with filters for the workflow, the object, the status, "
                    + "the trigger, the record and the time the execution was queued. dead=true lists the dead "
                    + "letters (the same as status=DEAD_LETTERED). For members who may view workflows. NOT_FOUND on "
                    + "the platform host, FORBIDDEN without the ability, VALIDATION_ERROR for a filter that is "
                    + "malformed.")
    ApiPageResponse<WorkflowExecutionView> list(@ParameterObject PageRequest page,
            @RequestParam(name = "workflow", required = false) String workflow,
            @RequestParam(name = "object", required = false) String object,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "trigger", required = false) String trigger,
            @RequestParam(name = "dead", required = false) Boolean dead,
            @RequestParam(name = "record", required = false) UUID record,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.validation("status", "Choose QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, SKIPPED "
                    + "or DEAD_LETTERED.");
        }
        if (Boolean.TRUE.equals(dead) && status != null && !"DEAD_LETTERED".equals(status)) {
            throw ApiException.validation("dead", "Either list the dead letters or choose another status, not both.");
        }
        String wanted = Boolean.TRUE.equals(dead) ? "DEAD_LETTERED" : status;
        ExecutionStore.After after = after(page.cursor());
        ExecutionStore.Filter filter = new ExecutionStore.Filter(blank(workflow), blank(object), wanted, record, from,
                to, blank(trigger));
        return administration.asAdministrator("workflows.executions.list", Ability.WORKFLOWS_VIEW, caller -> {
            List<WorkflowExecutionView> found = store.list(filter, after, page.limit());
            boolean more = found.size() > page.limit();
            List<WorkflowExecutionView> shown = more ? found.subList(0, page.limit()) : found;
            Pagination pagination = more
                    ? Pagination.more(page.limit(), cursor(shown.get(shown.size() - 1)))
                    : Pagination.last(page.limit());
            return ApiPageResponse.of(shown, pagination);
        });
    }

    @GetMapping(EXECUTIONS + "/dead-letters/summary")
    @Operation(
            operationId = "getWorkflowDeadLetterSummary",
            summary = "How many dead letters wait for a person",
            description = "Counted by workflow and stable reason, the largest group first. A dead letter that has been "
                    + "replayed is not counted. For members who may view workflows.")
    ApiResponse<WorkflowDeadLetterSummaryView> deadLetters() {
        return ApiResponse.of(administration.asAdministrator("workflows.executions.dead", Ability.WORKFLOWS_VIEW,
                caller -> store.deadLetters()));
    }

    @GetMapping(EXECUTIONS + "/{executionId}")
    @Operation(
            operationId = "getWorkflowExecution",
            summary = "One execution with its steps",
            description = "Each step shows its status, how many attempts ended, when the next one is due and the "
                    + "stable reason and failure type of the last failure; never a value. NOT_FOUND for an "
                    + "execution the organization does not have.")
    ApiResponse<WorkflowExecutionView> get(@PathVariable UUID executionId) {
        return ApiResponse.of(administration.asAdministrator("workflows.executions.get", Ability.WORKFLOWS_VIEW,
                caller -> store.get(executionId).orElseThrow(
                        () -> ApiException.notFound("This execution does not exist."))));
    }

    @PostMapping(EXECUTIONS + "/{executionId}/replay")
    @Operation(
            operationId = "replayWorkflowExecution",
            summary = "Run a dead letter again",
            description = "Makes a new execution that resumes at the step that failed, or a later one named in "
                    + "fromStep (a step that completed is never run again), against the record as it is now. The "
                    + "condition is not judged again. Replaying twice, or at the same moment, makes one new "
                    + "execution: the answer is then ALREADY_REPLAYED with the one that exists. Only a dead letter "
                    + "is replayed "
                    + "(NOT_REPLAYABLE). A switched-off or removed workflow, a missing plan feature and a record that "
                    + "is gone end the replay as for any execution. Needs workflows.replay. NOT_FOUND for an "
                    + "execution the organization does not have.")
    ApiResponse<WorkflowReplayResultView> replay(@PathVariable UUID executionId,
            @Valid @RequestBody(required = false) WorkflowReplayRequest body) {
        Integer fromStep = body == null ? null : body.fromStep();
        return ApiResponse.of(administration.asAdministrator("workflows.executions.replay",
                Ability.WORKFLOWS_REPLAY, caller -> actions.replay(caller, executionId, fromStep)));
    }

    @PostMapping(EXECUTIONS + "/replay")
    @Operation(
            operationId = "replayWorkflowExecutions",
            summary = "Run several dead letters again",
            description = "At most 200. One result per execution, each in a savepoint of its own: one that cannot be "
                    + "replayed does not stop the others, and there is no all-or-nothing form. Needs "
                    + "workflows.replay.")
    ApiResponse<WorkflowBulkReplayView> replayMany(@Valid @RequestBody WorkflowBulkReplayRequest body) {
        return ApiResponse.of(administration.asAdministrator("workflows.executions.replay_many",
                Ability.WORKFLOWS_REPLAY, caller -> actions.replayAll(caller, body.executions())));
    }

    @PostMapping(EXECUTIONS + "/{executionId}/cancel")
    @Operation(
            operationId = "cancelWorkflowExecution",
            summary = "Cancel an execution that has not started",
            description = "For an execution that is queued, including one that waits for its next attempt. CONFLICT "
                    + "when it is running or finished. Needs workflows.manage.")
    ApiResponse<WorkflowExecutionView> cancel(@PathVariable UUID executionId) {
        return ApiResponse.of(administration.asAdministrator("workflows.executions.cancel",
                Ability.WORKFLOWS_MANAGE, caller -> actions.cancel(caller, executionId)));
    }

    @GetMapping(ApiPaths.WORKFLOW_PARKED_EVENTS)
    @Operation(
            operationId = "listWorkflowParkedEvents",
            summary = "Events the platform gave up on that workflows depend on",
            description = "The record events and releases whose handlers kept failing and were set aside (at most 200, "
                    + "newest first). Never the payload. For members who may view workflows.")
    ApiResponse<List<ParkedEventView>> parked() {
        return ApiResponse.of(administration.asAdministrator("workflows.parked.list", Ability.WORKFLOWS_VIEW,
                caller -> actions.parkedEvents()));
    }

    @PostMapping(ApiPaths.WORKFLOW_PARKED_EVENTS + "/{eventId}/redrive")
    @Operation(
            operationId = "redriveWorkflowParkedEvent",
            summary = "Put a parked event back",
            description = "The event is delivered again as if it were new. Handlers that already handled it skip "
                    + "it, so nothing is done twice; one that did not handles it now (a release whose schedules "
                    + "were never made makes them). Redriving twice changes nothing the second time (NOT_FOUND). Needs "
                    + "workflows.replay.")
    ResponseEntity<Void> redrive(@PathVariable UUID eventId) {
        administration.asAdministrator("workflows.parked.redrive", Ability.WORKFLOWS_REPLAY, caller -> {
            actions.redrive(caller, eventId);
            return Boolean.TRUE;
        });
        return ResponseEntity.noContent().build();
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String cursor(WorkflowExecutionView last) {
        return Cursors.encode(last.queuedAt() + "|" + last.id());
    }

    /** The position a cursor stands for; a text that is not a cursor this class made is the caller's mistake. */
    private static ExecutionStore.After after(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String position = Cursors.decode(cursor).orElseThrow(IllegalArgumentException::new);
            int bar = position.indexOf('|');
            return new ExecutionStore.After(Instant.parse(position.substring(0, bar)),
                    UUID.fromString(position.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException | StringIndexOutOfBoundsException e) {
            throw ApiException.validation("cursor", "Is not a cursor of this list.");
        }
    }
}
