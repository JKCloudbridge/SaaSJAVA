package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The one answer to a request whose outcome must not reveal anything about an account (sign-up, password reset): it is
 * the same text whether or not an e-mail will be sent.
 *
 * @param message the text to show the person
 */
public record RequestAccepted(@NotNull String message) {

    /** The answer to a sign-up request. */
    public static final RequestAccepted SIGN_UP = new RequestAccepted(
            "If you can sign up with this address, an e-mail with the next step is on its way.");

    /** The answer to a password-reset request. */
    public static final RequestAccepted PASSWORD_RESET = new RequestAccepted(
            "If an account exists for this address, an e-mail with a reset link is on its way.");
}
