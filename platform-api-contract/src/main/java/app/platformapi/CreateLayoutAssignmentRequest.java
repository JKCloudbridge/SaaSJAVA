package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Says which page layout an object uses for a profile (Sprint 12). With no profile and no record type it names the
 * default layout of the object. A layout for a record type alone is not set here but on the record type itself.
 *
 * @param profileId the profile it is for, or null for the default layout of the object
 * @param recordType the record type it is for (only together with a profile), or null
 * @param layout the API name of the layout of the same object
 */
public record CreateLayoutAssignmentRequest(UUID profileId, @Size(max = 60) String recordType,
        @NotBlank @Size(max = 60) String layout) {
}
