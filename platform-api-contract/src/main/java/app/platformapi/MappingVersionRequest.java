package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Publishing a draft, naming the version the administrator saw so that what is made live is what they read.
 *
 * @param version the version of the draft read
 */
public record MappingVersionRequest(@NotNull @PositiveOrZero Long version) {
}
