package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * What an approver rule can name, by name (Sprint 24, ADR-0146): the profiles, the roles and the public groups of the
 * organization, so the builder can offer them. For members who manage approvals; a person is found with the search of
 * people instead, because there may be many.
 *
 * @param profiles the profiles
 * @param roles the roles
 * @param groups the public groups
 */
public record ApprovalChoicesView(@NotNull List<Named> profiles, @NotNull List<Named> roles,
        @NotNull List<Named> groups) {

    /** Copies the lists. */
    public ApprovalChoicesView {
        profiles = List.copyOf(profiles);
        roles = List.copyOf(roles);
        groups = List.copyOf(groups);
    }

    /**
     * One choice.
     *
     * @param id the identifier a rule carries
     * @param name its name
     */
    public record Named(@NotNull UUID id, @NotNull String name) {
    }
}
