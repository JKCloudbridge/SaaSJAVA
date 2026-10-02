package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The numbers of one licence pool of an organization (Sprint 6).
 *
 * @param licenceType the licence type key, for example {@code user}
 * @param name the licence type name for people
 * @param quantity how many licences the organization holds
 * @param assigned how many are held by members
 * @param available how many are still free
 */
public record LicencePoolView(@NotNull String licenceType, @NotNull String name, int quantity, int assigned,
        int available) {
}
