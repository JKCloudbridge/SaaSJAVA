package app.platform.identity.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The 10,000 most common passwords of a public list, bundled with the application (resource
 * {@code identity/common-passwords.txt}, one lower-case password per line).
 *
 * <p>Known limit (ADR-0020): the list is dominated by short passwords, and the policy minimum is longer than most of
 * them, so for people who pick long passwords the check adds little. It also rejects a listed password with digits or
 * punctuation appended, and a listed password repeated, which are the usual first guesses. A bigger list is a later
 * task behind {@link BreachedPasswords}.
 */
@Component
final class BundledCommonPasswords implements BreachedPasswords {

    private static final String RESOURCE = "/identity/common-passwords.txt";

    private final Set<String> passwords;

    BundledCommonPasswords() {
        this(load());
    }

    private BundledCommonPasswords(Set<String> passwords) {
        this.passwords = passwords;
    }

    /** Reads the bundled file. Done before the object is built, so a failure never leaves a half-built one. */
    private static Set<String> load() {
        Set<String> loaded = new HashSet<>();
        try (InputStream in = BundledCommonPasswords.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The bundled password list is missing from the application");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    String entry = line.strip().toLowerCase(Locale.ROOT);
                    if (!entry.isEmpty()) {
                        loaded.add(entry);
                    }
                    line = reader.readLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The bundled password list could not be read", e);
        }
        return Set.copyOf(loaded);
    }

    @Override
    public boolean contains(String password) {
        String lower = password.strip().toLowerCase(Locale.ROOT);
        if (passwords.contains(lower)) {
            return true;
        }
        String trimmed = stripTrailingDigitsAndPunctuation(lower);
        if (!trimmed.isEmpty() && passwords.contains(trimmed)) {
            return true;
        }
        return isRepetitionOfListed(lower);
    }

    /** "password123!" and "password" are the same guess. */
    private static String stripTrailingDigitsAndPunctuation(String text) {
        int end = text.length();
        while (end > 0 && !Character.isLetter(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** "passwordpassword" is the listed password twice. */
    private boolean isRepetitionOfListed(String text) {
        int half = text.length() / 2;
        return text.length() % 2 == 0 && half > 0 && text.substring(0, half).equals(text.substring(half))
                && passwords.contains(text.substring(0, half));
    }

    /** The number of listed passwords (for tests). */
    int size() {
        return passwords.size();
    }
}
