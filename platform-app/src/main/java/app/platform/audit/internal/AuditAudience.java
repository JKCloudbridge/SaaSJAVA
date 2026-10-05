package app.platform.audit.internal;

/**
 * Who may read an audit record (ADR-0054): the organization it is about, the platform, or both. The rules are by the
 * kind of record and by whether it is about an organization, and mirror the backfill of migration V029. The database
 * enforces them when records are read (a row level security policy on the two flags), so a mistake in a query cannot
 * show a row to the wrong reader.
 *
 * <p>A kind that no rule names is readable only by the side it happened on (the organization when it is about one,
 * the platform otherwise): a new kind is hidden from the wrong side until somebody decides.
 *
 * @param organization whether the organization the record is about may read it
 * @param platform whether platform people may read it
 */
record AuditAudience(boolean organization, boolean platform) {

    /** The audience of a record of this kind, given whether it is about an organization. */
    static AuditAudience of(String type, boolean aboutOrganization) {
        if (type.startsWith("platform.support_access.")) {
            // An organization sees that support asked for access and used it (ADR-0035).
            return new AuditAudience(aboutOrganization, true);
        }
        if (type.startsWith("platform.") || type.startsWith("system.") || type.startsWith("audit.")) {
            return new AuditAudience(false, true);
        }
        if (type.startsWith("support_access.") || type.startsWith("tenant.") || type.startsWith("retention.")) {
            return new AuditAudience(aboutOrganization, true);
        }
        if (type.startsWith("access.") || type.startsWith("membership.") || type.startsWith("organization.")
                || type.startsWith("metadata.")) {
            return new AuditAudience(aboutOrganization, false);
        }
        return new AuditAudience(aboutOrganization, !aboutOrganization);
    }

    /**
     * Whether the internal reason code of a record of this kind may be shown to a reader. Never for sign-in and
     * platform records: the true reason of a refused sign-in is kept from the person it refused (ADR-0005), and an
     * organization's administrators are not that exception.
     */
    static boolean reasonVisible(String type) {
        return type.startsWith("access.") || type.startsWith("membership.") || type.startsWith("support_access.")
                || type.startsWith("retention.") || type.startsWith("tenant.") || type.startsWith("metadata.");
    }
}
