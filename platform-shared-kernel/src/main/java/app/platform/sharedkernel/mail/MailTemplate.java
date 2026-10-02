package app.platform.sharedkernel.mail;

/**
 * The kinds of e-mail the platform sends (ADR-0024). A kind says what happened, not what the message looks like: the
 * notification module decides at send time what, if anything, goes out, and builds the text itself.
 */
public enum MailTemplate {

    /**
     * Somebody asked to sign up with this address. Sends a link that creates the account, or, when the address already
     * has an account, a message saying so, or nothing (see ADR-0023).
     */
    SIGN_UP_REQUEST,

    /** Somebody asked to reset the password of this address. Sends a link, or nothing when no active account exists. */
    PASSWORD_RESET_REQUEST,

    /** The password of the account was changed by a reset: tells the owner, so a takeover does not go unnoticed. */
    PASSWORD_CHANGED,

    /** The account was locked after repeated failed sign-ins: tells the owner and points to the reset. */
    ACCOUNT_LOCKED,

    /**
     * An organization invited this address (Sprint 5, ADR-0028). The request names the organization and the invitation
     * (facts, no secret); at send time the relay sends a link to a new account or to an existing one, or nothing when
     * the
     * invitation is no longer open or the address is already a member.
     */
    INVITATION
}
