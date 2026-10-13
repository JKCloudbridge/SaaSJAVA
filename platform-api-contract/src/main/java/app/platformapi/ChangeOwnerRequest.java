package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Gives a record to another owner (Sprint 17).
 *
 * @param ownerId the new owner: an active member of the organization
 * @param version the version of the record that was read
 */
public record ChangeOwnerRequest(@NotNull UUID ownerId, @NotNull Long version) {
}
