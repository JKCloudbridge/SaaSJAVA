package app.platform.inspection.internal;

import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestPage;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestQuery;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestView;
import app.platform.inspection.internal.ChangeRequestViews.ChangeRequestsView;
import app.platform.inspection.internal.ChangeRequestViews.CreateChangeRequest;
import app.platform.inspection.internal.ChangeRequestViews.NewChangeComment;
import app.platform.inspection.internal.RegisterViews.RegisterCompletion;
import app.platform.inspection.internal.RegisterViews.RegisterFileView;
import app.platform.inspection.internal.RegisterViews.RegisterInput;
import app.platform.inspection.internal.RegisterViews.RegisterView;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of the change requests on a template (FA5, ADR-0191; FA8, ADR-0202). Thin: the rules are in
 * {@link ChangeRequests} and {@link ChangeRequestRegisters}. Answered only on an organization host, for a signed-in
 * member who can read the template; the address names the template, never the organization. A request changes nothing
 * of the template or its versions until its last approval publishes it.
 */
@RestController
@Tag(name = "Template change requests")
class ChangeRequestController {

    private static final String BASE = ApiPaths.V1 + "/templates/{templateId}/change-requests";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** The most bytes of a filled register that are read; a real one is a few kilobytes. */
    private static final int MOST_FILE_BYTES = 1_000_000;
    private static final String SEES = "NOT_FOUND for a template the caller cannot read, for one that does not exist "
            + "and on the platform host (all alike), and for a request the caller may not see. A person sees their "
            + "own requests; a person who may change the template sees all of them. ";

    private static final String APPLICATION = "The application whose records are meant (its API name). It chooses "
            + "among the sets of records only and proves nothing: NOT_FOUND, in the same words, for an application "
            + "that does not exist, one the organization does not have and the platform host. What the caller may do "
            + "inside the set is decided by the same rights as ever.";

    private final ChangeRequests requests;
    private final ChangeRequestList list;
    private final ChangeRequestRegisters registers;
    private final RecordSets sets;

    ChangeRequestController(ChangeRequests requests, ChangeRequestList list, ChangeRequestRegisters registers,
            RecordSets sets) {
        this.requests = requests;
        this.list = list;
        this.registers = registers;
        this.sets = sets;
    }

    @PostMapping(ApiPaths.V1 + "/change-requests/query")
    @Operation(
            operationId = "queryChangeRequests",
            summary = "The change requests of every template that the caller may see",
            description = "One page for the tab of change requests: search (a text matched against the title and the "
                    + "name of the person who asked), an order (date, version or status, with date newest first by "
                    + "default), a narrowing to one template or status, and a cursor. A person sees their own "
                    + "requests, and every request of the templates they may change; they reach a template's "
                    + "requests only while they can read the template, and the platform host and another "
                    + "organization's requests are never in the answer. A page may be short and still have a cursor "
                    + "because requests the caller may not see are skipped; only a missing cursor is the end. A "
                    + "post, because the text does not belong in an address. Nothing typed is logged.")
    ApiResponse<ChangeRequestPage> query(@RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody ChangeRequestQuery body) {
        return ApiResponse.of(list.query(sets.require(application), body));
    }

    @GetMapping(ApiPaths.V1 + "/change-requests/{requestId}")
    @Operation(
            operationId = "getChangeRequestById",
            summary = "One change request by its identifier",
            description = "For a page that was given the request and not its template (the bell, the approval inbox). "
                    + "reviewId names the record whose approval panel decides it. " + SEES)
    ApiResponse<ChangeRequestView> getById(@PathVariable UUID requestId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(requests.getAnywhere(sets.require(application), requestId));
    }

    @GetMapping(ApiPaths.V1 + "/change-requests/by-review/{reviewId}")
    @Operation(
            operationId = "getChangeRequestByReview",
            summary = "One change request by the identifier of the record its approval reviews",
            description = "For the choice a step of the approval asks for, which knows the record under review and "
                    + "not the request or its template. " + SEES)
    ApiResponse<ChangeRequestView> getByReview(@PathVariable UUID reviewId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(requests.getByReview(sets.require(application), reviewId));
    }

    @GetMapping(BASE)
    @Operation(
            operationId = "listChangeRequests",
            summary = "The change requests of a template that the caller may see",
            description = "Each request with its comments, newest first. canReview says whether the caller may see all "
                    + "requests and canRequest whether the screen may offer to ask for a change (a revision is in "
                    + "use, the template is not retired and no other change is in progress); inProgress says that "
                    + "one is. The flags only hide buttons. status narrows the list (OPEN, DECLINED or DONE). A "
                    + "comment says whether its place is still in the revision in use. " + SEES)
    ApiResponse<ChangeRequestsView> list(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @RequestParam(required = false) String status) {
        return ApiResponse.of(requests.list(sets.require(application), templateId, status));
    }

    @GetMapping(BASE + "/{requestId}")
    @Operation(
            operationId = "getChangeRequest",
            summary = "One change request with its comments",
            description = SEES)
    ApiResponse<ChangeRequestView> get(@PathVariable UUID templateId, @PathVariable UUID requestId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(requests.get(sets.require(application), templateId, requestId));
    }

    @PostMapping(BASE)
    @Operation(
            operationId = "createChangeRequest",
            summary = "Ask for a change to a revision of a template",
            description = "Needs only the right to read the template. The body is the register form (the request "
                    + "number the person types, what is asked, the basis, the horizontal deployment and the format "
                    + "that stops being used), the revision that was read and, if the person likes, places on the "
                    + "sheet; each place may name a row, a column, both (a cell) or neither (the template as a whole), "
                    + "and a row or column must be in that revision. The platform fills the rest of the register "
                    + "(document, revision, who and when, department). Nothing of the template changes. "
                    + "VALIDATION_ERROR names the box that is wrong. CONFLICT for a retired template, when no "
                    + "revision is in use, when another change is in progress on the template, when the organization "
                    + "has no approval process for change requests, and when a limit is reached. " + SEES)
    ResponseEntity<ApiResponse<ChangeRequestView>> create(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody CreateChangeRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(requests.create(sets.require(application), templateId, body)));
    }

    @PostMapping(BASE + "/{requestId}/comments")
    @Operation(
            operationId = "addChangeComment",
            summary = "Add a comment to a change request",
            description = "By the person who asked or by a person who may change the template, while the request is "
                    + "in progress. The place is checked against the revision the request read. CONFLICT when the "
                    + "request is closed or has the most comments. " + SEES)
    ApiResponse<ChangeRequestView> comment(@PathVariable UUID templateId, @PathVariable UUID requestId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody NewChangeComment body) {
        return ApiResponse.of(requests.comment(sets.require(application), templateId, requestId, body));
    }

    @PostMapping(BASE + "/{requestId}/revised-copy")
    @Operation(
            operationId = "createRevisedCopy",
            summary = "Make the revised copy of the sheet for a change request",
            description = "A draft that is a copy of the revision in use now, held for the request: people who only "
                    + "read "
                    + "the template never see it, and the revision in use is not edited. Only a person asked to "
                    + "decide the request now (and the administrator) may. It is made on demand and only once. "
                    + "FORBIDDEN for anybody else. CONFLICT when the request is answered or not waiting for an "
                    + "approval, when a copy or another draft already exists, or when no revision is in use. " + SEES)
    ApiResponse<ChangeRequestView> createCopy(@PathVariable UUID templateId, @PathVariable UUID requestId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(requests.createCopy(sets.require(application), templateId, requestId));
    }

    @GetMapping(BASE + "/{requestId}/register")
    @Operation(
            operationId = "getChangeRequestRegister",
            summary = "The register form of a change request",
            description = "The values of the Document Change Register form, one set of values for both views, with the "
                    + "form drawn as a sheet page for the Excel view. canComplete says whether the caller may "
                    + "complete the bottom of it now. " + SEES)
    ApiResponse<RegisterView> register(@PathVariable UUID templateId, @PathVariable UUID requestId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(registers.get(sets.require(application), templateId, requestId));
    }

    @PutMapping(BASE + "/{requestId}/register")
    @Operation(
            operationId = "completeChangeRequestRegister",
            summary = "Complete the bottom of the register form of a change request",
            description = "For the people asked to decide the request now (and the administrator), while it is in "
                    + "progress: the department, the format or document obsoleted with its day, who approved, the "
                    + "signature words, the effective day and the two answers. The new revision and the day it was "
                    + "published are stamped by the publish and cannot be written. The register is changed in place "
                    + "and is final once the request is answered. FORBIDDEN for anybody else, CONFLICT when the "
                    + "request is answered, CONCURRENT_MODIFICATION when the register changed since it was read. "
                    + "Audited by the names of the boxes that changed, never by what was written. " + SEES)
    ApiResponse<RegisterView> complete(@PathVariable UUID templateId, @PathVariable UUID requestId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody RegisterCompletion body) {
        return ApiResponse.of(registers.complete(sets.require(application), templateId, requestId, body));
    }

    @GetMapping(BASE + "/{requestId}/register/file")
    @Operation(
            operationId = "downloadChangeRequestRegister",
            summary = "The register form of a change request as an Excel file",
            description = "The same values as the form, drawn on the page of the register. Every cell is text, never a "
                    + "formula. " + SEES)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The Excel file",
            content = @Content(mediaType = XLSX, schema = @Schema(type = "string", format = "binary")))
    void registerFile(@PathVariable UUID templateId, @PathVariable UUID requestId,
            @RequestParam @Parameter(description = APPLICATION) String application, HttpServletResponse response)
            throws IOException {
        send(response, registers.file(sets.require(application), templateId, requestId));
    }

    @PostMapping(BASE + "/register/page")
    @Operation(
            operationId = "drawChangeRequestRegister",
            summary = "Draw the register form for a request about the template, without making the request",
            description = "For the screen that shows the Excel view while a person types: the typed parts are drawn "
                    + "with "
                    + "the parts the platform fills (the document, the revision in use, who asks and the department). "
                    + "Nothing is stored or logged. CONFLICT when no revision is in use. NOT_FOUND for a template the "
                    + "caller cannot read.")
    ApiResponse<RegisterView> draw(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @RequestBody RegisterInput body) {
        return ApiResponse.of(registers.draft(sets.require(application), templateId, body));
    }

    @PostMapping(BASE + "/register/file")
    @Operation(
            operationId = "downloadChangeRequestRegisterDraft",
            summary = "The register form for a request about the template as an Excel file, to fill there",
            description = "The typed parts the body carries (all may be empty) are written with the parts the platform "
                    + "fills. Every cell is text, never a formula. Nothing is stored or logged. NOT_FOUND for a "
                    + "template the caller cannot read.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The Excel file",
            content = @Content(mediaType = XLSX, schema = @Schema(type = "string", format = "binary")))
    void draftFile(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @RequestBody RegisterInput body, HttpServletResponse response) throws IOException {
        send(response, registers.draftFile(sets.require(application), templateId, body));
    }

    @PostMapping(value = BASE + "/register/read", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(
            operationId = "readChangeRequestRegisterFile",
            summary = "Take the typed parts out of a filled register file",
            description = "The body is the .xlsx file itself and nothing else. Only the parts a requester types are "
                    + "taken, from their places on the page; text anywhere else on the page is refused in plain "
                    + "words that name the place. The file is read under the limits of the spreadsheet upload and "
                    + "kept nowhere; the form is filled with the answer and the person raises the request from it. "
                    + "VALIDATION_ERROR for a file that is not a register, PAYLOAD_TOO_LARGE for a file over the "
                    + "limit, RATE_LIMITED when other files are being read. NOT_FOUND for a template the caller "
                    + "cannot read.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/octet-stream",
            schema = @Schema(type = "string", format = "binary")))
    ApiResponse<RegisterFileView> readFile(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application, HttpServletRequest request)
            throws IOException {
        return ApiResponse.of(registers.read(sets.require(application), templateId, readBounded(request)));
    }

    private static byte[] readBounded(HttpServletRequest request) throws IOException {
        long announced = request.getContentLengthLong();
        if (announced > MOST_FILE_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than the allowed size of "
                    + MOST_FILE_BYTES + " bytes.");
        }
        byte[] bytes = request.getInputStream().readNBytes(MOST_FILE_BYTES + 1);
        if (bytes.length > MOST_FILE_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than the allowed size of "
                    + MOST_FILE_BYTES + " bytes.");
        }
        return bytes;
    }

    private static void send(HttpServletResponse response, ChangeRequestRegisters.Download file) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(XLSX);
        response.setContentLength(file.bytes().length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getOutputStream().write(file.bytes());
    }
}
