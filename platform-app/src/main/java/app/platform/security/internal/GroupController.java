package app.platform.security.internal;

import app.platformapi.AddGroupMemberRequest;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.AssignPolicyRequest;
import app.platformapi.GroupView;
import app.platformapi.SaveGroupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public groups (Sprint 8, ADR-0047). Thin: the rules are in {@link GroupService}. Answered only on an organization
 * host
 * and only for members who manage access; an identifier of another organization is simply not found.
 */
@RestController
@Tag(name = "Access")
class GroupController {

    private final GroupService groups;

    GroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping(ApiPaths.GROUPS)
    @Operation(
            operationId = "listGroups",
            summary = "List the public groups of the organization",
            description = "With their people, nested groups and access policies. For members who manage access. "
                    + "NOT_FOUND on the platform host, FORBIDDEN without the ability.")
    ApiResponse<List<GroupView>> list() {
        return ApiResponse.of(groups.list());
    }

    @PostMapping(ApiPaths.GROUPS)
    @Operation(
            operationId = "createGroup",
            summary = "Create a public group",
            description = "A named set of people and groups. VALIDATION_ERROR for a name in use. Audited.")
    ResponseEntity<ApiResponse<GroupView>> create(@Valid @RequestBody SaveGroupRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(groups.create(body)));
    }

    @GetMapping(ApiPaths.GROUPS + "/{groupId}")
    @Operation(
            operationId = "getGroup",
            summary = "Read one public group",
            description = "NOT_FOUND for a group of another organization.")
    ApiResponse<GroupView> get(@PathVariable UUID groupId) {
        return ApiResponse.of(groups.get(groupId));
    }

    @PutMapping(ApiPaths.GROUPS + "/{groupId}")
    @Operation(
            operationId = "updateGroup",
            summary = "Rename a public group",
            description = "VALIDATION_ERROR for a name in use. NOT_FOUND for a group of another organization. Audited.")
    ApiResponse<GroupView> update(@PathVariable UUID groupId, @Valid @RequestBody SaveGroupRequest body) {
        return ApiResponse.of(groups.update(groupId, body));
    }

    @DeleteMapping(ApiPaths.GROUPS + "/{groupId}")
    @Operation(
            operationId = "deleteGroup",
            summary = "Remove a public group",
            description = "Everyone who held something through the group loses it at once. CONFLICT when that would "
                    + "leave nobody who can manage access. Audited.")
    ResponseEntity<Void> delete(@PathVariable UUID groupId) {
        groups.delete(groupId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(ApiPaths.GROUPS + "/{groupId}/members")
    @Operation(
            operationId = "addGroupMember",
            summary = "Put a person or another group into a group",
            description = "Name exactly one of membershipId and groupId. CONFLICT when the group would contain itself, "
                    + "directly or through other groups, or when the person is not an active member. Audited.")
    ApiResponse<GroupView> addMember(@PathVariable UUID groupId, @Valid @RequestBody AddGroupMemberRequest body) {
        return ApiResponse.of(groups.addMember(groupId, body));
    }

    @DeleteMapping(ApiPaths.GROUPS + "/{groupId}/members/people/{membershipId}")
    @Operation(
            operationId = "removeGroupPerson",
            summary = "Take a person out of a group",
            description = "Takes effect at once. CONFLICT when that would leave nobody who can manage access. Audited.")
    ApiResponse<GroupView> removePerson(@PathVariable UUID groupId, @PathVariable UUID membershipId) {
        return ApiResponse.of(groups.removePerson(groupId, membershipId));
    }

    @DeleteMapping(ApiPaths.GROUPS + "/{groupId}/members/groups/{innerGroupId}")
    @Operation(
            operationId = "removeGroupGroup",
            summary = "Take a nested group out of a group",
            description = "Takes effect at once. CONFLICT when that would leave nobody who can manage access. Audited.")
    ApiResponse<GroupView> removeGroup(@PathVariable UUID groupId, @PathVariable UUID innerGroupId) {
        return ApiResponse.of(groups.removeGroup(groupId, innerGroupId));
    }

    @PostMapping(ApiPaths.GROUPS + "/{groupId}/policies")
    @Operation(
            operationId = "giveGroupPolicy",
            summary = "Give an access policy to a group",
            description = "Everyone in the group, directly or through nested groups, holds what the policy gives. "
                    + "CONFLICT for a policy that needs a licence (a licence belongs to a person). Audited.")
    ApiResponse<GroupView> givePolicy(@PathVariable UUID groupId, @Valid @RequestBody AssignPolicyRequest body) {
        return ApiResponse.of(groups.givePolicy(groupId, body));
    }

    @DeleteMapping(ApiPaths.GROUPS + "/{groupId}/policies/{policyId}")
    @Operation(
            operationId = "takeGroupPolicy",
            summary = "Take an access policy from a group",
            description = "Takes effect at once. CONFLICT when that would leave nobody who can manage access. Audited.")
    ApiResponse<GroupView> takePolicy(@PathVariable UUID groupId, @PathVariable UUID policyId) {
        return ApiResponse.of(groups.takePolicy(groupId, policyId));
    }
}
