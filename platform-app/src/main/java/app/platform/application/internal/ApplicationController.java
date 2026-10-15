package app.platform.application.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CustomPageScriptView;
import app.platformapi.MyApplicationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final CustomPageScripts scripts;

    ApplicationController(MyApplications applications, CustomPageScripts scripts) {
        this.applications = applications;
        this.scripts = scripts;
    }

    @GetMapping(ApiPaths.MY_APPLICATIONS)
    @Operation(
            operationId = "getMyApplications",
            summary = "The applications the signed-in member may use, with their navigation",
            description = "An application is listed when it names the profile of the member or names no profile. "
                    + "Inside it only what the member may open is listed (Sprint 18): an object or record page "
                    + "entry needs the permission to read the object, a tab entry needs the tab and its custom page "
                    + "to exist; a heading that leads nowhere and an application with nothing left are left out. "
                    + "Items that point to something that no longer exists are left out. Presentation only: every "
                    + "page a link opens is decided again by the server. NOT_FOUND on the platform host.")
    ApiResponse<List<MyApplicationView>> mine() {
        return ApiResponse.of(applications.mine());
    }

    @GetMapping(ApiPaths.MY_CUSTOM_PAGES + "/{customPageApiName}")
    @Operation(
            operationId = "getCustomPageScript",
            summary = "The script of a custom page the signed-in member may open",
            description = "For a member of an application that lists a tab showing the page. The script is the "
                    + "organization's own text, checked against the hash that was published; the platform never "
                    + "runs it. NOT_FOUND for a page that does not exist or that the member may not open (the same "
                    + "answer), SERVICE_UNAVAILABLE (with Retry-After) when the object store cannot give it.")
    ResponseEntity<ApiResponse<CustomPageScriptView>> script(@PathVariable String customPageApiName) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(scripts.script(customPageApiName)));
    }
}
