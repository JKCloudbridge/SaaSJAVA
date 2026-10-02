package app.platform.sharedkernel.support;

/**
 * A platform person asked for an organization's data without an active support-access grant (ADR-0035). The message
 * never says why (no grant, expired, revoked, denied): the answer reveals nothing about the organization's decisions.
 */
public final class SupportAccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SupportAccessDeniedException() {
        super("Support access to this organization is not granted.");
    }
}
