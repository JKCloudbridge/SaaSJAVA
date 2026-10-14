package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A sharing rule (Sprint 17): it gives the records of an object to a role, a role and its subordinates, or a public
 * group.
 *
 * @param id the rule
 * @param object the object's API name
 * @param name the name chosen by the organization
 * @param kind {@code OWNER} (records owned by the members of a role), {@code GROUP} (owned by the members of a public
 *        group) or {@code CRITERIA} (records that meet a condition)
 * @param sourceType for {@code OWNER}: {@code ROLE} or {@code ROLE_AND_SUBORDINATES}; for {@code GROUP}:
 *        {@code GROUP}; absent for {@code CRITERIA}
 * @param sourceId the role or group whose members own the records, absent for {@code CRITERIA}
 * @param criterion for {@code CRITERIA}: the condition, in the model of the record query; absent otherwise
 * @param targetType whom the records are shared with: {@code ROLE}, {@code ROLE_AND_SUBORDINATES} or {@code GROUP}
 * @param targetId the role or group shared with
 * @param accessLevel {@code READ} or {@code READ_WRITE}; a rule never gives the right to delete
 * @param active whether the rule is in force
 * @param version the version, to send when changing it
 */
public record SharingRuleView(@NotNull UUID id, @NotNull String object, @NotNull String name, @NotNull String kind,
        String sourceType, UUID sourceId, QueryFilterNode criterion, @NotNull String targetType,
        @NotNull UUID targetId, @NotNull String accessLevel, boolean active, long version) {

    @Override
    public String toString() {
        return "SharingRuleView[redacted]";
    }
}
