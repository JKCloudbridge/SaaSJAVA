package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One profile that may use a record type (Sprint 15A, ADR-0098).
 *
 * @param key names it in change sets: the API name of the record type, a tilde and the identifier of the profile
 * @param recordType the record type
 * @param profileId the profile
 * @param profileName the name of the profile now, or null when the profile no longer exists (the assignment then
 *        applies to nobody, but it still restricts the record type until it is removed)
 * @param defaultForProfile whether this is the type a new record of the profile gets when it names none
 * @param version the version to send back when changing it
 */
public record RecordTypeAssignmentView(@NotNull String key, @NotNull String recordType, @NotNull UUID profileId,
        String profileName, boolean defaultForProfile, @NotNull Long version) {

    /** Prints nothing typed: the name of a profile is text the organization chose (ADR-0012). */
    @Override
    public String toString() {
        return "RecordTypeAssignmentView[redacted]";
    }
}
