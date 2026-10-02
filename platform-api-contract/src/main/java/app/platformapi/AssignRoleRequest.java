package app.platformapi;

import java.util.UUID;

/**
 * Places a member in the role hierarchy, or takes them out of it (Sprint 7).
 *
 * @param roleId the role of the organization, absent for none
 */
public record AssignRoleRequest(UUID roleId) {
}
