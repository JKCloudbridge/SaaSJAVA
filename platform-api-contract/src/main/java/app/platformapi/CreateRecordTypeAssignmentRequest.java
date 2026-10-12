package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Lets a profile use a record type of an object (Sprint 15A, ADR-0098). A record type that has no assignment is open to
 * every profile; once it has at least one, only the profiles named may use it.
 *
 * @param recordType the API name of the record type; on the endpoint of a record type it is the one in the path and
 *        this value is ignored, in a change of a change set it is required
 * @param profileId the profile that may use the record type
 * @param defaultForProfile whether this is the record type a new record of this profile gets when it names none (a
 *        profile has at most one default per object; giving it here takes the mark off the previous one); left out
 *        means no
 */
public record CreateRecordTypeAssignmentRequest(@Size(max = 60) String recordType, @NotNull UUID profileId,
        Boolean defaultForProfile) {
}
