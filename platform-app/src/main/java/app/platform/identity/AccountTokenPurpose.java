package app.platform.identity;

/** What a one-time link token is for (ADR-0023). */
public enum AccountTokenPurpose {

    /** Completes a sign-up: proves the holder reads the mail of an address and lets them create the account. */
    SIGN_UP,

    /** Completes a password reset for an existing account. */
    PASSWORD_RESET
}
