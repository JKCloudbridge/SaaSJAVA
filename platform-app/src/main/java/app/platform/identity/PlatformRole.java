package app.platform.identity;

/**
 * A role a person holds on the platform itself, separate from anything inside an organization (ADR-0030). A platform
 * role grants abilities on the platform's console; it gives no authority inside any organization, which comes only from
 * an active membership there.
 */
public enum PlatformRole {

    /** Organizations and their lifecycle, provisioning, plans, pools, entitlements and platform roles. */
    PLATFORM_ADMIN,

    /**
     * Reads the state of an organization (never its members or business data), asks for support access, resends a
     * first-administrator invitation, signs a user out.
     */
    PLATFORM_SUPPORT,

    /** Plans, subscriptions and trials; read-only on lifecycle. */
    PLATFORM_BILLING
}
