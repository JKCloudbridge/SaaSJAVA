package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * A public group of the organization (Sprint 8): a named set of people and of other groups, which can be given access
 * policies. Whoever is in the group, directly or through nested groups, holds what its access policies give.
 *
 * @param id the group
 * @param name the name chosen by the organization
 * @param description what the group is for
 * @param people the members of the group who are people (membership identifiers), not counting nested groups
 * @param groups the groups that are members of this group
 * @param policies the access policies given to the group
 */
public record GroupView(@NotNull UUID id, @NotNull String name, @NotNull String description,
        @NotNull List<UUID> people, @NotNull List<GroupRef> groups, @NotNull List<AccessPolicyRef> policies) {

    public GroupView {
        people = people == null ? List.of() : List.copyOf(people);
        groups = groups == null ? List.of() : List.copyOf(groups);
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    @Override
    public String toString() {
        return "GroupView[redacted]";
    }
}
