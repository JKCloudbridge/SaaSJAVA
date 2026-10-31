package app.platform.inspection.internal;

import app.platform.inspection.internal.QualityHomeViews.SummaryRequest;
import app.platform.inspection.internal.QualityHomeViews.SummaryView;
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
 * The one endpoint of the home page of an application of the kind (FA9, ADR-0203; for every application since FA13B,
 * ADR-0226). Thin: the rules are in {@link QualityHome}. Answered only on an organization host, for a signed-in member,
 * and only while the organization has the application's plan feature; it needs no ability, because every number is made
 * over what the caller may already see.
 */
@RestController
@Tag(name = "Quality home")
class QualityHomeController {

    private final QualityHome home;
    private final RecordSets sets;

    QualityHomeController(QualityHome home, RecordSets sets) {
        this.home = home;
        this.sets = sets;
    }

    @PostMapping(ApiPaths.V1 + "/quality-home/summary")
    @Operation(
            operationId = "qualityHomeSummary",
            summary = "The numbers and graphs of the Quality Management System home page",
            description = "One answer for the whole page: the tiles, the graphs and the lists, for a period (7d, 30d, "
                    + "90d or 12m; 30d when left out) and optionally one location and one department the "
                    + "organization offers. A post, because the filters do not belong in an address. Needs no "
                    + "ability: every number is counted over the records the caller may see, through the same record "
                    + "store query the lists use, so the page agrees with the lists, and a section whose object "
                    + "the caller cannot read at all is null (never a zero). The record store gives no total, so "
                    + "the headers are read in pages up to a limit; a number that reached it says atLeast. A "
                    + "section that failed is named in problems and the others are still answered. Days are "
                    + "days in UTC. NOT_FOUND on the platform host and for an organization without the plan "
                    + "feature; VALIDATION_ERROR for a period, location or department that is not offered. "
                    + "Nothing typed, no name and no filter value is logged or audited.")
    ApiResponse<SummaryView> summary(@RequestParam @Parameter(description = "The application whose records are "
            + "counted (its API name). It chooses among the sets of records only: NOT_FOUND, in the same words, "
            + "for an application that does not exist or that the organization does not have.") String application,
            @RequestBody(required = false) SummaryRequest request) {
        return ApiResponse.of(home.summary(sets.require(application), request));
    }
}
