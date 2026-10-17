package app.platform.notification.internal;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
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
    private final String scheme;
    private final String port;
    private final String baseDomain;

    MailLinks(MailProperties properties, @Value("${platform.tenancy.base-domain}") String baseDomain) {
        this.base = properties.baseUrl();
        URI uri = URI.create(base);
        this.scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        this.port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        this.baseDomain = baseDomain.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The address of a record on its organization's own host (ADR-0138): the one mail link that is not on the platform
     * host, because the record lives in the organization. The scheme and port are the platform's. The caller passes
     * an object name and an identifier it has checked.
     */
    String record(String slug, String objectApiName, java.util.UUID recordId) {
        return scheme + "://" + slug + "." + baseDomain + port + "/records/" + objectApiName + "/" + recordId;
    }

    String signUpComplete(String token) {
        return base + "/sign-up/complete#token=" + token;
    }

    String passwordReset(String token) {
        return base + "/reset-password#token=" + token;
    }

    String invitation(String token) {
        return base + "/invitations/accept#token=" + token;
    }

    String signIn() {
        return base + "/sign-in";
    }

    String forgotPassword() {
        return base + "/forgot-password";
    }
}
