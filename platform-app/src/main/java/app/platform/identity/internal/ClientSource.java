package app.platform.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The network source of a request, for rate limits and audit records (story S3-SEC-09).
 *
 * <p>The source is the connection's address, unless the deployment says a trusted proxy overwrites
 * {@code X-Forwarded-For} (then its first entry). An IPv6 address is reduced to its /64 prefix: one subscriber normally
 * holds a whole /64, so counting single addresses would let an attacker rotate through billions of them.
 */
final class ClientSource {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private ClientSource() {
    }

    static String of(HttpServletRequest request, boolean trustForwardedFor) {
        String candidate = request.getRemoteAddr();
        if (trustForwardedFor) {
            String forwarded = request.getHeader(FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                candidate = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).strip();
            }
        }
        return normalize(candidate);
    }

    /** A literal address in a comparable form; anything else becomes {@code unknown} (one shared bucket). */
    static String normalize(String candidate) {
        if (candidate == null || !LITERAL.matcher(candidate).matches()) {
            return "unknown";
        }
        try {
            // A literal is parsed without any name lookup because the pattern admits only address characters.
            byte[] bytes = InetAddress.getByName(candidate).getAddress();
            if (bytes.length == 16) {
                return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64",
                        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]);
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
