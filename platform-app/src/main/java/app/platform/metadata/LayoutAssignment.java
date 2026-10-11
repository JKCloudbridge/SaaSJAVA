package app.platform.metadata;

import java.util.UUID;

/**
 * Which layout an object uses for a profile, and optionally a record type (Sprint 12, ADR-0071). With no profile it is
 * the default layout of the object. The profile is only an identifier: if the profile is deleted the assignment is
 * simply never chosen again.
 *
 * @param objectApiName the object
 * @param profileId the profile, or null for the default layout
 * @param recordType the record type (only together with a profile), or null
 * @param layout the API name of the layout
 * @param version the version, for concurrent changes
 */
public record LayoutAssignment(String objectApiName, UUID profileId, String recordType, String layout,
        long version) {

    /** The key that names the assignment in paths and change sets. */
    public String key() {
        return keyOf(profileId, recordType);
    }

    /** The key of the assignment for the profile and record type: {@code default}, {@code <profile>} or both. */
    public static String keyOf(UUID profileId, String recordType) {
        if (profileId == null) {
            return "default";
        }
        return recordType == null ? profileId.toString() : profileId + "~" + recordType;
    }
}
