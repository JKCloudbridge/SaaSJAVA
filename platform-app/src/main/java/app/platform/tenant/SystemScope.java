package app.platform.tenant;

/**
 * The few kinds of platform work that deliberately span tenants (ADR-0015). Row level security does not have a
 * general bypass: a table's policy names the scopes it admits, and the application role has no special privilege.
 *
 * <p>A scope is entered through {@link TenantContexts#callAsSystem}; it cannot be entered from inside a tenant
 * context, and while it is active there is no tenant. Adding a scope means adding a constant here, naming it in
 * the policies that admit it, and listing it in the tests that guard the policies. Each use is logged; a scope that is
 * {@linkplain #audited() audited} also leaves an audit record (Sprint 9, ADR-0054).
 */
public enum SystemScope {

    /** The outbox relay: claims due events of every tenant and applies retention (ADR-0016). */
    OUTBOX_RELAY("outbox_relay", false),

    /**
     * The identity module asking which organizations one person belongs to (the organization switcher, ADR-0027). The
     * policy of {@code membership} admits it for reading only, and the code always asks by user.
     */
    MEMBERSHIP_LOOKUP("membership_lookup", true);

    private final String settingValue;
    private final boolean audited;

    SystemScope(String settingValue, boolean audited) {
        this.settingValue = settingValue;
        this.audited = audited;
    }

    /**
     * Whether each use leaves an audit record. Not for the outbox relay, which opens its scope every second on every
     * instance: its work is audited where it has effects (the handlers write their own records).
     */
    public boolean audited() {
        return audited;
    }

    /** The value of the transaction-local system-scope setting of the database session, as policies name it. */
    public String settingValue() {
        return settingValue;
    }
}
