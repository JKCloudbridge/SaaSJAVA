package app.platform.integration.internal;

import app.platform.integration.adapter.FileFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Every name and path the file flows use, made and judged in one place (Sprint 29A, ADR-0240, ADR-0241). Pure.
 *
 * <p>The rules: a path on the server is a configured directory (a closed alphabet, checked when the connection is
 * saved) joined with a <em>name</em>; a name is either made by the platform from identifiers, or accepted from the
 * other side only when it is short, plain and has the ending of the connection's format. No name is ever made from a
 * value of a
 * record, no ".." or separator can be in one, and a name that starts with a dot is never picked up, which is how a
 * partly written file of the other side (and the platform's own temporary files) is left alone.
 */
final class FilePaths {

    /** The most characters of a name picked up from the other side. */
    static final int MAX_NAME = 100;

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern CLAIMED = Pattern.compile("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12})__(.{1,100})");

    private FilePaths() {
    }

    /** Whether a name found in an inbox is one the platform may pick up for this format. */
    static boolean pickable(String name, FileFormat format) {
        if (name == null || !PLAIN.matcher(name).matches() || name.contains("..") || name.endsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.length() > format.extension().length() && lower.endsWith(format.extension());
    }

    /** The directory and the name joined with one slash. */
    static String join(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    /** The name a picked-up file has in the processing directory: the claim first, so it can be found again. */
    static String processingName(UUID claim, String original) {
        return claim + "__" + original;
    }

    /** The claim a name in the processing directory belongs to, or nothing if it is not one the platform made. */
    static Optional<UUID> claimOf(String processingName) {
        var matcher = CLAIMED.matcher(processingName);
        return matcher.matches() ? Optional.of(UUID.fromString(matcher.group(1))) : Optional.empty();
    }

    /** The original name inside a name of the processing directory. */
    static Optional<String> originalOf(String processingName) {
        var matcher = CLAIMED.matcher(processingName);
        return matcher.matches() ? Optional.of(matcher.group(2)) : Optional.empty();
    }

    /**
     * The name of the file written for one send: the delivery and the generation, so a repeat of the same send after
     * a lost answer is the same name, and a replay (a new generation) is a new one.
     */
    static String outboundName(UUID delivery, int generation, FileFormat format) {
        return "d-" + delivery + "-g" + generation + format.extension();
    }

    /** The temporary name a file is written under before it is renamed; it starts with a dot so nobody picks it up. */
    static String temporaryName(String finalName, UUID unique) {
        return "." + finalName + "." + unique + ".part";
    }

    /**
     * Identifies one file as the server shows it: what is hashed is the name, the size and the time, so the same file
     * again is the same key and a changed file is not. The name never leaves this method but as part of a hash.
     */
    static String fileKey(String name, long size, long modifiedSeconds) {
        return sha256(name + "\n" + size + "\n" + modifiedSeconds);
    }

    /** SHA-256 of the bytes, as lower case hex. */
    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
