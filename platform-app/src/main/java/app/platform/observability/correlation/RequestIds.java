package app.platform.observability.correlation;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Request ID rules. A client may supply its own ID; it is accepted only when it is short and made of harmless
 * characters, because the value ends up in log lines and response headers (no line breaks, no markup). Generated
 * IDs are {@code req_} plus 22 characters: a 10-character time prefix, so IDs sort by time, and 12 random ones.
 * The total of 26 characters is deliberate: together with a trace ID it fits the 63 characters of a PostgreSQL
 * application name (see {@link TransactionCorrelationListener}).
 */
final class RequestIds {

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,63}");
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIME_CHARS = 10;
    private static final int RANDOM_CHARS = 12;
    private static final RandomGenerator RANDOM = new SecureRandom();

    private RequestIds() {
    }

    /** True when a client-supplied ID may be adopted as is. */
    static boolean isAcceptable(String candidate) {
        return candidate != null && ACCEPTED.matcher(candidate).matches();
    }

    /** A new request ID for the given time. */
    static String generate(long epochMillis) {
        return generate(epochMillis, RANDOM);
    }

    static String generate(long epochMillis, RandomGenerator random) {
        char[] out = new char[TIME_CHARS + RANDOM_CHARS];
        long time = epochMillis;
        for (int i = TIME_CHARS - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        for (int i = TIME_CHARS; i < out.length; i++) {
            out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return "req_" + new String(out);
    }
}
