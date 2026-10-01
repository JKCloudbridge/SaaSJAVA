package app.platform.identity;

import java.util.Locale;

/**
 * Why an attempt was refused. Recorded in the audit trail with the failure; never shown to the caller (ADR-0005,
 * story S3-SEC-11).
 */
public enum RejectionReason {

    /** No user has this address. */
    UNKNOWN_ACCOUNT,

    /** The password is wrong. */
    WRONG_PASSWORD,

    /** The account is locked after repeated failures; even the right password is refused. */
    LOCKED,

    /** The account is suspended or deactivated. */
    DISABLED,

    /** The account has not completed activation (invited, address not verified, or no password yet). */
    NOT_VERIFIED,

    /** The provider could not decide in time (for example hashing capacity is exhausted). */
    UNAVAILABLE;

    /** The lower-case code used in audit records. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
