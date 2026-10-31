package app.platform.integration.internal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the extra headers a connector asks for (Sprint 29, ADR-0228). An adapter may name a few headers its kind of
 * system needs (an action name, an idempotency key), but never one the framework sets itself or one that could carry a
 * credential, change the destination or smuggle a second request. Pure.
 */
final class ExtraHeaders {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,63}");
    private static final int MAX = 8;
    private static final Set<String> RESERVED = Set.of("host", "content-length", "content-type", "transfer-encoding",
            "connection", "upgrade", "te", "trailer", "expect", "user-agent", "accept", "cookie", "set-cookie",
            "authorization", "proxy-authorization", "forwarded", "x-forwarded-for", "x-forwarded-host", "x-real-ip",
            "date", "range", "if-match", "if-none-match", "location", "origin", "referer");

    private ExtraHeaders() {
    }

    /** Whether every header is a plain, allowed one and there are not too many. */
    static boolean safe(Map<String, String> headers) {
        if (headers.size() > MAX) {
            return false;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!NAME.matcher(header.getKey()).matches()
                    || RESERVED.contains(header.getKey().toLowerCase(Locale.ROOT))
                    || !EgressClient.Header.printable(header.getValue())) {
                return false;
            }
        }
        return true;
    }
}
