package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Shares one record with a user, a public group or a role, or changes the level (or, for a role, the kind) of a share
 * that exists (Sprint 17, Functional track FA2B).
 *
 * @param granteeType {@code USER}, {@code GROUP}, {@code ROLE} (the members whose own role it is) or
 *        {@code ROLE_AND_SUBORDINATES} (the members of that role and of every role below it)
 * @param granteeId the user (a member of the organization), the public group or the role
 * @param accessLevel {@code READ} or {@code READ_WRITE}
 */
public record ShareRecordRequest(@NotNull @Size(max = 30) String granteeType, @NotNull UUID granteeId,
        @NotNull @Size(max = 20) String accessLevel) {
}
