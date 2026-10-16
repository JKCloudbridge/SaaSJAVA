package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One record page assignment (Sprint 20).
 *
 * @param key names it in the paths: {@code default}, {@code type~<recordType>} for the record-type-alone rule, the
 *        identifier of the profile, or the identifier of the profile, a tilde and the API name of the record type
 * @param profileId the profile, or null for the default record page of the object, or the record-type-alone rule
 * @param profileName the name of the profile now, or null when the profile no longer exists (the assignment is then
 *        ignored when a record page is chosen) or there is none
 * @param recordType the record type, or null
 * @param recordPage the record page it points to
 * @param version the version to send back when changing it
 */
public record RecordPageAssignmentView(@NotNull String key, UUID profileId, String profileName, String recordType,
        @NotNull String recordPage, @NotNull Long version) {

    /** Prints nothing typed: the name of a profile is text the organization chose (ADR-0012). */
    @Override
    public String toString() {
        return "RecordPageAssignmentView[redacted]";
    }
}
