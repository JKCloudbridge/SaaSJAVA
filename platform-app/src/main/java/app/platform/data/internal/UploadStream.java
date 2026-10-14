package app.platform.data.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The bytes of an upload as they pass to the object store (Sprint 17, ADR-0120): counted, hashed with SHA-256 and cut
 * at the announced length. The size of a file is therefore judged by the bytes that really passed, not by the
 * length the client announced, and the application's own checksum is made while streaming (nothing is kept in memory).
 *
 * <p>It never returns more than the announced length, so a body that is longer than announced cannot be stored; a body
 * that ends before the announced length is remembered ({@link #endedEarly}), so the failure is told apart from a
 * failure of the store.
 */
final class UploadStream extends InputStream {

    private final InputStream source;
    private final long announced;
    private final MessageDigest digest;
    private long count;
    private boolean endedEarly;

    UploadStream(InputStream source, long announced, MessageDigest digest) {
        this.source = source;
        this.announced = announced;
        this.digest = digest;
    }

    @Override
    public int read() throws IOException {
        if (count >= announced) {
            return -1;
        }
        int value = source.read();
        if (value < 0) {
            endedEarly = true;
            return -1;
        }
        digest.update((byte) value);
        count++;
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        long remaining = announced - count;
        if (remaining <= 0) {
            return -1;
        }
        int read = source.read(buffer, offset, (int) Math.min(length, remaining));
        if (read < 0) {
            endedEarly = true;
            return -1;
        }
        digest.update(buffer, offset, read);
        count += read;
        return read;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    /** How many bytes have passed. */
    long count() {
        return count;
    }

    /** Whether the body ended before the announced length. */
    boolean endedEarly() {
        return endedEarly;
    }

    /** The SHA-256 of the bytes that passed, in lower case hexadecimal. */
    String sha256() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
