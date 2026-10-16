package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Says which record page an object uses for a profile, a record type, or both (Sprint 20). With no profile and no
 * record type it names the default record page of the object. With a record type and no profile it is the
 * record-type-alone rule.
 *
 * @param profileId the profile it is for, or null
 * @param recordType the record type it is for, or null
 * @param recordPage the API name of a record page of the same object
 */
public record CreateRecordPageAssignmentRequest(UUID profileId, @Size(max = 60) String recordType,
        @NotBlank @Size(max = 60) String recordPage) {
}
