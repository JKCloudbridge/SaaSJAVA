package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Shares one record with a user or a public group, or changes the level of a share that exists (Sprint 17).
 *
 * @param granteeType {@code USER} or {@code GROUP}
 * @param granteeId the user (a member of the organization) or the public group
 * @param accessLevel {@code READ} or {@code READ_WRITE}
 */
public record ShareRecordRequest(@NotNull @Size(max = 10) String granteeType, @NotNull UUID granteeId,
        @NotNull @Size(max = 20) String accessLevel) {
}
