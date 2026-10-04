package app.platform.security.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.DataAccessView;
import app.platformapi.DataCatalogue;
import app.platformapi.SaveDataAccessRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The permission matrices (Sprint 8, ADR-0049): what a profile, an access policy or one member is allowed to do with
 * objects and fields, and the signed-in member's own effective matrix. Thin: the rules are in
 * {@link DataAccessService}. Answered only on an organization host; an identifier of another organization is simply
 * not found.
 */
@RestController
@Tag(name = "Access")
class DataAccessController {

    private final DataAccessService service;

    DataAccessController(DataAccessService service) {
        this.service = service;
    }

    @GetMapping(ApiPaths.DATA_CATALOGUE)
    @Operation(
            operationId = "getDataCatalogue",
            summary = "The objects and fields a permission matrix can be about, and the actions",
            description = "Objects arrive with the metadata engine (Sprint 10); until then a deployment lists "
                    + "none. For "
                    + "members who manage access. NOT_FOUND on the platform host, FORBIDDEN without the ability.")
    ApiResponse<DataCatalogue> catalogue() {
        return ApiResponse.of(service.catalogue());
    }

    @GetMapping(ApiPaths.DATA_ACCESS_MINE)
    @Operation(
            operationId = "getMyDataAccess",
            summary = "What the signed-in member may do with data",
            description = "Their effective permissions on objects and fields (profile while licensed, access policies, "
                    + "the access policies of their groups, individual grants), with implied actions written out. "
                    + "Presentation only: every request is decided again by the server. NOT_FOUND on the platform "
                    + "host.")
    ApiResponse<DataAccessView> mine() {
        return ApiResponse.of(service.mine());
    }

    @GetMapping(ApiPaths.PROFILES + "/{profileId}/data-access")
    @Operation(
            operationId = "getProfileDataAccess",
            summary = "The permission matrix of a profile",
            description = "For members who manage access. The administrator profile answers everything. "
                    + "NOT_FOUND for a "
                    + "profile of another organization.")
    ApiResponse<DataAccessView> profile(@PathVariable UUID profileId) {
        return ApiResponse.of(service.ofProfile(profileId));
    }

    @PutMapping(ApiPaths.PROFILES + "/{profileId}/data-access")
    @Operation(
            operationId = "replaceProfileDataAccess",
            summary = "Replace the permission matrix of a profile",
            description = "What is not listed is not allowed. Counts for its members while they hold the "
                    + "licence of the "
                    + "profile. VALIDATION_ERROR for an unknown object, field or action; CONFLICT for the "
                    + "administrator profile. Audited.")
    ApiResponse<DataAccessView> replaceProfile(@PathVariable UUID profileId,
            @Valid @RequestBody SaveDataAccessRequest body) {
        return ApiResponse.of(service.replaceProfile(profileId, body));
    }

    @GetMapping(ApiPaths.ACCESS_POLICIES + "/{policyId}/data-access")
    @Operation(
            operationId = "getAccessPolicyDataAccess",
            summary = "The permission matrix of an access policy",
            description = "For members who manage access. NOT_FOUND for a policy of another organization.")
    ApiResponse<DataAccessView> policy(@PathVariable UUID policyId) {
        return ApiResponse.of(service.ofPolicy(policyId));
    }

    @PutMapping(ApiPaths.ACCESS_POLICIES + "/{policyId}/data-access")
    @Operation(
            operationId = "replaceAccessPolicyDataAccess",
            summary = "Replace the permission matrix of an access policy",
            description = "What is not listed is not allowed. Takes effect at once for everyone who holds the policy, "
                    + "also through a group. VALIDATION_ERROR for an unknown object, field or action. Audited.")
    ApiResponse<DataAccessView> replacePolicy(@PathVariable UUID policyId,
            @Valid @RequestBody SaveDataAccessRequest body) {
        return ApiResponse.of(service.replacePolicy(policyId, body));
    }

    @GetMapping(ApiPaths.MEMBERS + "/{membershipId}/data-access")
    @Operation(
            operationId = "getMemberDataAccess",
            summary = "The permissions on data granted to one member directly",
            description = "For members who manage access. NOT_FOUND for a member of another organization.")
    ApiResponse<DataAccessView> member(@PathVariable UUID membershipId) {
        return ApiResponse.of(service.ofMember(membershipId));
    }

    @PutMapping(ApiPaths.MEMBERS + "/{membershipId}/data-access")
    @Operation(
            operationId = "replaceMemberDataAccess",
            summary = "Replace the permissions on data granted to one member directly",
            description = "What is not listed is not granted. VALIDATION_ERROR for an unknown object, field or action; "
                    + "CONFLICT for a member who is not active. Audited.")
    ApiResponse<DataAccessView> replaceMember(@PathVariable UUID membershipId,
            @Valid @RequestBody SaveDataAccessRequest body) {
        return ApiResponse.of(service.replaceMember(membershipId, body));
    }
}
