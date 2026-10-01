package app.platform.web.status;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.PlatformStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells a caller that the API is up and can reach its database. Needs no tenant and returns no internals. */
@RestController
@Tag(name = "Platform")
class PlatformStatusController {

    private final PlatformStatusService service;

    PlatformStatusController(PlatformStatusService service) {
        this.service = service;
    }

    @GetMapping(ApiPaths.PLATFORM_STATUS)
    @Operation(
            operationId = "getPlatformStatus",
            summary = "Platform status",
            description = "Succeeds when the API is running and its database answered a query.")
    ApiResponse<PlatformStatus> status() {
        return ApiResponse.of(service.status());
    }
}
