package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Sets the organization-wide default of an object (Sprint 17). The effect is immediate and no record is rewritten.
 *
 * @param defaultAccess {@code PRIVATE}, {@code PUBLIC_READ} or {@code PUBLIC_READ_WRITE}
 * @param grantViaHierarchy whether the people above the owner in the role hierarchy read the record
 * @param version the version that was read, when the object has been set up before; absent the first time
 */
public record SaveObjectSharingRequest(@NotNull @Size(max = 30) String defaultAccess,
        @NotNull Boolean grantViaHierarchy, Long version) {
}
