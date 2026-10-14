package app.platform.data.internal;

import app.platformapi.ApiException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * What a file may be called and be (Sprint 17, ADR-0120): the rules that keep a hostile name or a hostile file from
 * doing harm later, written as pure functions so they can be tested without a store or a database.
 *
 * <p>The <strong>name</strong> a person typed is data, never a path: only its last part is kept, anything that could
 * break a header, a file system or a log is replaced, and it is bounded. The <strong>type</strong> is decided by the
 * server: the type the client declares is accepted only when it has the shape of a type, is not on the denied list
 * and (when the deployment names an allowed list) is on it; anything else is stored and served as
 * {@code application/octet-stream}. A name that ends in a denied extension is refused whatever the declared type.
 * The first bytes are looked at too: a program, a script with an interpreter line or a page a browser would run is
 * refused whatever the name and the declared type say.
 */
@Component
final class FilePolicy {

    static final String DEFAULT_TYPE = "application/octet-stream";

    /** How many bytes at the start of a file are looked at. */
    static final int HEAD_BYTES = 512;

    private static final int MAX_NAME = 150;
    private static final Pattern TYPE = Pattern.compile(
            "^[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}$");
    private static final Pattern UNSAFE_NAME = Pattern.compile("[\\p{Cntrl}\\p{Cf}\\\\/:*?\"<>|]+");

    private final FileProperties properties;

    FilePolicy(FileProperties properties) {
        this.properties = properties;
    }

    /**
     * A name and a type that passed the rules.
     *
     * @param name the cleaned name, to be shown and offered for download
     * @param contentType the type the server decided
     */
    record Checked(String name, String contentType) {

        /** Prints nothing: the name is typed text. */
        @Override
        public String toString() {
            return "Checked[redacted]";
        }
    }

    /**
     * Checks a name and a declared type.
     *
     * @throws ApiException {@code VALIDATION_ERROR} at {@code name} or {@code contentType}
     */
    Checked check(String rawName, String declaredType) {
        String name = cleanName(rawName);
        String extension = extensionOf(name);
        if (properties.deniedExtensions().contains(extension)) {
            throw ApiException.validation("name", "This kind of file is not accepted.");
        }
        String type = normalizeType(declaredType);
        if (properties.deniedTypes().contains(type)) {
            throw ApiException.validation("contentType", "This kind of file is not accepted.");
        }
        if (!properties.allowedTypes().isEmpty() && !properties.allowedTypes().contains(type)) {
            throw ApiException.validation("contentType", "This kind of file is not accepted.");
        }
        return new Checked(name, type);
    }

    /** The name with only its last part, safe characters, a bound on its length, and never empty. */
    static String cleanName(String raw) {
        String text = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        String last = slash >= 0 ? text.substring(slash + 1) : text;
        String safe = UNSAFE_NAME.matcher(last).replaceAll("_").strip();
        while (safe.startsWith(".")) {
            safe = safe.substring(1);
        }
        if (safe.isEmpty()) {
            throw ApiException.validation("name", "Give the file a name.");
        }
        return safe.length() <= MAX_NAME ? safe : truncated(safe);
    }

    /** The type in lower case without parameters when it has the shape of a type, and the default type otherwise. */
    static String normalizeType(String declared) {
        if (declared == null) {
            return DEFAULT_TYPE;
        }
        int semicolon = declared.indexOf(';');
        String bare = (semicolon >= 0 ? declared.substring(0, semicolon) : declared).strip().toLowerCase(Locale.ROOT);
        return TYPE.matcher(bare).matches() ? bare : DEFAULT_TYPE;
    }

    /** The part of the name after its last dot, in lower case, or empty. */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses a file whose first bytes are a program, a script with an interpreter line, or a page a browser would
     * run, whatever it is called and whatever type was declared.
     *
     * @throws ApiException {@code VALIDATION_ERROR} at {@code file}
     */
    static void refuseDangerousStart(byte[] head, int length) {
        boolean program = length >= 2 && head[0] == 'M' && head[1] == 'Z';
        boolean linked = length >= 4 && head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
        boolean script = length >= 2 && head[0] == '#' && head[1] == '!';
        if (program || linked || script || (length >= 4 && isMachO(head))) {
            throw refused();
        }
        String start = new String(head, 0, Math.min(length, 64), StandardCharsets.ISO_8859_1).stripLeading()
                .toLowerCase(Locale.ROOT);
        if (start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("<script")
                || start.startsWith("<svg")) {
            throw refused();
        }
    }

    private static boolean isMachO(byte[] head) {
        int magic = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        return magic == 0xCAFEBABE || magic == 0xFEEDFACE || magic == 0xFEEDFACF || magic == 0xCEFAEDFE
                || magic == 0xCFFAEDFE;
    }

    private static ApiException refused() {
        return ApiException.validation("file", "This kind of file is not accepted.");
    }

    private static String truncated(String name) {
        String extension = extensionOf(name);
        if (extension.isEmpty() || extension.length() > 10) {
            return name.substring(0, MAX_NAME);
        }
        int keep = MAX_NAME - extension.length() - 1;
        return name.substring(0, keep) + "." + extension;
    }
}
