package app.platform.inspection.internal;

import app.platform.inspection.internal.ChangeRequestViews.AnswerChangeRequest;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestView;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestsView;
import app.platform.inspection.internal.ChangeRequestViews.CreateChangeRequest;
import app.platform.inspection.internal.ChangeRequestViews.NewChangeComment;
import app.platform.inspection.internal.ChangeRequestViews.TurnIntoVersionRequest;
import app.platform.inspection.internal.ChangeRequestViews.TurnedView;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of the change requests on a template (FA5, ADR-0191). Thin: the rules are in {@link ChangeRequests}.
 * Answered only on an organization host, for a signed-in member who can read the template; the address names the
 * template, never the organization. A request changes nothing of the template or its versions.
 */
@RestController
@Tag(name = "Template change requests")
class ChangeRequestController {

    private static final String BASE = ApiPaths.V1 + "/templates/{templateId}/change-requests";
    private static final String SEES = "NOT_FOUND for a template the caller cannot read, for one that does not exist "
            + "and on the platform host (all alike), and for a request the caller may not see. A person sees their "
            + "own requests; a person who may change the template sees all of them. ";

    private final ChangeRequests requests;

    ChangeRequestController(ChangeRequests requests) {
        this.requests = requests;
    }

    @GetMapping(BASE)
    @Operation(
            operationId = "listChangeRequests",
            summary = "The change requests of a template that the caller may see",
            description = "Each request with its comments, newest first. canReview says whether the caller may answer "
                    + "requests and canRequest whether the screen may offer to ask for a change (a version is in use "
                    + "and the template is not retired); the flags only hide buttons. status narrows the list "
                    + "(OPEN, ACCEPTED, DECLINED or DONE). A comment says whether its place is still in the version "
                    + "in use. " + SEES)
    ApiResponse<ChangeRequestsView> list(@PathVariable UUID templateId, @RequestParam(required = false) String status) {
        return ApiResponse.of(requests.list(templateId, status));
    }

    @GetMapping(BASE + "/{requestId}")
    @Operation(
            operationId = "getChangeRequest",
            summary = "One change request with its comments",
            description = SEES)
    ApiResponse<ChangeRequestView> get(@PathVariable UUID templateId, @PathVariable UUID requestId) {
        return ApiResponse.of(requests.get(templateId, requestId));
    }

    @PostMapping(BASE)
    @Operation(
            operationId = "createChangeRequest",
            summary = "Ask for a change to a version of a template",
            description = "Needs only the right to read the template. The version must be one that is or was in use; "
                    + "each comment may name a row, a column, both (a cell) or neither (the template as a whole), "
                    + "and a row or column must be in that version. Nothing of the template changes. VALIDATION_ERROR "
                    + "names the place that is wrong. CONFLICT for a retired template, when no version is in use, "
                    + "and when a limit is reached (open requests per person, requests per template). " + SEES)
    ApiResponse<ChangeRequestView> create(@PathVariable UUID templateId, @Valid @RequestBody CreateChangeRequest body) {
        return ApiResponse.of(requests.create(templateId, body));
    }

    @PostMapping(BASE + "/{requestId}/comments")
    @Operation(
            operationId = "addChangeComment",
            summary = "Add a comment to a change request",
            description = "By the person who asked or by a person who may change the template, while the request is "
                    + "open or accepted. The place is checked against the version the request read. CONFLICT when "
                    + "the request is closed or has the most comments. " + SEES)
    ApiResponse<ChangeRequestView> comment(@PathVariable UUID templateId, @PathVariable UUID requestId,
            @Valid @RequestBody NewChangeComment body) {
        return ApiResponse.of(requests.comment(templateId, requestId, body));
    }

    @PostMapping(BASE + "/{requestId}/answer")
    @Operation(
            operationId = "answerChangeRequest",
            summary = "Accept, decline or reopen a change request",
            description = "Needs the right to change the template (FORBIDDEN otherwise). ACCEPT moves an open request "
                    + "to accepted; DECLINE needs a reason and ends an open or accepted one; REOPEN puts a done "
                    + "request back to open, only when the version it became was rejected or withdrawn. CONFLICT "
                    + "when it is not in a status that allows the move, or somebody answered first. " + SEES)
    ApiResponse<ChangeRequestView> answer(@PathVariable UUID templateId, @PathVariable UUID requestId,
            @Valid @RequestBody AnswerChangeRequest body) {
        return ApiResponse.of(requests.answer(templateId, requestId, body));
    }

    @PostMapping(BASE + "/turn-into-version")
    @Operation(
            operationId = "turnChangeRequestsIntoVersion",
            summary = "Turn change requests into the next version of the template",
            description = "Needs the right to change the template (FORBIDDEN otherwise). Makes a draft that is a copy "
                    + "of the version in use now (not of the version each person read) and links the requests to it; "
                    + "they become done. When a draft already exists nothing is copied and the requests are linked to "
                    + "that draft (created is false). The draft goes through approval like any other. CONFLICT for "
                    + "a retired template, when no version is in use, and for a request that is already answered or "
                    + "turned (the second of two callers at once gets this). " + SEES)
    ApiResponse<TurnedView> turn(@PathVariable UUID templateId, @Valid @RequestBody TurnIntoVersionRequest body) {
        return ApiResponse.of(requests.turn(templateId, body));
    }
}
