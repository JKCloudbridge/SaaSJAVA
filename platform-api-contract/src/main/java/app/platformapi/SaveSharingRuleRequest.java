package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Creates a sharing rule or changes one (Sprint 17). The object and the kind are given when the rule is created and
 * never change. The text is typed by a person, so it never prints.
 *
 * @param object the object's API name (create only)
 * @param name the name, unique among the rules of the object
 * @param kind {@code OWNER}, {@code GROUP} or {@code CRITERIA} (create only)
 * @param sourceType {@code ROLE} or {@code ROLE_AND_SUBORDINATES} for {@code OWNER}; {@code GROUP} for {@code GROUP};
 *        absent for {@code CRITERIA}
 * @param sourceId the role or group whose members own the records; absent for {@code CRITERIA}
 * @param criterion for {@code CRITERIA}: a condition on fields that are flagged as indexed; absent otherwise
 * @param targetType {@code ROLE}, {@code ROLE_AND_SUBORDINATES} or {@code GROUP}
 * @param targetId the role or group the records are shared with
 * @param accessLevel {@code READ} or {@code READ_WRITE}
 * @param active whether the rule is in force (absent: true)
 * @param version the version that was read (change only)
 */
public record SaveSharingRuleRequest(@Size(max = 60) String object, @NotBlank @Size(max = 80) String name,
        @Size(max = 20) String kind, @Size(max = 30) String sourceType, UUID sourceId, @Valid QueryFilterNode criterion,
        @NotNull @Size(max = 30) String targetType, @NotNull UUID targetId, @NotNull @Size(max = 20) String accessLevel,
        Boolean active, Long version) {

    @Override
    public String toString() {
        return "SaveSharingRuleRequest[redacted]";
    }
}
