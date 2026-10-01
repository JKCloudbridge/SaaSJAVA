package app.platformapi;

/** HTTP header names that are part of the API contract. */
public final class ApiHeaders {

    /**
     * Identifies one request. A client may send its own (8 to 64 characters of letters, digits, dot, underscore
     * and hyphen); otherwise the platform generates one. It is always echoed on the response and appears in
     * every log line and error body of the request.
     */
    public static final String REQUEST_ID = "X-Request-ID";

    /** Identifier of the distributed trace of the request, echoed on the response for support. */
    public static final String TRACE_ID = "X-Trace-Id";

    private ApiHeaders() {
    }
}
