package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * The single error model of the API. Contains only what a client needs: a stable code, a safe message, the
 * invalid fields (for validation errors) and the identifiers support needs to find the request in the logs.
 * It never contains stack traces, class names, SQL, file paths or the rejected values.
 *
 * @param code stable machine-readable code
 * @param message safe human-readable message
 * @param fields for {@link ErrorCode#VALIDATION_ERROR}: field name to the problems found; absent otherwise
 * @param requestId the request ID of the failed request
 * @param traceId the trace ID of the failed request; absent when tracing is off
 */
public record ApiError(
        @NotNull ErrorCode code,
        @NotNull String message,
        Map<String, List<String>> fields,
        @NotNull String requestId,
        String traceId) {

    public ApiError {
        fields = fields == null || fields.isEmpty() ? null : Map.copyOf(fields);
    }
}
