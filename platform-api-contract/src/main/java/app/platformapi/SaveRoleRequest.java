package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Creates a role or changes one (Sprint 7). A role cannot be placed below itself or below one of its own sub-roles.
 * The text is typed by a person, so it never prints.
 *
 * @param name the name, unique in the organization
 * @param description what the role is for, optional
 * @param parentId the role above this one, absent for a top role
 */
public record SaveRoleRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        UUID parentId) {

    @Override
    public String toString() {
        return "SaveRoleRequest[redacted]";
    }
}
