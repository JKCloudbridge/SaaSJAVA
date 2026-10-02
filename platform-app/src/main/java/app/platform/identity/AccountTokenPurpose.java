package app.platform.identity;

/** What a one-time link token is for (ADR-0023). */
public enum AccountTokenPurpose {

    /** Completes a sign-up: proves the holder reads the mail of an address and lets them create the account. */
    SIGN_UP,

    /** Completes a password reset for an existing account. */
    PASSWORD_RESET,

    /**
     * Accepts an invitation into an organization (Sprint 5, ADR-0028). The token resolves, on the server, to the
     * invitation and so to the organization; the person never names it.
     */
    INVITATION
}
