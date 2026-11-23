package app.platform.inspection.internal;

import app.platform.inspection.internal.ManagementBoardViews.ManagementPlantBoard;
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

    @PostMapping(ApiPaths.V1 + "/management-home/plant-board")
    @Operation(
            operationId = "managementHomePlantBoard",
            summary = "The plant board of the Management Core Home page: tiles, score by month, Action plan tracker",
            description = "The same question as the summary (a month, how many months to compare, one plant, one "
                    + "form) and the same counting rule: every number is made over the fill-ins and Action plans "
                    + "the record store itself returns to the caller, narrowed to the application. It adds the "
                    + "status of every Action plan (Awaiting, Submitted, Late, Overdue N days) from the two days the "
                    + "platform stamped (the day the reviewer's Submit stamped the score and the day the plant head "
                    + "first sent the Action plan), the number of days in the setting actionPlanDueDays and today "
                    + "in UTC from the platform's clock. A plan with no stamped day is NO_DATE and is never "
                    + "guessed. Statuses are shown, never acted on. The sections are null for a caller who may not "
                    + "read fill-ins, and a month a plant did not report is a null score, never a zero. NOT_FOUND "
                    + "on the platform host, for an application without this page and for an organization without "
                    + "the plan feature; VALIDATION_ERROR for a month, span, plant or form that is not offered. "
                    + "Nothing typed, no score, plant, month or day is logged or audited.")
    ApiResponse<ManagementPlantBoard> plantBoard(
            @RequestParam @Parameter(description = "The application whose records are counted (its API name). It "
                    + "chooses among the sets of records only: NOT_FOUND, in the same words, for an application "
                    + "that does not exist, that the organization does not have or that has no such page.")
            String application, @RequestBody(required = false) ManagementSummaryRequest request) {
        return ApiResponse.of(home.board(sets.require(application), request));
    }
}
