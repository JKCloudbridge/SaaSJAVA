package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;
import static app.platform.identity.PlatformRole.PLATFORM_BILLING;
import static app.platform.identity.PlatformRole.PLATFORM_SUPPORT;

import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ChangeSubscriptionRequest;
import app.platformapi.FirstAdministratorRequest;
import app.platformapi.LifecycleRequest;
import app.platformapi.PageRequest;
import app.platformapi.PlatformOrganizationDetail;
import app.platformapi.PlatformOrganizationSummary;
import app.platformapi.ProvisionOrganizationRequest;
import app.platformapi.ReasonRequest;
import app.platformapi.RequestAccepted;
import app.platformapi.SetEntitlementRequest;
import app.platformapi.SetPoolRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The organizations part of the platform console (Sprint 6). Thin: the rules are in {@link OrganizationAdminService}.
 * Answered only on the platform host, and only for the platform roles each action needs (ADR-0030):
 * administrators do everything; support reads, resends a first-administrator invitation and signs people out; billing
 * reads and changes subscriptions. The organization named in a path is a destination chosen by an authorized platform
 * person, never the tenant of the request.
 */
@RestController
@Tag(name = "Platform organizations")
class PlatformOrganizationController {

    private final PlatformCaller caller;
    private final OrganizationAdminService organizations;

    PlatformOrganizationController(PlatformCaller caller, OrganizationAdminService organizations) {
        this.caller = caller;
        this.organizations = organizations;
    }

    @GetMapping(ApiPaths.PLATFORM_ORGANIZATIONS)
    @Operation(
            operationId = "listPlatformOrganizations",
            summary = "List the organizations (platform console)",
            description = "By short name, one page at a time. For platform administrators, support and billing. Never "
                    + "shows members or business data. Platform host only.")
    ApiPageResponse<PlatformOrganizationSummary> list(@ParameterObject PageRequest page,
            @RequestParam(name = "search", required = false) String search, Principal principal) {
        caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT, PLATFORM_BILLING);
        return organizations.list(page, search);
    }

    @GetMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}")
    @Operation(
            operationId = "getPlatformOrganization",
            summary = "One organization (platform console)",
            description = "Status, subscription, licence pools, features and the state of the first-administrator "
                    + "invitation (never its address). For platform administrators, support and billing.")
    ApiResponse<PlatformOrganizationDetail> get(@PathVariable UUID organizationId, Principal principal) {
        caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT, PLATFORM_BILLING);
        return ApiResponse.of(organizations.detail(organizationId));
    }

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS)
    @Operation(
            operationId = "provisionOrganization",
            summary = "Set up an organization for a client",
            description = "Creates the organization on a plan and invites its first administrator. The organization "
                    + "stays closed (its host answers not available) until that person accepts, which opens it. The "
                    + "answer is the same whether or not the address has an account. For platform administrators.")
    ResponseEntity<ApiResponse<PlatformOrganizationSummary>> provision(
            @Valid @RequestBody ProvisionOrganizationRequest body, Principal principal) {
        UUID actor = caller.require(principal, PLATFORM_ADMIN);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(organizations.provision(actor, body)));
    }

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/suspend")
    @Operation(
            operationId = "suspendOrganization",
            summary = "Suspend an organization",
            description = "Its host answers not available at once and everybody signed in loses access. Needs a "
                    + "reason (kept in the audit trail only). For platform administrators.")
    ResponseEntity<Void> suspend(@PathVariable UUID organizationId, @Valid @RequestBody LifecycleRequest body,
            Principal principal) {
        organizations.suspend(caller.require(principal, PLATFORM_ADMIN), organizationId, body.reason());
        return noContent();
    }

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/reinstate")
    @Operation(
            operationId = "reinstateOrganization",
            summary = "Reinstate a suspended organization",
            description = "People sign in again; sessions that ended do not come back. Needs a reason. For platform "
                    + "administrators.")
    ResponseEntity<Void> reinstate(@PathVariable UUID organizationId, @Valid @RequestBody LifecycleRequest body,
            Principal principal) {
        organizations.reinstate(caller.require(principal, PLATFORM_ADMIN), organizationId, body.reason());
        return noContent();
    }

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/deactivate")
    @Operation(
            operationId = "deactivateOrganization",
            summary = "Close an organization for good",
            description = "Final. Needs a reason and the short name of the organization typed again as confirmation. "
                    + "Open invitations are withdrawn and sessions end. Also cancels an organization that was still "
                    + "being set up. For platform administrators.")
    ResponseEntity<Void> deactivate(@PathVariable UUID organizationId, @Valid @RequestBody LifecycleRequest body,
            Principal principal) {
        organizations.deactivate(caller.require(principal, PLATFORM_ADMIN), organizationId, body.reason(),
                body.confirm());
        return noContent();
    }

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/first-administrator")
    @Operation(
            operationId = "inviteFirstAdministrator",
            summary = "Invite a new first administrator",
            description = "For an organization that is being set up, or that lost every administrator. Always answers "
                    + "202 with the same text whether or not the address has an account. For platform administrators.")
    ResponseEntity<ApiResponse<RequestAccepted>> inviteFirstAdministrator(@PathVariable UUID organizationId,
            @Valid @RequestBody FirstAdministratorRequest body, Principal principal) {
        organizations.inviteFirstAdministrator(caller.require(principal, PLATFORM_ADMIN), organizationId,
                body.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.of(RequestAccepted.INVITATION));
    }

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/first-administrator/resend")
    @Operation(
            operationId = "resendFirstAdministratorInvitation",
            summary = "Send the first-administrator invitation again",
            description = "A new link replaces the old one and the time starts again. CONFLICT when it is no longer "
                    + "open. For platform administrators and support.")
    ResponseEntity<ApiResponse<RequestAccepted>> resendFirstAdministrator(@PathVariable UUID organizationId,
            Principal principal) {
        organizations.resendFirstAdministrator(caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT),
                organizationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.of(RequestAccepted.INVITATION));
    }

    @PutMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/subscription")
    @Operation(
            operationId = "changeOrganizationSubscription",
            summary = "Change the subscription of an organization",
            description = "Plan, status and dates; also puts an organization without a plan on one. A plan that would "
                    + "leave a licence pool below the licences in use is refused (CONFLICT). No payment is involved. "
                    + "For platform administrators and billing.")
    ApiResponse<PlatformOrganizationDetail> changeSubscription(@PathVariable UUID organizationId,
            @Valid @RequestBody ChangeSubscriptionRequest body, Principal principal) {
        UUID actor = caller.require(principal, PLATFORM_ADMIN, PLATFORM_BILLING);
        return ApiResponse.of(organizations.changeSubscription(actor, organizationId, body));
    }

    @PutMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/pools/{licenceType}")
    @Operation(
            operationId = "setOrganizationLicencePool",
            summary = "Set the size of a licence pool of an organization",
            description = "Not below the licences in use (CONFLICT). For platform administrators.")
    ApiResponse<PlatformOrganizationDetail> setPool(@PathVariable UUID organizationId,
            @PathVariable String licenceType, @Valid @RequestBody SetPoolRequest body, Principal principal) {
        UUID actor = caller.require(principal, PLATFORM_ADMIN);
        return ApiResponse.of(organizations.setPool(actor, organizationId, licenceType, body));
    }

    @PutMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/entitlements/{feature}")
    @Operation(
            operationId = "setOrganizationEntitlement",
            summary = "Switch a feature on or off for an organization",
            description = "Over the plan default; an absent value removes the override. For platform administrators.")
    ApiResponse<PlatformOrganizationDetail> setEntitlement(@PathVariable UUID organizationId,
            @PathVariable String feature, @Valid @RequestBody SetEntitlementRequest body, Principal principal) {
        UUID actor = caller.require(principal, PLATFORM_ADMIN);
        return ApiResponse.of(organizations.setEntitlement(actor, organizationId, feature, body));
    }

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/sign-out-all")
    @Operation(
            operationId = "signOutOrganizationEverywhere",
            summary = "Sign everybody out of an organization",
            description = "Ends every session and token bound to the organization host. Needs a reason. For platform "
                    + "administrators and support.")
    ResponseEntity<Void> signOutAll(@PathVariable UUID organizationId, @Valid @RequestBody ReasonRequest body,
            Principal principal) {
        organizations.signOutOrganization(caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT),
                organizationId, body.reason());
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
