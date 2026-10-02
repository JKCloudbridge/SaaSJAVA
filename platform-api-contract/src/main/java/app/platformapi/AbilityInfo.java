package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One thing a person may be allowed to do in an organization (Sprint 7). The list of abilities is fixed by the
 * platform; organizations put abilities into profiles, access policies and individual grants.
 *
 * @param key the stable key, for example {@code members.invite}
 * @param name the name for people
 * @param description what the ability allows, in plain words
 */
public record AbilityInfo(@NotNull String key, @NotNull String name, @NotNull String description) {
}
