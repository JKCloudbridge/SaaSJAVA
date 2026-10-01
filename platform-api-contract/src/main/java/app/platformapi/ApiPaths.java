package app.platformapi;

/** Path conventions of the published HTTP API (ADR-0011). */
public final class ApiPaths {

    /** Prefix of every versioned platform endpoint. A breaking change would introduce {@code /api/v2}. */
    public static final String V1 = "/api/v1";

    /** The generated OpenAPI document of version 1. */
    public static final String OPENAPI = V1 + "/openapi";

    /** Platform-level information that needs no tenant. */
    public static final String PLATFORM = V1 + "/platform";

    /** Reports that the API is up and can reach its database. */
    public static final String PLATFORM_STATUS = PLATFORM + "/status";

    /** Information about the organization (tenant) that the host name addresses. */
    public static final String TENANT = V1 + "/tenant";

    /** The organization the request's host name resolves to. */
    public static final String TENANT_CURRENT = TENANT + "/current";

    private ApiPaths() {
    }
}
