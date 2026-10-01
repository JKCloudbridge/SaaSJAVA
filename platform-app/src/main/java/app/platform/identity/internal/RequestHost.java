package app.platform.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/**
 * The host name a request was addressed to, in the same way the tenant module reads it (ADR-0017): the {@code Host}
 * header, or the first entry of {@code X-Forwarded-Host} when the deployment says a trusted proxy overwrites it. Used
 * to check that a sign-in redirects only back to the host the person is on, and to build that address.
 */
final class RequestHost {

    private static final String FORWARDED_HOST = "X-Forwarded-Host";

    private RequestHost() {
    }

    /** The host with its port as the client addressed it, in lower case; empty text when there is none. */
    static String authority(HttpServletRequest request, boolean trustForwardedHost) {
        String value = null;
        if (trustForwardedHost) {
            String forwarded = request.getHeader(FORWARDED_HOST);
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                value = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).strip();
            }
        }
        if (value == null) {
            value = request.getHeader("Host");
        }
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** The host without a port. */
    static String name(String authority) {
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            return end > 0 ? authority.substring(0, end + 1) : authority;
        }
        int colon = authority.lastIndexOf(':');
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }

    /** The host name of the request, without a port. */
    static String name(HttpServletRequest request, boolean trustForwardedHost) {
        String authority = authority(request, trustForwardedHost);
        return authority.isEmpty() ? request.getServerName().toLowerCase(Locale.ROOT) : name(authority);
    }
}
