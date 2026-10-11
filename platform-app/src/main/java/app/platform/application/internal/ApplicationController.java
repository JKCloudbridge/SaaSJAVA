package app.platform.application.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.MyApplicationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The applications the signed-in member may use and their navigation (Sprint 12, ADR-0073). Thin: the rules are in
 * {@link MyApplications}. Answered only on an organization host, for any active member: it presents what the
 * organization configured for the member's profile and decides nothing about access.
 */
@RestController
@Tag(name = "Applications")
class ApplicationController {

    private final MyApplications applications;

    ApplicationController(MyApplications applications) {
        this.applications = applications;
    }

    @GetMapping(ApiPaths.MY_APPLICATIONS)
    @Operation(
            operationId = "getMyApplications",
            summary = "The applications the signed-in member may use, with their navigation",
            description = "An application is listed when it names the profile of the member or names no profile. "
                    + "Items that point to something that no longer exists are left out. Presentation only: every "
                    + "page a link opens is decided again by the server. NOT_FOUND on the platform host.")
    ApiResponse<List<MyApplicationView>> mine() {
        return ApiResponse.of(applications.mine());
    }
}
