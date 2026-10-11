package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One function of the expression language (Sprint 13): the setup screens show this list, so that no screen holds a list
 * of its own.
 *
 * @param name the name in capitals
 * @param signature how a call is written, for example {@code ROUND(number, places)}
 * @param description what it does, in plain words
 * @param rulesOnly whether only a validation rule may use it
 */
public record FunctionView(@NotNull String name, @NotNull String signature, @NotNull String description,
        @NotNull Boolean rulesOnly) {
}
