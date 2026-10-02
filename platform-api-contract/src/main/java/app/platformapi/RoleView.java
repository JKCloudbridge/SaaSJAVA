package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A role of the organization's hierarchy (Sprint 7). A role decides which records a member may see once records exist;
 * it never gives an ability.
 *
 * @param id the role
 * @param name the name chosen by the organization
 * @param description what the role is for
 * @param parentId the role above this one, absent for a top role
 * @param members how many members hold it
 */
public record RoleView(@NotNull UUID id, @NotNull String name, @NotNull String description, UUID parentId,
        int members) {

    @Override
    public String toString() {
        return "RoleView[redacted]";
    }
}
