package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Gives a member a profile (Sprint 7). The member then needs a licence of the profile's type.
 *
 * @param profileId the profile of the organization
 */
public record AssignProfileRequest(@NotNull UUID profileId) {
}
