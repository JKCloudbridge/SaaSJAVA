package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.UsageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The limits on the public API and their use, for the organization the host names (Sprint 32, ADR-0280). Thin: the
 * rules
 * are in {@link UsageService}. Organization hosts only; the caller must be a member who holds {@code usage.view}, and
 * the
 * organization must have the plan feature. A token of a connected application never reaches it.
 */
@RestController
@Tag(name = "Connected applications")
class UsageController {

    private final UsageService usage;

    UsageController(UsageService usage) {
        this.usage = usage;
    }

    @GetMapping(ApiPaths.USAGE)
    @Operation(operationId = "getApiUsage", summary = "The limits on the public API and how much is used",
            description = "Needs usage.view. The organization's limits and the limit of each connected application, "
                    + "with what has been used in the current window: numbers only. Reading it costs nothing against "
                    + "the limits. NOT_FOUND on the platform host.")
    ApiResponse<UsageView> usage() {
        return ApiResponse.of(usage.usage());
    }
}
