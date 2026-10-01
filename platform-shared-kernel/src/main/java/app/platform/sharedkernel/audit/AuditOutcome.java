package app.platform.sharedkernel.audit;

/** How the audited action ended. */
public enum AuditOutcome {

    /** The action happened. */
    SUCCESS,

    /** The action was attempted and did not happen (wrong password, reuse of a token, ...). */
    FAILURE,

    /** The action was refused by a protection (a lock, a rate limit, a binding to another host). */
    DENIED
}
