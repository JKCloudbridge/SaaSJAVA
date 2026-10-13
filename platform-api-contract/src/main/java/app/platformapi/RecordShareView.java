package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * A record shared with one user or one public group (Sprint 17).
 *
 * @param id the share
 * @param granteeType {@code USER} or {@code GROUP}
 * @param granteeId the user or group the record is shared with
 * @param accessLevel {@code READ} or {@code READ_WRITE}; a share never gives the right to delete
 * @param sharedAt when the share was made or last changed
 * @param sharedBy the user who made it
 */
public record RecordShareView(@NotNull UUID id, @NotNull String granteeType, @NotNull UUID granteeId,
        @NotNull String accessLevel, @NotNull Instant sharedAt, @NotNull UUID sharedBy) {
}
