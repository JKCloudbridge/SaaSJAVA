package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Successful response carrying one result.
 *
 * @param data the result
 * @param <T> the type of the result
 */
public record ApiResponse<T>(@NotNull T data) implements ApiEnvelope {

    /** Wraps a result in the envelope. */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
