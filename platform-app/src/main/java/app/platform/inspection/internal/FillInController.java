package app.platform.inspection.internal;

import app.platform.inspection.internal.FillInViews.FillInOptionsView;
import app.platform.inspection.internal.FillInViews.FillInPage;
import app.platform.inspection.internal.FillInViews.FillInStartedView;
import app.platform.inspection.internal.FillInViews.FillReportRequest;
import app.platform.inspection.internal.FillInViews.ReportColumnsView;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of the fill-ins (FF1, ADR-0192): whether a fill-in can be started on a template, starting one, and the
 * list of fill-ins. Thin: the rules are in {@link FillIns}. Answered only on an organization host, for a signed-in
 * member; the address names the template, never the organization.
 *
 * <p>The cells are the sheets module's ({@code /sheet-fills}); sending a fill-in for review, approving and returning
 * it are the ordinary approval endpoints, and the cluster takes part in them through the approval hook
 * ({@link FillApprovalHooks}).
 */
@RestController
@Tag(name = "Fill-ins")
class FillInController {

    private static final String SEES = "NOT_FOUND for a template the caller cannot see, for one that does not exist "
            + "and on the platform host (all alike). A template is visible by the rules of every record: the object "
            + "permission, the private default of a template, and the shares. ";

    private final FillIns fillIns;
    private final FillReport report;

    FillInController(FillIns fillIns, FillReport report) {
        this.fillIns = fillIns;
        this.report = report;
    }

    @GetMapping(ApiPaths.V1 + "/templates/{templateId}/fill-in-options")
    @Operation(
            operationId = "getFillInOptions",
            summary = "Whether the caller may start a fill-in on a template",
            description = "canStart is true when the caller holds the right to create fill-ins, the template is not "
                    + "retired and a version is in use; activeVersion is the version a fill-in started now would be "
                    + "made on. When canStart is false, reason says why in words for a person. The flag only hides "
                    + "a button: starting is decided again. " + SEES)
    ApiResponse<FillInOptionsView> options(@PathVariable UUID templateId) {
        return ApiResponse.of(fillIns.options(templateId));
    }

    @PostMapping(ApiPaths.V1 + "/templates/{templateId}/fill-ins")
    @Operation(
            operationId = "startFillIn",
            summary = "Start a fill-in on the version of a template that is in use",
            description = "Needs the right to create fill-ins (FORBIDDEN otherwise). The fill-in is pinned to the "
                    + "version in use at that moment for ever; the template and every other fill-in are untouched. "
                    + "The caller owns the new fill-in, and by the private default of the object nobody else sees "
                    + "it, apart from the people who may view every fill-in. CONFLICT for a retired template, when "
                    + "no version is in use and when the version moved while the request ran. Audited by the "
                    + "identifiers and the version number. " + SEES)
    ResponseEntity<ApiResponse<FillInStartedView>> start(@PathVariable UUID templateId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(fillIns.start(templateId)));
    }

    @GetMapping(ApiPaths.V1 + "/fill-ins")
    @Operation(
            operationId = "listFillIns",
            summary = "The fill-ins the caller may see, newest first",
            description = "One page, without the cells (a list never reads them), paged by the key of the last one "
                    + "seen and never by an offset, so a page is as cheap at the thousandth as at the first. The "
                    + "page is read through the record store, so a caller sees exactly the fill-ins they may see: "
                    + "their own, and all of them for a caller who may view every fill-in. Filters: templateId and "
                    + "status (DRAFT, SUBMITTED, RETURNED or APPROVED). A fill-in stays in the list after its "
                    + "template is unpublished or retired. size is at most 200 (50 when left out).")
    ApiResponse<FillInPage> list(@RequestParam(required = false) UUID templateId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.of(fillIns.page(templateId, status, size, cursor));
    }

    @PostMapping(ApiPaths.V1 + "/fill-ins/query")
    @Operation(
            operationId = "queryFillIns",
            summary = "The fill-ins the caller may see, for a search, a filter and an order",
            description = "The same page as the list, for a question a list view holds: search (a name, or the whole "
                    + "word of a status), a filter and up to two sort keys in the closed query model of the record "
                    + "store, and the narrowing to one template or status. A post, because the filter does not "
                    + "belong in an address. The page is read through the record store, so the caller sees exactly "
                    + "the fill-ins they may see. Nothing typed is logged.")
    ApiResponse<FillInPage> query(@Valid @RequestBody FillInViews.FillInQuery body) {
        return ApiResponse.of(fillIns.query(body));
    }

    @GetMapping(ApiPaths.V1 + "/fill-ins/report/columns")
    @Operation(
            operationId = "getFillInReportColumns",
            summary = "The columns of a template version that can be reported on",
            description = "The columns the author flagged reportable in the version (the version in use when "
                    + "versionNo is left out), in the order of the sheet, with their type and, for a list column, "
                    + "its values. A version still being written or reviewed has none to show. Only to draw the "
                    + "report's choices: the report decides again. " + SEES)
    ApiResponse<ReportColumnsView> reportColumns(@RequestParam UUID templateId,
            @RequestParam(required = false) Integer versionNo) {
        return ApiResponse.of(report.columns(templateId, versionNo));
    }

    @PostMapping(ApiPaths.V1 + "/fill-ins/report")
    @Operation(
            operationId = "reportFillIns",
            summary = "The fill-ins of a template version that hold a value in a reportable column",
            description = "One condition on one reportable column: EQUALS one value, ONE_OF up to 100 values, or "
                    + "BETWEEN two bounds (either may be left out) for a number or a date. The answer is a page "
                    + "of fill-ins in the form of the list, newest first, without the cells, paged by the key of "
                    + "the last one looked at and never by an offset. A caller sees exactly the fill-ins they see "
                    + "in the list: the same visibility, decided by the same code. Because a caller who sees few "
                    + "of many matches makes the search look through more of them, a page can be short and still "
                    + "carry a nextCursor: only a missing nextCursor means the end. The value travels in the body "
                    + "and is never logged or audited. VALIDATION_ERROR names the field that is wrong, with fixed "
                    + "words. " + SEES)
    ApiResponse<FillInPage> report(@Valid @RequestBody FillReportRequest request) {
        return ApiResponse.of(report.run(request));
    }
}
