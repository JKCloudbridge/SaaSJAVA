package app.platformapi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thrown by any module to fail a request with a specific error code. The message and fields are shown to the
 * client, so they must be written for the client: never put internal details, identifiers of other tenants,
 * rejected values or exception messages in them.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Map<String, List<String>> fields;
    private final long retryAfterSeconds;

    /** Fails with the code's default message. */
    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), Map.of());
    }

    /** Fails with a client-facing message. */
    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    /** Fails with a client-facing message and the invalid fields. */
    public ApiException(ErrorCode code, String message, Map<String, List<String>> fields) {
        this(code, message, fields, 0);
    }

    private ApiException(ErrorCode code, String message, Map<String, List<String>> fields, long retryAfterSeconds) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.fields = Map.copyOf(fields);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    /**
     * The caller sent too many requests: {@link ErrorCode#RATE_LIMITED}, and the response carries a
     * {@code Retry-After} header with the number of seconds to wait.
     */
    public static ApiException rateLimited(long retryAfterSeconds) {
        return new ApiException(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.defaultMessage(), Map.of(),
                retryAfterSeconds);
    }

    /**
     * A dependency is unavailable for now: {@link ErrorCode#SERVICE_UNAVAILABLE}, with a {@code Retry-After} header.
     */
    public static ApiException unavailable(long retryAfterSeconds) {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE.defaultMessage(),
                Map.of(), retryAfterSeconds);
    }

    /** A validation error for the given fields. */
    public static ApiException validation(Map<String, List<String>> fields) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), fields);
    }

    /** A validation error for one field. */
    public static ApiException validation(String field, String problem) {
        return validation(Map.of(field, List.of(problem)));
    }

    /** The resource was not found. */
    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    /** The error code of this failure. */
    public ErrorCode code() {
        return code;
    }

    /** Seconds the caller should wait before retrying (the {@code Retry-After} header), zero when none is advised. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** The invalid fields, empty when none apply. */
    public Map<String, List<String>> fields() {
        return fields;
    }
}
