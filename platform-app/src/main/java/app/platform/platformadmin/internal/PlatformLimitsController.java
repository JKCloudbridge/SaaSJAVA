package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;
import static app.platform.identity.PlatformRole.PLATFORM_SUPPORT;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.OrganizationLimitsView;
import app.platformapi.ReasonRequest;
import app.platformapi.SetOrganizationLimitsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The limits on the public API of an organization, for the platform console (Sprint 32, ADR-0283). Thin: the rules are
 * in {@link PlatformLimitsService}. Platform host only; administrators change the numbers, support may read them. The
 * organization named in a path is a destination chosen by an authorized platform person, never the tenant of the
 * request.
 */
@RestController
@Tag(name = "Platform organizations")
class PlatformLimitsController {

    private static final String ROOT = ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/limits";

    private final PlatformCaller caller;
    private final PlatformLimitsService limits;

    PlatformLimitsController(PlatformCaller caller, PlatformLimitsService limits) {
        this.caller = caller;
        this.limits = limits;
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_SUPPORT})
    @GetMapping(ROOT)
    @Operation(
            operationId = "getOrganizationLimits",
            summary = "The limits on the public API of an organization (platform console)",
            description = "The numbers in force and the defaults beside them. Numbers only. For platform "
                    + "administrators and support. Platform host only.")
    ApiResponse<OrganizationLimitsView> read(@PathVariable UUID organizationId) {
        return ApiResponse.of(limits.read(organizationId));
    }

    @PlatformFunction({PLATFORM_ADMIN})
    @PutMapping(ROOT)
    @Operation(
            operationId = "setOrganizationLimits",
            summary = "Set the limits on the public API of an organization (platform console)",
            description = "Replaces the override of the organization; a number left out keeps its default and every "
                    + "number stays inside the ceilings of the platform's settings. Needs a reason, kept in the audit "
                    + "trail only. Other organizations are not touched. For platform administrators. Platform host "
                    + "only.")
    ApiResponse<OrganizationLimitsView> set(@PathVariable UUID organizationId,
            @Valid @RequestBody SetOrganizationLimitsRequest body, Principal principal) {
        return ApiResponse.of(limits.set(caller.person(principal), organizationId, body));
    }

    @PlatformFunction({PLATFORM_ADMIN})
    @PostMapping(ROOT + "/reset")
    @Operation(
            operationId = "resetOrganizationLimits",
            summary = "Return the limits of an organization to the defaults (platform console)",
            description = "Removes the override. Needs a reason, kept in the audit trail only. For platform "
                    + "administrators. Platform host only.")
    ApiResponse<OrganizationLimitsView> reset(@PathVariable UUID organizationId,
            @Valid @RequestBody ReasonRequest body, Principal principal) {
        return ApiResponse.of(limits.clear(caller.person(principal), organizationId, body.reason()));
    }
}
