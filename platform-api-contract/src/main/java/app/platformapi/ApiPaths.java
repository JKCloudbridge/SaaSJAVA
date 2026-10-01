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

    /** Sign-in, sign-out and the signed-in user (Sprint 3). */
    public static final String AUTH = V1 + "/auth";

    /** Reads the cookie token that protects state-changing browser requests against forgery (sets the cookie). */
    public static final String AUTH_CSRF = AUTH + "/csrf";

    /** Checks an address and a password and starts the sign-in of the browser. */
    public static final String AUTH_SIGN_IN = AUTH + "/sign-in";

    /** Browser navigation: begins the authorization-code step of the sign-in. Not in the OpenAPI document. */
    public static final String AUTH_START = AUTH + "/start";

    /** Browser navigation: the redirect target that completes the sign-in and sets the session cookies. */
    public static final String AUTH_CALLBACK = AUTH + "/callback";

    /** Replaces the session tokens using the refresh cookie. */
    public static final String AUTH_REFRESH = AUTH + "/refresh";

    /** Ends the current sign-in. */
    public static final String AUTH_SIGN_OUT = AUTH + "/sign-out";

    /** Ends every sign-in of the user, on every device. */
    public static final String AUTH_SIGN_OUT_ALL = AUTH + "/sign-out-all";

    /** Changes the password of the signed-in user. */
    public static final String AUTH_PASSWORD = AUTH + "/password";

    /** Who the caller is. */
    public static final String AUTH_ME = AUTH + "/me";

    /** The authorization server's endpoints (Sprint 3). */
    public static final String OAUTH2 = V1 + "/oauth2";

    /** Authorization endpoint (authorization code with PKCE). */
    public static final String OAUTH2_AUTHORIZE = OAUTH2 + "/authorize";

    /** Token endpoint. */
    public static final String OAUTH2_TOKEN = OAUTH2 + "/token";

    /** Token revocation endpoint. */
    public static final String OAUTH2_REVOKE = OAUTH2 + "/revoke";

    /** The public keys that verify ID tokens. */
    public static final String OAUTH2_JWKS = OAUTH2 + "/jwks";

    private ApiPaths() {
    }
}
