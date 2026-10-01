package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Body of every failed request.
 *
 * @param error what went wrong
 */
public record ApiErrorResponse(@NotNull ApiError error) implements ApiEnvelope {
}
