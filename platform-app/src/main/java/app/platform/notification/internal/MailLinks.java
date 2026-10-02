package app.platform.notification.internal;

import org.springframework.stereotype.Component;

/**
 * The addresses in mails. Always on the platform host (ADR-0024): a new organization's own host refuses requests until
 * it is open, and a person may belong to several organizations, so the pages of sign-up and reset live on the platform
 * host. A token travels after a {@code #}: the part of an address after it is never sent to a server, so the token
 * cannot appear in an access log, a proxy log or a {@code Referer} header; the page reads it and sends it in the body
 * of a request.
 */
@Component
class MailLinks {

    private final String base;

    MailLinks(MailProperties properties) {
        this.base = properties.baseUrl();
    }

    String signUpComplete(String token) {
        return base + "/sign-up/complete#token=" + token;
    }

    String passwordReset(String token) {
        return base + "/reset-password#token=" + token;
    }

    String signIn() {
        return base + "/sign-in";
    }

    String forgotPassword() {
        return base + "/forgot-password";
    }
}
