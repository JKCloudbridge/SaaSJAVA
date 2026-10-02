package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * An access policy as it appears on a member (Sprint 7).
 *
 * @param id the policy
 * @param name the policy's name
 * @param licenceType the key of the licence type the assignment uses, absent when the policy needs none
 */
public record AccessPolicyRef(@NotNull UUID id, @NotNull String name, String licenceType) {

    @Override
    public String toString() {
        return "AccessPolicyRef[redacted]";
    }
}
