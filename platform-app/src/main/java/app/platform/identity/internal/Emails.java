package app.platform.identity.internal;

import java.util.Locale;
import java.util.Optional;

/** Normalizes and checks the address a person signs in with. */
final class Emails {

    private static final int MAX_LENGTH = 254;

    private Emails() {
    }

    /**
     * The form in which an address is stored and compared: trimmed and in lower case, so that
     * {@code User-A@Example.test} and {@code user-a@example.test} are one identity.
     *
     * @return the normalized address, or empty when the text cannot be an address (empty, too long, no single
     *         {@code @} with text on both sides, or containing white space)
     */
    static Optional<String> normalize(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String address = text.strip().toLowerCase(Locale.ROOT);
        int at = address.indexOf('@');
        boolean valid = address.length() >= 3 && address.length() <= MAX_LENGTH
                && at > 0 && at == address.lastIndexOf('@') && at < address.length() - 1
                && address.chars().noneMatch(Character::isWhitespace);
        return valid ? Optional.of(address) : Optional.empty();
    }
}
