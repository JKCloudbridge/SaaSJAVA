package app.platformapi;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Opaque cursors. A cursor wraps whatever position a query needs (typically the sort key of the last row) in
 * URL-safe text so clients treat it as a black box. It is not a secret and not signed: a forged cursor can only
 * move the caller within results they are already allowed to see, because every page request is authorized
 * again. Producers must therefore never put anything in a cursor that the caller may not know.
 */
public final class Cursors {

    /** Longest cursor the API accepts. */
    public static final int MAX_LENGTH = 512;

    private Cursors() {
    }

    /** Encodes a position into a cursor. */
    public static String encode(String position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor back into the position it wraps.
     *
     * @param cursor text received from a client
     * @return the position, or empty when the text is not a cursor this class produced
     */
    public static Optional<String> decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
