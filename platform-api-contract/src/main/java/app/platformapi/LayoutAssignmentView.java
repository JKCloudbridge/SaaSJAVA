package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One layout assignment (Sprint 12).
 *
 * @param key names it in the paths: {@code default}, the identifier of the profile, or the identifier of the profile,
 *        a tilde and the API name of the record type
 * @param profileId the profile, or null for the default layout of the object
 * @param profileName the name of the profile now, or null when the profile no longer exists (the assignment is then
 *        ignored when a layout is chosen)
 * @param recordType the record type, or null
 * @param layout the layout it points to
 * @param version the version to send back when changing it
 */
public record LayoutAssignmentView(@NotNull String key, UUID profileId, String profileName, String recordType,
        @NotNull String layout, @NotNull Long version) {
}
