package app.platform.notification.internal;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the notification module (ADR-0024). The SMTP server itself is configured through the framework's own
 * {@code spring.mail.*} settings; the credentials of a real server come from the environment, never from a file.
 *
 * @param enabled whether this instance runs the relay (every instance may; they coordinate through the database)
 * @param senderAddress the address mails come from; no default in a deployment
 * @param publicBaseUrl where the platform's pages live (scheme, host and port, no path): the links in mails point
 *        here, always at the platform host, never at an organization host
 * @param pollInterval how long the relay waits when the queue is empty
 * @param batchSize how many mails one poll claims
 * @param lease how long a claimed mail is invisible to other instances; longer than a batch takes
 * @param maxAttempts how many times a mail is tried before it becomes a dead letter
 * @param backoffInitial the wait after the first failed attempt (doubles each time)
 * @param backoffMax the longest wait between attempts
 * @param maxAge a mail still unsent after this long is set aside unsent: its link would be stale anyway
 * @param sentRetention how long finished mails (sent or suppressed) stay in the table
 * @param deadRetention how long dead letters stay for a person to look at
 */
@ConfigurationProperties("platform.notification")
record MailProperties(
        @DefaultValue("true") boolean enabled,
        String senderAddress,
        String publicBaseUrl,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("10") int batchSize,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("30s") Duration backoffInitial,
        @DefaultValue("15m") Duration backoffMax,
        @DefaultValue("24h") Duration maxAge,
        @DefaultValue("7d") Duration sentRetention,
        @DefaultValue("30d") Duration deadRetention) {

    MailProperties {
        if (senderAddress == null || !senderAddress.matches("[^@\\s<>\"]+@[^@\\s<>\"]+")) {
            throw new IllegalArgumentException("platform.notification.sender-address must be an e-mail address");
        }
        URI base = parse(publicBaseUrl);
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        boolean localHttp = scheme.equals("http") && (host.equals("localhost") || host.endsWith(".localhost")
                || host.equals("127.0.0.1") || host.endsWith(".test"));
        if (host.isEmpty() || !(https || localHttp) || (base.getPath() != null && !base.getPath().isEmpty())
                || base.getQuery() != null || base.getFragment() != null || base.getUserInfo() != null) {
            throw new IllegalArgumentException("platform.notification.public-base-url must be an https address "
                    + "without a path (http is accepted only for a developer machine)");
        }
        if (batchSize < 1 || maxAttempts < 1 || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("platform.notification: invalid values");
        }
    }

    private static URI parse(String text) {
        try {
            return URI.create(text == null ? "" : text.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("platform.notification.public-base-url is not an address", e);
        }
    }

    /** The platform's base address without a trailing slash. */
    String baseUrl() {
        return publicBaseUrl.strip();
    }
}
