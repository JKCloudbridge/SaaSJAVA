package app.platformapi;

/**
 * Stable machine-readable error codes. Clients branch on the code, never on the message. A code is never
 * renamed or reused for another meaning; new codes may be added, so clients must treat an unknown code like its
 * HTTP status class. {@code ErrorCodeTest} freezes this list.
 */
public enum ErrorCode {

    /** One or more fields of the request are invalid; the error lists them. */
    VALIDATION_ERROR(400, "The request is invalid."),

    /** The request could not be read, for example the body is not valid JSON. */
    MALFORMED_REQUEST(400, "The request could not be read."),

    /** No valid authentication was supplied. */
    UNAUTHENTICATED(401, "Authentication is required."),

    /** The caller is authenticated but not allowed to perform the action. */
    FORBIDDEN(403, "You are not allowed to perform this action."),

    /** The resource does not exist, or the caller may not know that it exists. */
    NOT_FOUND(404, "The requested resource was not found."),

    /** The HTTP method is not supported for this path. */
    METHOD_NOT_ALLOWED(405, "This method is not supported for this resource."),

    /** The server cannot produce a representation the client accepts. */
    NOT_ACCEPTABLE(406, "No acceptable representation is available."),

    /** The request conflicts with the current state of the resource. */
    CONFLICT(409, "The request conflicts with the current state of the resource."),

    /** The resource was changed by someone else since it was read (optimistic concurrency). */
    CONCURRENT_MODIFICATION(409, "The resource was changed by someone else. Reload it and try again."),

    /** The request body is larger than allowed. */
    PAYLOAD_TOO_LARGE(413, "The request is too large."),

    /** The content type of the request body is not supported. */
    UNSUPPORTED_MEDIA_TYPE(415, "The content type of the request is not supported."),

    /** The caller sent too many requests. */
    RATE_LIMITED(429, "Too many requests. Try again later."),

    /** An unexpected failure on the server. Nothing about the cause is disclosed. */
    INTERNAL_ERROR(500, "An unexpected error occurred."),

    /** A dependency of the service is unavailable. Retrying later may succeed. */
    SERVICE_UNAVAILABLE(503, "The service is temporarily unavailable.");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /** The HTTP status that carries this error. */
    public int httpStatus() {
        return httpStatus;
    }

    /** A safe, generic message in English. Never contains internal details. */
    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * The code for an HTTP status produced by the framework itself (routing, content negotiation, size limits).
     *
     * @param status an HTTP status code
     * @return the matching code; any other 4xx maps to {@link #MALFORMED_REQUEST}, any other status to
     *         {@link #INTERNAL_ERROR}
     */
    public static ErrorCode forHttpStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 400 && status < 500 ? MALFORMED_REQUEST : INTERNAL_ERROR;
        };
    }
}
