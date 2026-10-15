package app.platform.sharedkernel.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The place the bytes of files are kept (Sprint 17, ADR-0120; a shared contract since Sprint 18, ADR-0127, because the
 * scripts of custom pages are kept in it as well): a bucket of an S3-compatible object store, reached only
 * by the application. Nothing is public and no address of the store is ever handed to a browser: the application
 * authorizes every request for a file and streams the bytes itself.
 *
 * <p>The keys are made by the application ({@code files/<organization>/<file>}) and never contain a name a person
 * typed. A failure of the store is an {@link ObjectStoreException}, which carries no message: what a library says about
 * a failed request can name keys and hosts and never goes to a response or a log.
 */
public interface ObjectStore {

    /** Bytes read from the store, with their length. It must be closed (a try block does it). */
    record Stored(InputStream bytes, long length) implements AutoCloseable {

        /** Closes the stream. */
        @Override
        public void close() {
            try {
                bytes.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Whether the store is set up at all (a deployment without one answers files with a clean 503). */
    boolean configured();

    /** Whether the store answers (the health check). */
    boolean reachable();

    /** Makes the bucket when it does not exist (a developer machine; a deployment provisions its own). */
    void ensureBucket();

    /**
     * Stores exactly {@code length} bytes read from the stream under the key.
     *
     * @throws ObjectStoreException when the store fails or the stream ends before {@code length} bytes
     */
    void put(String key, InputStream bytes, long length, String contentType);

    /** The bytes under the key, or null when there are none. */
    default Stored get(String key) {
        return get(key, 0);
    }

    /** The bytes under the key from a byte offset on (a ranged read), or null when there are none. */
    Stored get(String key, long fromOffset);

    /** Removes the bytes under the key; nothing happens when there are none (the removal is repeatable). */
    void delete(String key);
}
