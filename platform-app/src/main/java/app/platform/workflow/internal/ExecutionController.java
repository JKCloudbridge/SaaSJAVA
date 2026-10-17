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
import app.platformapi.WorkflowExecutionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The history of workflow executions (Sprint 22, ADR-0137): what ran, for which record, under which version of which
 * workflow, with what outcome and for what reason. Answered only on an organization host and only for members who hold
 * {@code workflows.view}; the organization is the host's, never a parameter, and the database shows only its rows. The
 * executions hold facts and never a value of the record, the condition or anything a person typed.
 */
@RestController
@Tag(name = "Workflows")
class ExecutionController {

    private static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED",
            "SKIPPED");

    private final ExecutionStore store;
    private final OrganizationAdministration administration;

    ExecutionController(ExecutionStore store, OrganizationAdministration administration) {
        this.store = store;
        this.administration = administration;
    }

    @GetMapping(ApiPaths.WORKFLOW_EXECUTIONS)
    @Operation(
            operationId = "listWorkflowExecutions",
            summary = "The executions of workflows",
            description = "Newest first, one page at a time, with filters for the workflow, the object, the status, "
                    + "the record and the time the execution was queued. For members who may view workflows. "
                    + "NOT_FOUND on the platform host, FORBIDDEN without the ability, VALIDATION_ERROR for a filter "
                    + "that is malformed.")
    ApiPageResponse<WorkflowExecutionView> list(@ParameterObject PageRequest page,
            @RequestParam(name = "workflow", required = false) String workflow,
            @RequestParam(name = "object", required = false) String object,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "record", required = false) UUID record,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.validation("status", "Choose QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED or "
                    + "SKIPPED.");
        }
        ExecutionStore.After after = after(page.cursor());
        ExecutionStore.Filter filter = new ExecutionStore.Filter(blank(workflow), blank(object), status, record, from,
                to);
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

    @GetMapping(ApiPaths.WORKFLOW_EXECUTIONS + "/{executionId}")
    @Operation(
            operationId = "getWorkflowExecution",
            summary = "One execution with its steps",
            description = "NOT_FOUND for an execution the organization does not have.")
    ApiResponse<WorkflowExecutionView> get(@PathVariable UUID executionId) {
        return ApiResponse.of(administration.asAdministrator("workflows.executions.get", Ability.WORKFLOWS_VIEW,
                caller -> store.get(executionId).orElseThrow(
                        () -> ApiException.notFound("This execution does not exist."))));
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
