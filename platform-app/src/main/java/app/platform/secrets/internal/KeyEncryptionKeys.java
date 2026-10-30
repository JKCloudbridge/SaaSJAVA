package app.platform.secrets.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The keys that wrap the data keys of stored secrets (ADR-0212). They come from the deployment, as a file or as the
 * value of an environment variable, and never from the repository, a table or a log. There is no default and no
 * generated key: with none the application does not start (a stored secret that nobody can open is worse than a
 * refused start).
 *
 * <p>The active key wraps every new data key; the previous ones only open what they wrapped, so a key can be replaced
 * without a stop. A failure to read a key says which setting is wrong and never repeats a path, a value or a library's
 * message.
 */
final class KeyEncryptionKeys {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int KEY_BYTES = 32;

    /** One key. Prints nothing. */
    static final class Key {

        private final String id;
        private final SecretKey secret;

        private Key(String id, byte[] bytes) {
            this.id = id;
            this.secret = new SecretKeySpec(bytes, "AES");
        }

        String id() {
            return id;
        }

        SecretKey secret() {
            return secret;
        }

        @Override
        public String toString() {
            return "Key[redacted]";
        }
    }

    private final Key active;
    private final Map<String, Key> byId;

    KeyEncryptionKeys(SecretsProperties.Kek settings) {
        Map<String, Key> loaded = new LinkedHashMap<>();
        this.active = load("platform.secrets.kek.active", settings.activeId(), settings.activeKey(),
                settings.activeFile());
        loaded.put(active.id(), active);
        for (SecretsProperties.Previous previous : settings.previous()) {
            Key key = load("platform.secrets.kek.previous", previous.id(), previous.key(), previous.file());
            if (loaded.putIfAbsent(key.id(), key) != null) {
                throw new IllegalStateException("platform.secrets.kek: key identifiers must be different");
            }
        }
        this.byId = Map.copyOf(loaded);
    }

    /** The key that wraps new data keys. */
    Key active() {
        return active;
    }

    /** The key with this identifier, if the deployment still supplies it. */
    Optional<Key> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    private static Key load(String setting, String id, String value, String file) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalStateException(setting + "-id: a key identifier of 1 to 64 letters, digits, dots, "
                    + "dashes or underscores is required (the credential store cannot start without a key)");
        }
        boolean hasValue = value != null && !value.isBlank();
        boolean hasFile = file != null && !file.isBlank();
        if (hasValue == hasFile) {
            throw new IllegalStateException(setting + ": give the key either as -key or as -file, not both and not "
                    + "neither");
        }
        String text;
        try {
            text = hasValue ? value : Files.readString(Path.of(file), StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException e) {
            // Neither the path nor the library's message is repeated.
            throw new IllegalStateException(setting + "-file: the key file could not be read");
        }
        byte[] bytes = decode(text);
        if (bytes == null || bytes.length != KEY_BYTES) {
            Arrays.fill(bytes == null ? new byte[0] : bytes, (byte) 0);
            throw new IllegalStateException(setting + ": a key is 32 random bytes written in Base64");
        }
        Key key = new Key(id, bytes);
        Arrays.fill(bytes, (byte) 0);
        return key;
    }

    /** Standard or URL-safe Base64, with or without padding and surrounding white space; null when it is neither. */
    static byte[] decode(String text) {
        String compact = text.replaceAll("\\s", "");
        try {
            if (compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0) {
                return Base64.getUrlDecoder().decode(compact);
            }
            return Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
