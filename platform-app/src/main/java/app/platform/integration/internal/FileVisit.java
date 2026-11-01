package app.platform.integration.internal;

import java.util.List;
import java.util.Optional;

/**
 * One visit to a file server: a session opened, a few operations, the session closed (Sprint 29A, ADR-0237). It is the
 * only view the sender, the poller and the tester have of the server, so every rule about the other side is kept in the
 * transport that implements it and the work above can be tested against a plain fake.
 *
 * <p>Every operation either succeeds or throws {@link TransportFailure} with a stable reason. A path is absolute on the
 * server and was built from settings that passed a closed alphabet; no operation follows a symbolic link or writes over
 * a file that exists.
 */
interface FileVisit extends AutoCloseable {

    /**
     * What the server says about a name.
     *
     * @param name the last part of the path
     * @param size the size in bytes
     * @param modifiedSeconds the time of the last change, in seconds since 1970
     * @param regular whether it is an ordinary file (not a directory, a link or anything else)
     */
    record Entry(String name, long size, long modifiedSeconds, boolean regular) {
    }

    /**
     * The entries directly in a directory, without {@code .} and {@code ..}, at most {@code maxEntries} of them: a
     * directory with millions of names is read as far as the bound and no further.
     */
    List<Entry> list(String directory, int maxEntries);

    /** What is at the path, without following a link, or nothing when there is nothing. */
    Optional<Entry> stat(String path);

    /** Moves a file. Refuses (a failure) when the target exists, so a move can never overwrite. */
    void rename(String from, String to);

    /** The whole content of a file, at most {@code maxBytes}; a longer file is refused before it is read to the end. */
    byte[] read(String path, int maxBytes);

    /** Creates a file with this content; refuses when the path exists. */
    void create(String path, byte[] content);

    @Override
    void close();
}
