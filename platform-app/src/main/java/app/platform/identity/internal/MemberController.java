package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.AssignPolicyRequest;
import app.platformapi.AssignProfileRequest;
import app.platformapi.AssignRoleRequest;
import app.platformapi.GrantAbilityRequest;
import app.platformapi.LicencePoolView;
import app.platformapi.MemberAccessView;
import app.platformapi.MemberView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The members of the organization the host names (Sprint 5, ADR-0026; profiles, roles and access in Sprint 7,
 * ADR-0039). Thin: the rules are in {@link MemberService}. Answered only on an organization host and only for members
 * whose abilities allow the action; the organization is the host, and a membership identifier of another organization
 * is simply not found.
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
            description = "With each member's profile, role, access policies and licence. For members who may see "
                    + "members. NOT_FOUND on the platform host, FORBIDDEN without the ability.")
    ApiResponse<List<MemberView>> list() {
        return ApiResponse.of(members.list());
    }

    @GetMapping(ApiPaths.MEMBERS + "/{membershipId}/access")
    @Operation(
            operationId = "getMemberAccess",
            summary = "Everything that decides what one member may do",
            description = "Their profile, role, access policies, individual grants (with the notes) and the resulting "
                    + "abilities. For members who manage access. NOT_FOUND for a member of another organization.")
    ApiResponse<MemberAccessView> access(@PathVariable UUID membershipId) {
        return ApiResponse.of(members.accessOf(membershipId));
    }

    @PostMapping(ApiPaths.MEMBERS + "/{membershipId}/deactivate")
    @Operation(
            operationId = "deactivateMember",
            summary = "Deactivate a member",
            description = "The member is signed out of this organization at once, their licences go back to the pool, "
                    + "and they cannot get back in until reactivated; their other organizations are untouched. "
                    + "CONFLICT when already deactivated or when this is the last member who can manage access. "
                    + "NOT_FOUND for a member of another organization.")
    ResponseEntity<Void> deactivate(@PathVariable UUID membershipId) {
        members.deactivate(membershipId);
        return noContent();
    }

    @PostMapping(ApiPaths.MEMBERS + "/{membershipId}/reactivate")
    @Operation(
            operationId = "reactivateMember",
            summary = "Reactivate a deactivated member",
            description = "The person can sign in again with the organization's default profile and nothing else "
                    + "(what they held ended with their membership), and a licence if one is free; sessions that "
                    + "ended do not come back. CONFLICT when the member is "
                    + "not deactivated.")
    ResponseEntity<Void> reactivate(@PathVariable UUID membershipId) {
        members.reactivate(membershipId);
        return noContent();
    }

    @PutMapping(ApiPaths.MEMBERS + "/{membershipId}/profile")
    @Operation(
            operationId = "setMemberProfile",
            summary = "Give a member a profile",
            description = "The member then holds a licence of the profile's type, taken from the pool (the one they "
                    + "held for the old profile goes back). CONFLICT when none is free, the member is not active, or "
                    + "the change would leave nobody who can manage access. For members who manage access; audited.")
    ResponseEntity<Void> setProfile(@PathVariable UUID membershipId, @Valid @RequestBody AssignProfileRequest body) {
        members.setProfile(membershipId, body.profileId());
        return noContent();
    }

    @PutMapping(ApiPaths.MEMBERS + "/{membershipId}/role")
    @Operation(
            operationId = "setMemberRole",
            summary = "Place a member in the role hierarchy, or take them out of it",
            description = "A role decides which records the member may see once records exist; it gives no ability. "
                    + "For members who manage access; audited.")
    ResponseEntity<Void> setRole(@PathVariable UUID membershipId, @Valid @RequestBody AssignRoleRequest body) {
        members.setRole(membershipId, body.roleId());
        return noContent();
    }

    @PostMapping(ApiPaths.MEMBERS + "/{membershipId}/policies")
    @Operation(
            operationId = "assignMemberPolicy",
            summary = "Give a member an access policy",
            description = "A policy that needs a licence uses one from the pool: CONFLICT when none is free, two "
                    + "administrators asking for the last one have one winner. For members who manage access; "
                    + "audited.")
    ResponseEntity<Void> assignPolicy(@PathVariable UUID membershipId, @Valid @RequestBody AssignPolicyRequest body) {
        members.assignPolicy(membershipId, body.policyId());
        return noContent();
    }

    @DeleteMapping(ApiPaths.MEMBERS + "/{membershipId}/policies/{policyId}")
    @Operation(
            operationId = "unassignMemberPolicy",
            summary = "Take an access policy from a member",
            description = "Its licence goes back to the pool. Nothing happens when the member does not hold it. "
                    + "CONFLICT when the change would leave nobody who can manage access.")
    ResponseEntity<Void> unassignPolicy(@PathVariable UUID membershipId, @PathVariable UUID policyId) {
        members.unassignPolicy(membershipId, policyId);
        return noContent();
    }

    @PostMapping(ApiPaths.MEMBERS + "/{membershipId}/grants")
    @Operation(
            operationId = "grantMemberAbility",
            summary = "Give one member one ability directly",
            description = "With an optional short note why, shown only to members who manage access. VALIDATION_ERROR "
                    + "for an unknown ability. Audited.")
    ResponseEntity<Void> grant(@PathVariable UUID membershipId, @Valid @RequestBody GrantAbilityRequest body) {
        members.grant(membershipId, body.ability(), body.reason());
        return noContent();
    }

    @DeleteMapping(ApiPaths.MEMBERS + "/{membershipId}/grants/{ability}")
    @Operation(
            operationId = "revokeMemberAbility",
            summary = "Take a directly given ability back",
            description = "Nothing happens when the member does not hold it. CONFLICT when the change would leave "
                    + "nobody who can manage access.")
    ResponseEntity<Void> revokeGrant(@PathVariable UUID membershipId, @PathVariable String ability) {
        members.revokeGrant(membershipId, ability);
        return noContent();
    }

    @GetMapping(ApiPaths.LICENCES)
    @Operation(
            operationId = "listLicencePools",
            summary = "The licence pools of the organization",
            description = "Per licence type: how many licences the organization holds, how many are assigned (for "
                    + "profiles and for licence-bound access policies) and how many are free. For members who manage "
                    + "licences. NOT_FOUND on the platform host.")
    ApiResponse<List<LicencePoolView>> pools() {
        return ApiResponse.of(members.pools());
    }

    @PutMapping(ApiPaths.MEMBERS + "/{membershipId}/licence")
    @Operation(
            operationId = "giveMemberLicence",
            summary = "Give a member the licence their profile needs",
            description = "For a member who has none (for example one who joined when none was free). CONFLICT when "
                    + "none is free of that type, the member is not active or has no profile; two administrators "
                    + "asking for the last free licence have one winner. A licence counts assignments only: it "
                    + "grants no permission.")
    ResponseEntity<Void> giveLicence(@PathVariable UUID membershipId) {
        members.giveLicence(membershipId);
        return noContent();
    }

    @DeleteMapping(ApiPaths.MEMBERS + "/{membershipId}/licence")
    @Operation(
            operationId = "releaseMemberLicence",
            summary = "Take a member licence back",
            description = "The licence for their profile returns to the pool and the profile gives no abilities until "
                    + "one is held again. Nothing happens when the member holds none. CONFLICT when the change "
                    + "would leave nobody who can manage access.")
    ResponseEntity<Void> releaseLicence(@PathVariable UUID membershipId) {
        members.releaseLicence(membershipId);
        return noContent();
    }

    @PostMapping(ApiPaths.ORGANIZATION_SIGN_OUT_ALL)
    @Operation(
            operationId = "signOutEveryoneElse",
            summary = "Sign everybody else out of the organization",
            description = "Ends every session and token bound to this organization host except the caller own. For "
                    + "members who may sign people out; audited.")
    ResponseEntity<Void> signOutEveryoneElse() {
        members.signOutEveryoneElse();
        return noContent();
    }

    @PostMapping(ApiPaths.ORGANIZATION_LEAVE)
    @Operation(
            operationId = "leaveOrganization",
            summary = "Leave the organization",
            description = "The caller's membership ends: they are signed out of this organization, their licences go "
                    + "back to the pool, and an administrator can let them back in. Their other organizations are "
                    + "untouched. CONFLICT for the last member who can manage access. Any active member may leave.")
    ResponseEntity<Void> leave() {
        members.leave();
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
