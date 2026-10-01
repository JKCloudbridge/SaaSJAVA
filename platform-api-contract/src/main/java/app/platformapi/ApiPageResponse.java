package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Successful response carrying one page of a collection.
 *
 * @param data the items of this page, in the order of the request
 * @param pagination how to fetch the next page
 * @param <T> the type of an item
 */
public record ApiPageResponse<T>(@NotNull List<T> data, @NotNull Pagination pagination) implements ApiEnvelope {

    public ApiPageResponse {
        data = List.copyOf(data);
    }

    /** Wraps one page in the envelope. */
    public static <T> ApiPageResponse<T> of(List<T> data, Pagination pagination) {
        return new ApiPageResponse<>(data, pagination);
    }
}
