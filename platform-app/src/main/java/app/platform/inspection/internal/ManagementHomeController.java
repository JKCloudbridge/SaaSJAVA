package app.platform.inspection.internal;

import app.platform.inspection.internal.ManagementHomeViews.ManagementSummaryRequest;
import app.platform.inspection.internal.ManagementHomeViews.ManagementSummaryView;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint of the Home page that compares the plants (MC3, ADR-0262). Thin: the rules are in
 * {@link ManagementHome}. Answered only on an organization host, for a signed-in member, and only for an application
 * whose file says it has this dashboard while the organization has the application's plan feature; it needs no
 * ability, because every number is made over what the caller may already see.
 */
@RestController
@Tag(name = "Management home")
class ManagementHomeController {

    private final ManagementHome home;
    private final RecordSets sets;

    ManagementHomeController(ManagementHome home, RecordSets sets) {
        this.home = home;
        this.sets = sets;
    }

    @PostMapping(ApiPaths.V1 + "/management-home/summary")
    @Operation(
            operationId = "managementHomeSummary",
            summary = "The numbers and graphs of the Home page that compares the plants",
            description = "One answer for the whole page: the tiles and the graphs, for a month (this, last or a "
                    + "month as year-month) and how many months to compare (3, 6 or 12), and optionally one plant "
                    + "and one form. A post, because the filters do not belong in an address. Needs no ability: "
                    + "every number is counted over the fill-ins the caller may see, through the same record store "
                    + "query the lists use, narrowed to the application, so the page agrees with the lists and a "
                    + "plant head sees one plant. A section whose object the caller cannot read at all is null "
                    + "(never a zero) and a month a plant did not report is a gap (a null score, never a zero). "
                    + "The record store gives no total, so the headers are read in pages up to a limit; a number "
                    + "that reached it says atLeast. A section that failed is named in problems and the others are "
                    + "still answered. Months are months in UTC. NOT_FOUND on the platform host, for an application "
                    + "without this page and for an organization without the plan feature; VALIDATION_ERROR for a "
                    + "month, span, plant or form that is not offered. Nothing typed, no score, limit, plant or "
                    + "month is logged or audited.")
    ApiResponse<ManagementSummaryView> summary(
            @RequestParam @Parameter(description = "The application whose records are counted (its API name). It "
                    + "chooses among the sets of records only: NOT_FOUND, in the same words, for an application "
                    + "that does not exist, that the organization does not have or that has no such page.")
            String application, @RequestBody(required = false) ManagementSummaryRequest request) {
        return ApiResponse.of(home.summary(sets.require(application), request));
    }
}
