package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * A record shared with one user, one public group or one role (Sprint 17, Functional track FA2B).
 *
 * @param id the share
 * @param granteeType {@code USER}, {@code GROUP}, {@code ROLE} or {@code ROLE_AND_SUBORDINATES}
 * @param granteeId the user, group or role the record is shared with
 * @param accessLevel {@code READ} or {@code READ_WRITE}; a share never gives the right to delete
 * @param sharedAt when the share was made or last changed
 * @param sharedBy the user who made it
 * @param granteeLabel the name of the group or role, or absent when it no longer exists or the grantee is a user (a
 *        person is named through the labels of the owner field, as a link is)
 */
public record RecordShareView(@NotNull UUID id, @NotNull String granteeType, @NotNull UUID granteeId,
        @NotNull String accessLevel, @NotNull Instant sharedAt, @NotNull UUID sharedBy, String granteeLabel) {
}
