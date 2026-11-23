package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What the organization may ask of the public API and what it has used (Sprint 32, ADR-0280). The organization's own
 * limits cover every caller together; each connected application also has a limit of its own, so that one application
 * cannot use up the others' share. A person's own token counts against the member and the organization.
 *
 * @param enabled whether limits are in force; false when the platform switched them off
 * @param organization the organization's limits and use
 * @param applications the connected applications and their use, by name
 * @param words the plain words that say what counts and what to do when a limit is reached
 */
public record UsageView(boolean enabled, @NotNull List<LimitUsageView> organization,
        @NotNull List<ApplicationUsageView> applications, @NotNull String words) {

    public UsageView {
        organization = List.copyOf(organization);
        applications = List.copyOf(applications);
    }
}
