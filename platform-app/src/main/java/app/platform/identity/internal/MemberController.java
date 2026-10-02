package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.MemberView;
import app.platformapi.SetAdministratorRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The members of the organization the host names (Sprint 5, ADR-0026). Thin: the rules are in {@link MemberService}.
 * Answered only on an organization host and only for its administrators; the organization is the host, and a membership
 * identifier of another organization is simply not found.
 */
@RestController
@Tag(name = "Members")
class MemberController {

    private final MemberService members;

    MemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping(ApiPaths.MEMBERS)
    @Operation(
            operationId = "listMembers",
            summary = "List the members of the organization",
            description = "For the administrators of the organization of the host. NOT_FOUND on the platform host, "
                    + "FORBIDDEN for a member who is not an administrator.")
    ApiResponse<List<MemberView>> list() {
        return ApiResponse.of(members.list());
    }

    @PostMapping(ApiPaths.MEMBERS + "/{membershipId}/deactivate")
    @Operation(
            operationId = "deactivateMember",
            summary = "Deactivate a member",
            description = "The member is signed out of this organization at once and cannot get back in until "
                    + "reactivated; their other organizations are untouched. CONFLICT when already deactivated or "
                    + "when this is the last administrator. NOT_FOUND for a member of another organization.")
    ResponseEntity<Void> deactivate(@PathVariable UUID membershipId) {
        members.deactivate(membershipId);
        return noContent();
    }

    @PostMapping(ApiPaths.MEMBERS + "/{membershipId}/reactivate")
    @Operation(
            operationId = "reactivateMember",
            summary = "Reactivate a deactivated member",
            description = "The person can sign in again; sessions that ended do not come back and the administrator "
                    + "marker is not restored. CONFLICT when the member is not deactivated.")
    ResponseEntity<Void> reactivate(@PathVariable UUID membershipId) {
        members.reactivate(membershipId);
        return noContent();
    }

    @PutMapping(ApiPaths.MEMBERS + "/{membershipId}/administrator")
    @Operation(
            operationId = "setMemberAdministrator",
            summary = "Name a member an administrator, or release them",
            description = "A stop-gap until access policies exist. The last administrator cannot be released "
                    + "(CONFLICT). Only an active member can be an administrator.")
    ResponseEntity<Void> setAdministrator(@PathVariable UUID membershipId,
            @Valid @RequestBody SetAdministratorRequest body) {
        members.setAdministrator(membershipId, body.administrator());
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
