package app.platform.inspection.internal;

import app.platform.inspection.internal.TemplateViews.AudienceOptionsView;
import app.platform.inspection.internal.TemplateViews.PublishTemplateRequest;
import app.platform.inspection.internal.TemplateViews.TemplateLifecycleView;
import app.platform.inspection.internal.TemplateViews.TemplatePage;
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
    private static final String SEES = "NOT_FOUND for a template the caller cannot see, for one that does not exist "
            + "and on the platform host (all alike). A template is visible by the rules of every record: the object "
            + "permission, the private default of a template, and the shares. ";

    private final TemplateList list;
    private final TemplatePublishing publishing;

    TemplateController(TemplateList list, TemplatePublishing publishing) {
        this.list = list;
        this.publishing = publishing;
    }

    @GetMapping(BASE)
    @Operation(
            operationId = "listTemplates",
            summary = "The templates the caller may see, with the lifecycle of each",
            description = "One page of templates, each with the numbers of its active, approved, in-review and draft "
                    + "versions and the state of the template as one word. The page is read through the record "
                    + "store, so a caller sees exactly the templates they may see: their own, all of them for a "
                    + "caller who may view every template, and those published to a group they are in. "
                    + "Filters: search (part of a name or a number), category, subCategory, retired and state. "
                    + "The state filter is applied to the page after it is read: a page can hold fewer rows than asked "
                    + "while nextCursor still goes on (filtered is true then). Sort: name, number or newest. "
                    + "canCreate says whether the screen may offer New; the request is decided again.")
    ApiResponse<TemplatePage> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String category, @RequestParam(required = false) String subCategory,
            @RequestParam(required = false) Boolean retired, @RequestParam(required = false) String state,
            @RequestParam(required = false) String sort, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.of(list.page(search, category, subCategory, retired, state, sort, size, cursor));
    }

    @PostMapping(BASE + "/query")
    @Operation(
            operationId = "queryTemplates",
            summary = "The templates the caller may see, for a search, a filter and an order",
            description = "The same page as the list, for a question a list view holds: search (a name or a number, or "
                    + "a whole category or sub-category code), a filter (the closed query model of the record "
                    + "store, conditions combined with AND or OR), up to two sort keys, and a lifecycle state. A "
                    + "post, because the filter does not belong in an address. A field the caller may not read is "
                    + "answered like a field that does not exist, and an order or a filter that cannot use an index "
                    + "is bounded by the query engine's scan limit (and refused above it, naming the field). The "
                    + "page is read through the record store, so the caller sees exactly the templates they may see. "
                    + "Nothing typed is logged.")
    ApiResponse<TemplatePage> query(@Valid @RequestBody TemplateViews.TemplateQuery body) {
        return ApiResponse.of(list.query(body));
    }

    @GetMapping(BASE + "/audience-options")
    @Operation(
            operationId = "templateAudienceOptions",
            summary = "The locations and departments a template can be published to",
            description = "Every location of the organization's list with the departments that go with it, and for "
                    + "each department whether an administrator has set up a group for it. Needs the ability to "
                    + "publish sheets (FORBIDDEN otherwise).")
    ApiResponse<AudienceOptionsView> audienceOptions() {
        return ApiResponse.of(publishing.audienceOptions());
    }

    @GetMapping(BASE + "/{templateId}/lifecycle")
    @Operation(
            operationId = "getTemplateLifecycle",
            summary = "The lifecycle of one template and what the caller may do next",
            description = "The numbers of its versions by status, the groups it is published to, and the flags "
                    + "canPublish, canUnpublish and canRetire. The flags only hide buttons: each act is decided again. "
                    + SEES)
    ApiResponse<TemplateLifecycleView> lifecycle(@PathVariable UUID templateId) {
        return ApiResponse.of(publishing.lifecycle(templateId));
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
            @Valid @RequestBody PublishTemplateRequest request) {
        return ApiResponse.of(publishing.publish(templateId, request));
    }

    @PostMapping(BASE + "/{templateId}/unpublish")
    @Operation(
            operationId = "unpublishTemplate",
            summary = "Take a template back from the people it was published to",
            description = "Needs the ability to publish sheets. The version in use is approved again (not in use) and "
                    + "exactly the shares the publish made are removed; a share somebody gave by hand stays. "
                    + "CONFLICT when the template is not published. " + SEES)
    ApiResponse<TemplateLifecycleView> unpublish(@PathVariable UUID templateId) {
        return ApiResponse.of(publishing.unpublish(templateId));
    }

    @PostMapping(BASE + "/{templateId}/retire")
    @Operation(
            operationId = "retireTemplate",
            summary = "Retire a template for good",
            description = "Needs the ability to retire sheets. The template is marked retired and its version in use "
                    + "ends as retired; nothing is deleted, the number is never given again and the people it was "
                    + "published to still read it. CONFLICT while a version waits for approval (recall it or let "
                    + "it finish first) and for a template that is already retired. " + SEES)
    ApiResponse<TemplateLifecycleView> retire(@PathVariable UUID templateId) {
        return ApiResponse.of(publishing.retire(templateId));
    }
}
