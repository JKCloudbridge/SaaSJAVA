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

    /**
     * Names the major version of the public API that answered, for example {@code v1} (Sprint 30, ADR-0247). Inside a
     * version a change only adds.
     */
    public static final String API_VERSION = "API-Version";

    /**
     * The limits that apply to a request of the public API, one item for each window, in the form of the IETF draft on
     * rate limit headers: {@code "minute";q=600;w=60, "day";q=500000;w=86400} (Sprint 32, ADR-0282).
     */
    public static final String RATE_LIMIT_POLICY = "RateLimit-Policy";

    /**
     * What is left of those limits and when each window ends, for the answer that carries it: {@code
     * "minute";r=120;t=33, "day";r=499000;t=40000}, where {@code r} is what remains and {@code t} the seconds until
     * the window ends (Sprint 32, ADR-0282).
     */
    public static final String RATE_LIMIT = "RateLimit";

    /**
     * The caller's own key for a create or a bulk call of the public API (Sprint 30, ADR-0246): the same key with the
     * same request makes one record, whatever the number of attempts.
     */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** Set to {@code true} on an answer that repeats the outcome of an earlier request with the same key. */
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    /** Announces that an operation of the public API is deprecated (a date; Sprint 30, ADR-0247). */
    public static final String DEPRECATION = "Deprecation";

    /** The date after which a deprecated operation may be removed (Sprint 30, ADR-0247). */
    public static final String SUNSET = "Sunset";

    private ApiHeaders() {
    }
}
