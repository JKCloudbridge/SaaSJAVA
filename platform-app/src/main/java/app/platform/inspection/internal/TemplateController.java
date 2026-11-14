package app.platform.inspection.internal;

import app.platform.inspection.internal.TemplateViews.AudienceOptionsView;
import app.platform.inspection.internal.TemplateViews.PublishTemplateRequest;
import app.platform.inspection.internal.TemplateViews.TemplateLifecycleView;
import app.platform.inspection.internal.TemplateViews.TemplatePage;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platform.inspection.internal.TemplateViews.SheetOptionsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of the template lifecycle (FA4, ADR-0188): the master list, the lifecycle of one template, and the
 * acts of publishing, unpublishing and retiring. Thin: the rules are in {@link TemplateList} and
 * {@link TemplatePublishing}. Answered only on an organization host, for a signed-in member; the address names the
 * template, never the organization.
 *
 * <p>Submitting for approval, approving and rejecting are not here: they are the ordinary approval endpoints, and the
 * cluster takes part in them through the approval hook ({@link TemplateApprovalHooks}).
 */
@RestController
@Tag(name = "Templates")
class TemplateController {

    private static final String BASE = ApiPaths.V1 + "/templates";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String SEES ="NOT_FOUND for a template the caller cannot see, for one that does not exist "
            + "and on the platform host (all alike). A template is visible by the rules of every record: the object "
            + "permission, the private default of a template, and the shares. ";

    private static final String APPLICATION = "The application whose records are meant (its API name). It chooses "
            + "among the sets of records only and proves nothing: NOT_FOUND, in the same words, for an application "
            + "that does not exist, one the organization does not have and the platform host. What the caller may do "
            + "inside the set is decided by the same rights as ever.";

    private final TemplateList list;
    private final TemplatePublishing publishing;
    private final TemplateDownloads downloads;
    private final TemplateNumbering numbering;
    private final RecordSets sets;

    TemplateController(TemplateList list, TemplatePublishing publishing, TemplateDownloads downloads,
            TemplateNumbering numbering, RecordSets sets) {
        this.sets = sets;
        this.numbering = numbering;
        this.list = list;
        this.publishing = publishing;
        this.downloads = downloads;
    }

    @GetMapping(BASE)
    @Operation(
            operationId = "listTemplates",
            summary = "The templates the caller may see, with the lifecycle of each",
            description = "One page of templates, each with the numbers of its active, approved, in-review and draft "
                    + "versions and the state of the template as one word. The page is read through the record "
                    + "store, so a caller sees exactly the templates they may see: their own, all of them for a "
                    + "caller who may view every template, and those published to a group they are in. "
                    + "Filters: search (part of a name or a number), category (the identification), partA, retired and "
                    + "state. "
                    + "The state filter is applied to the page after it is read: a page can hold fewer rows than asked "
                    + "while nextCursor still goes on (filtered is true then). Sort: name, number or newest. "
                    + "canCreate says whether the screen may offer New; the request is decided again.")
    ApiResponse<TemplatePage> list(@RequestParam @Parameter(description = APPLICATION) String application,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category, @RequestParam(required = false) String partA,
            @RequestParam(required = false) Boolean retired, @RequestParam(required = false) String state,
            @RequestParam(required = false) String sort, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.of(list.page(sets.require(application), search, category, partA, retired, state, sort,
                size, cursor));
    }

    @PostMapping(BASE + "/query")
    @Operation(
            operationId = "queryTemplates",
            summary = "The templates the caller may see, for a search, a filter and an order",
            description = "The same page as the list, for a question a list view holds: search (a name or a number, or "
                    + "a whole identification code or Part A), a filter (the closed query model of the record "
                    + "store, conditions combined with AND or OR), up to two sort keys, and a lifecycle state. A "
                    + "post, because the filter does not belong in an address. A field the caller may not read is "
                    + "answered like a field that does not exist, and an order or a filter that cannot use an index "
                    + "is bounded by the query engine's scan limit (and refused above it, naming the field). The "
                    + "page is read through the record store, so the caller sees exactly the templates they may see. "
                    + "Nothing typed is logged.")
    ApiResponse<TemplatePage> query(@RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody TemplateViews.TemplateQuery body) {
        return ApiResponse.of(list.query(sets.require(application), body));
    }

    @PostMapping(BASE + "/numbering-info")
    @Operation(
            operationId = "templateNumberingInfo",
            summary = "What the number of a template is made of, for the form while a person types",
            description = "Takes the identification and the parts typed so far (all optional) and answers which of "
                    + "Part A and Part B the format of that identification reads, whether it ends in a serial, the "
                    + "number written so far, whether this combination already has numbers and, only for a caller "
                    + "who may set the identification, the next serial and the whole number. Advice only: nothing is "
                    + "reserved and the Save decides. NOT_FOUND for a caller who cannot read templates and on the "
                    + "platform host. A post, because what is typed does not belong in an address; nothing typed is "
                    + "logged.")
    ApiResponse<TemplateViews.NumberingInfoView> numberingInfo(
            @RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody TemplateViews.NumberingInfoRequest body) {
        return ApiResponse.of(numbering.info(sets.require(application), body));
    }

    @GetMapping(BASE + "/audience-options")
    @Operation(
            operationId = "templateAudienceOptions",
            summary = "The locations and departments a template can be published to",
            description = "Every location of the organization's list with the departments that go with it, and for "
                    + "each department whether an administrator has set up a group for it. Needs the ability to "
                    + "publish sheets (FORBIDDEN otherwise).")
    ApiResponse<AudienceOptionsView> audienceOptions(
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(publishing.audienceOptions(sets.require(application)));
    }

    @GetMapping(BASE + "/{templateId}/suggestion-options")
    @Operation(
            operationId = "templateSuggestionOptions",
            summary = "The locations and departments an approver may suggest for a template",
            description = "The same lists as the publish dialog, for the popup of an approval step that asks for an "
                    + "audience. Needs only that the caller can see the template (NOT_FOUND otherwise), not the "
                    + "ability to publish: a suggestion shares nothing. The lists are the organization's own words.")
    ApiResponse<AudienceOptionsView> suggestionOptions(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(publishing.suggestionOptions(sets.require(application), templateId));
    }

    @GetMapping(BASE + "/{templateId}/lifecycle")
    @Operation(
            operationId = "getTemplateLifecycle",
            summary = "The lifecycle of one template and what the caller may do next",
            description = "The numbers of its versions by status, the groups it is published to, and the flags "
                    + "canPublish, canUnpublish and canRetire. The flags only hide buttons: each act is decided again. "
                    + SEES)
    ApiResponse<TemplateLifecycleView> lifecycle(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(publishing.lifecycle(sets.require(application), templateId));
    }

    @PostMapping(BASE + "/{templateId}/publish")
    @Operation(
            operationId = "publishTemplate",
            summary = "Publish an approved version to the people of departments",
            description = "Needs the ability to publish sheets. Makes the approved version (the latest one when "
                    + "versionNo is left out) the one in use, replacing the one that was, and shares the template, "
                    + "read only, with the group an administrator set up for each location and department asked "
                    + "for. A group published to and not asked for again is taken back. Every pair is checked before "
                    + "anything is shared and a pair that cannot be published to refuses the whole request: "
                    + "VALIDATION_ERROR with audiences[i] naming it (no group is set up, several are, the group was "
                    + "removed or has no members). CONFLICT for a retired template or when there is nothing "
                    + "approved to publish. Allowed while another version is in review. Audited by counts and "
                    + "version numbers, never by a name. " + SEES)
    ApiResponse<TemplateLifecycleView> publish(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody PublishTemplateRequest request) {
        return ApiResponse.of(publishing.publish(sets.require(application), templateId, request));
    }

    @PutMapping(BASE + "/{templateId}/options")
    @Operation(
            operationId = "setTemplateSheetOptions",
            summary = "Set view only and allow download for a template",
            description = "Needs the ability to publish sheets. viewOnly: nobody can start a fill-in from the template "
                    + "(the template can still be read). allowDownload: the sheet may be given as an Excel file to "
                    + "everybody who can read it; the people who may publish can always download. The options belong "
                    + "to the template, not to a revision, and may be changed while a revision is waiting for "
                    + "approval. Audited by the template's identifier and the two values, never by a name. " + SEES)
    ApiResponse<TemplateLifecycleView> setOptions(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @Valid @RequestBody SheetOptionsRequest request) {
        return ApiResponse.of(publishing.setOptions(sets.require(application), templateId, request));
    }

    @GetMapping(BASE + "/{templateId}/download")
    @Operation(
            operationId = "downloadTemplateSheet",
            summary = "The sheet of a template as an Excel file",
            description = "The revision in use, or the one named by versionNo (a stored number) when the caller may "
                    + "read it, as an .xlsx file named after the template number and the revision. The file holds "
                    + "the sheet as people read it with the input cells empty and a footer row with the template "
                    + "number and the revision at its right. Every cell is text, never a formula. FORBIDDEN, in fixed "
                    + "words, when download is not allowed for the template and the caller may not publish: the "
                    + "option is checked here and a hidden button is not the rule. Audited by the template's "
                    + "identifier, the revision number and the size. " + SEES)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The Excel file",
            content = @Content(mediaType = XLSX, schema = @Schema(type = "string", format = "binary")))
    void download(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application,
            @RequestParam(required = false) Integer versionNo, HttpServletResponse response) throws IOException {
        TemplateDownloads.Download file = downloads.download(sets.require(application), templateId, versionNo);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(XLSX);
        response.setContentLength(file.bytes().length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getOutputStream().write(file.bytes());
    }

    @PostMapping(BASE + "/{templateId}/unpublish")
    @Operation(
            operationId = "unpublishTemplate",
            summary = "Take a template back from the people it was published to",
            description = "Needs the ability to publish sheets. The version in use is approved again (not in use) and "
                    + "exactly the shares the publish made are removed; a share somebody gave by hand stays. "
                    + "CONFLICT when the template is not published. " + SEES)
    ApiResponse<TemplateLifecycleView> unpublish(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(publishing.unpublish(sets.require(application), templateId));
    }

    @PostMapping(BASE + "/{templateId}/retire")
    @Operation(
            operationId = "retireTemplate",
            summary = "Retire a template for good",
            description = "Needs the ability to retire sheets. The template is marked retired and its version in use "
                    + "ends as retired; nothing is deleted, the number is never given again and the people it was "
                    + "published to still read it. CONFLICT while a version waits for approval (recall it or let "
                    + "it finish first) and for a template that is already retired. " + SEES)
    ApiResponse<TemplateLifecycleView> retire(@PathVariable UUID templateId,
            @RequestParam @Parameter(description = APPLICATION) String application) {
        return ApiResponse.of(publishing.retire(sets.require(application), templateId));
    }
}
