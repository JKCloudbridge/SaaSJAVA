package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Switching a connection on, naming the version the administrator saw so that what is switched on is what they read.
 *
 * @param version the version read
 */
public record EnableIntegrationRequest(@NotNull @PositiveOrZero Long version) {
}
