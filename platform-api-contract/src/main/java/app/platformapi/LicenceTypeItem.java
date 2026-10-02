package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A licence type of the catalogue (Sprint 8).
 *
 * @param key the stable key
 * @param name the name for people
 * @param kind {@code SEAT}: the right to occupy a seat, the only kind a profile can belong to; {@code ADD_ON}: sold on
 *        top, for example the licence of a standard access policy
 */
public record LicenceTypeItem(@NotNull String key, @NotNull String name, @NotNull String kind) {
}
