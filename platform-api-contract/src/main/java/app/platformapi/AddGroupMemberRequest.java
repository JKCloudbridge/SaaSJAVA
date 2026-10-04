package app.platformapi;

import java.util.UUID;

/**
 * Puts a person or another group into a group (Sprint 8). Exactly one of the two is given.
 *
 * @param membershipId the member to add
 * @param groupId the group to add as a nested group; refused when it would make a loop
 */
public record AddGroupMemberRequest(UUID membershipId, UUID groupId) {
}
