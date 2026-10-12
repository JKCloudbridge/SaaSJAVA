package app.platform.metadata;

import java.util.UUID;

/**
 * Says that a profile may use a record type of an object, and whether it is the type a new record of that profile gets
 * when it names none (Sprint 15A, ADR-0098). A record type with no assignment is open to every profile; with at least
 * one it is open only to the profiles named. The profile is only an identifier, so deleting a profile is never
 * blocked by an assignment; the assignment is then never anyone's again, but it still restricts the record type (it
 * fails closed) until an administrator removes it.
 *
 * @param objectApiName the object
 * @param recordType the API name of the record type
 * @param profileId the profile that may use it
 * @param defaultForProfile whether it is the type a new record of the profile gets by default
 * @param version the version, for concurrent changes
 */
public record RecordTypeAssignment(String objectApiName, String recordType, UUID profileId, boolean defaultForProfile,
        long version) {

    /** The key that names the assignment in paths and change sets: the record type, a tilde and the profile. */
    public String key() {
        return keyOf(recordType, profileId);
    }

    /** The key of the assignment of the profile to the record type. */
    public static String keyOf(String recordType, UUID profileId) {
        return recordType + "~" + profileId;
    }
}
