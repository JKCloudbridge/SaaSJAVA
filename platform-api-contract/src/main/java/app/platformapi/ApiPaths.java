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

    /** Starts a sign-up: an e-mail with a link is sent when the address may sign up (Sprint 4). */
    public static final String AUTH_SIGN_UP = AUTH + "/sign-up";

    /** Completes a sign-up with the token from the mailed link: creates the account. */
    public static final String AUTH_SIGN_UP_COMPLETE = AUTH + "/sign-up/complete";

    /** Asks for a password-reset link. */
    public static final String AUTH_PASSWORD_FORGOT = AUTH_PASSWORD + "/forgot";

    /** Completes a password reset with the token from the mailed link. */
    public static final String AUTH_PASSWORD_RESET = AUTH_PASSWORD + "/reset";

    /** Organizations: a signed-in person founds one (Sprint 4). */
    public static final String ORGANIZATIONS = V1 + "/organizations";

    /** Invitations of the organization the host names: invite, list, send again, revoke (Sprint 5). */
    public static final String INVITATIONS = V1 + "/invitations";

    /** The members of the organization the host names: list, deactivate, reactivate, administrators (Sprint 5). */
    public static final String MEMBERS = V1 + "/members";

    /** Reads what an invitation link is for, before the person decides (public: the token is the proof). */
    public static final String AUTH_INVITATION_PREVIEW = AUTH + "/invitations/preview";

    /** Accepts an invitation by a person who has no account yet: chooses a name and a password. */
    public static final String AUTH_INVITATION_ACCEPT_NEW = AUTH + "/invitations/accept-new";

    /** Accepts an invitation by a signed-in person who already has an account. */
    public static final String AUTH_INVITATION_ACCEPT = AUTH + "/invitations/accept";

    /** Asks to continue in another organization of the signed-in person (Sprint 5). */
    public static final String AUTH_SWITCH = AUTH + "/switch";

    /** Completes a switch on the target host with the one-time handoff (public: the token is the proof). */
    public static final String AUTH_SWITCH_COMPLETE = AUTH_SWITCH + "/complete";

    /** The licence pools of the organization the host names: numbers per licence type (Sprint 6). */
    public static final String LICENCES = V1 + "/licences";

    /** Signs everyone else of the organization the host names out (Sprint 6). */
    public static final String ORGANIZATION_SIGN_OUT_ALL = V1 + "/organization/sign-out-all";

    /** The abilities the platform knows, for building profiles and policies (Sprint 7). */
    public static final String ABILITIES = V1 + "/abilities";

    /** The profiles of the organization the host names: list, create, change, remove, make default (Sprint 7). */
    public static final String PROFILES = V1 + "/profiles";

    /** The access policies of the organization the host names: list, create, change, remove (Sprint 7). */
    public static final String ACCESS_POLICIES = V1 + "/access-policies";

    /** The role hierarchy of the organization the host names: list, create, change, remove (Sprint 7). */
    public static final String ROLES = V1 + "/roles";

    /** The licence types a profile or access policy can use (the platform catalogue), for the setup screens. */
    public static final String LICENCE_TYPES = V1 + "/licence-types";

    /** The public groups of the organization the host names: list, create, change, remove, members, policies (S8). */
    public static final String GROUPS = V1 + "/groups";

    /** What a permission matrix can be about: the objects with their fields and the actions (Sprint 8). */
    public static final String DATA_CATALOGUE = V1 + "/data-catalogue";

    /** What the signed-in member may do with data: their own permission matrix (Sprint 8). */
    public static final String DATA_ACCESS_MINE = V1 + "/data-access/mine";

    /** The audit events of the organization the host names, for members who may view them (Sprint 9). */
    public static final String AUDIT_EVENTS = V1 + "/audit-events";

    /** The audit events of the platform, for platform administrators (Sprint 9). */
    public static final String PLATFORM_AUDIT_EVENTS = PLATFORM + "/audit-events";

    /** The object and field definitions of the organization the host names: standard and custom (Sprint 10). */
    public static final String METADATA_OBJECTS = V1 + "/metadata/objects";

    /** The field types an organization can choose from, with the settings each takes (Sprint 10). */
    public static final String METADATA_FIELD_TYPES = V1 + "/metadata/field-types";

    /** The caller leaves the organization the host names (Sprint 7). */
    public static final String ORGANIZATION_LEAVE = V1 + "/organization/leave";

    /** Requests for support access to the organization the host names: list, approve, deny, revoke (Sprint 6). */
    public static final String SUPPORT_ACCESS = V1 + "/support-access";

    /** The platform console: platform roles held by people. */
    public static final String PLATFORM_PEOPLE = PLATFORM + "/people";

    /** The platform console: organizations, their lifecycle, subscription, pools and entitlements. */
    public static final String PLATFORM_ORGANIZATIONS = PLATFORM + "/organizations";

    /** The platform console: plans. */
    public static final String PLATFORM_PLANS = PLATFORM + "/plans";

    /** The platform console: licence types. */
    public static final String PLATFORM_LICENCE_TYPES = PLATFORM + "/licence-types";

    /** The platform console: feature keys. */
    public static final String PLATFORM_FEATURES = PLATFORM + "/features";

    /** The platform console: sessions of a person (lookup and sign-out everywhere). */
    public static final String PLATFORM_SESSIONS = PLATFORM + "/sessions";

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
