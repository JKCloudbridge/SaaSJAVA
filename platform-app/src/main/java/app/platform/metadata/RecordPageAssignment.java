package app.platform.metadata;

import java.util.UUID;

/**
 * Which record page an object uses for a profile, and optionally a record type (Sprint 20, ADR-0131). With no profile
 * it is the default record page of the object. Unlike a layout assignment (ADR-0071) a record type alone is a rule
 * here too: {@link RecordType} carries its own layout but no record page of its own, so the record-type-alone rule is
 * an assignment with a record type and no profile. The profile is only an identifier: if the profile is deleted the
 * assignment is simply never chosen again.
 *
 * @param objectApiName the object
 * @param profileId the profile, or null for the default record page of the object, or for the record-type-alone rule
 * @param recordType the record type, or null
 * @param recordPage the API name of the record page
 * @param version the version, for concurrent changes
 */
public record RecordPageAssignment(String objectApiName, UUID profileId, String recordType, String recordPage,
        long version) {

    /** The key that names the assignment in paths and change sets. */
    public String key() {
        return keyOf(profileId, recordType);
    }

    /** The key of the assignment for the profile and record type: {@code default}, a record type alone, the
     * profile alone, or the profile, a tilde and the record type. */
    public static String keyOf(UUID profileId, String recordType) {
        if (profileId == null) {
            return recordType == null ? "default" : "type~" + recordType;
        }
        return recordType == null ? profileId.toString() : profileId + "~" + recordType;
    }
}
