package app.platform.testsupport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * A real file server for the tests of file flows (Sprint 29A): a secure shell server with the file transfer subsystem,
 * in this process, on a port of its own, serving a directory of its own. The platform meets it exactly as it would meet
 * any server: over a socket, with a key to check and a credential to show.
 *
 * <p>It can behave badly on demand, which is what the tests of the failure paths need: {@link #down} (the port
 * refuses), {@link #hang} (the port accepts and says nothing), {@link #wrongHostKey} (a different key from the one
 * pinned), {@link #rejectLogins}, {@link #denyWrites}, {@link #diskFull} and {@link #dropWhileWriting} (the connection
 * is cut in
 * the middle of a file). Everything it knows about the other side is in the directory, so a test reads and writes files
 * with the ordinary file API and sees what the platform sees.
 */
public final class TestFileServer implements AutoCloseable {

    /** The user name every test connection uses. */
    public static final String USER = "user-a";
    /** The password every test connection uses. */
    public static final String PASSWORD = "password-" + java.util.UUID.randomUUID();

    private final Path root;
    private final Path keyFile;
    private Path backupKey;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final List<Socket> held = new CopyOnWriteArrayList<>();
    private final List<PublicKey> allowedKeys = new CopyOnWriteArrayList<>();
    private volatile boolean rejectLogins;
    private volatile boolean denyWrites;
    private volatile boolean diskFull;
    private volatile boolean dropWhileWriting;
    private volatile int port;
    private volatile SshServer server;
    private volatile SimpleGeneratorHostKeyProvider hostKeys;
    private volatile ServerSocket blackhole;

    private TestFileServer(Path root, Path keyFile) {
        this.root = root;
        this.keyFile = keyFile;
    }

    /** Starts a server on a free port with an empty directory. */
    public static TestFileServer start() throws IOException {
        Path root = Files.createTempDirectory("file-server-");
        Path keyFile = Files.createTempFile("file-server-key-", ".ser");
        TestFileServer server = new TestFileServer(root, keyFile);
        server.listen(0);
        return server;
    }

    private void listen(int wanted) throws IOException {
        SshServer fresh = SshServer.setUpDefaultServer();
        fresh.setHost(InetAddress.getLoopbackAddress().getHostAddress());
        fresh.setPort(wanted);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(keyFile);
        keys.setAlgorithm("EC");
        hostKeys = keys;
        fresh.setKeyPairProvider(keys);
        fresh.setPasswordAuthenticator((user, password, session) -> {
            logins.incrementAndGet();
            return !rejectLogins && USER.equals(user) && PASSWORD.equals(password);
        });
        fresh.setPublickeyAuthenticator((user, key, session) -> {
            logins.incrementAndGet();
            return !rejectLogins && USER.equals(user) && allowedKeys.stream().anyMatch(allowed ->
                    KeyUtils.compareKeys(allowed, key));
        });
        fresh.setFileSystemFactory(new VirtualFileSystemFactory(root));
        SftpSubsystemFactory files = new SftpSubsystemFactory.Builder().build();
        files.addSftpEventListener(new Misbehaviour());
        fresh.setSubsystemFactories(List.of(files));
        fresh.start();
        this.server = fresh;
        this.port = fresh.getPort();
    }

    // ---- where it is and who it is ----

    /** The address of the server as a connection names it. */
    public String address() {
        return "sftp://127.0.0.1:" + port;
    }

    public int port() {
        return port;
    }

    /** The directory the server serves; the paths a connection uses are below it. */
    public Path root() {
        return root;
    }

    /** The fingerprint of the key the server shows, as the usual tools print it. */
    public String fingerprint() {
        KeyPair pair = hostKeys.loadKeys(null).iterator().next();
        return KeyUtils.getFingerPrint(pair.getPublic());
    }

    /** A fingerprint no server shows: for a connection pinned to a key that is not the server's. */
    public static String strangerFingerprint() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return KeyUtils.getFingerPrint(generator.generateKeyPair().getPublic());
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** How many times someone tried to sign in. */
    public int loginAttempts() {
        return logins.get();
    }

    /** How many bytes-carrying writes the server received. */
    public int writeCount() {
        return writes.get();
    }

    /** Lets a user with this private key sign in; returns the key in the text form the platform stores. */
    public String allowGeneratedKey() throws java.security.GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        allowedKeys.add(pair.getPublic());
        String body = Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----";
    }

    // ---- how it misbehaves ----

    /** The port refuses connections. */
    public void down() throws IOException {
        stopServer();
        closeBlackhole();
    }

    /** The port accepts connections and says nothing, ever. */
    public void hang() throws IOException {
        stopServer();
        closeBlackhole();
        ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        blackhole = socket;
        Thread.ofVirtual().start(() -> {
            try {
                while (!socket.isClosed()) {
                    held.add(socket.accept());
                }
            } catch (IOException closed) {
                // The black hole was closed: that is the end of it.
            }
        });
    }

    /** Back to normal on the same port with the same key. */
    public void up() throws IOException {
        closeBlackhole();
        stopServer();
        rejectLogins = false;
        denyWrites = false;
        diskFull = false;
        dropWhileWriting = false;
        if (backupKey != null) {
            Files.copy(backupKey, keyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        listen(port);
    }

    /** Back on the same port with another key: what a replaced or impersonated server looks like. */
    public void wrongHostKey() throws IOException {
        closeBlackhole();
        stopServer();
        if (backupKey == null && Files.size(keyFile) > 0) {
            backupKey = Files.createTempFile("file-server-key-original-", ".ser");
            Files.copy(keyFile, backupKey, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(keyFile);
        listen(port);
    }

    public void rejectLogins(boolean value) {
        rejectLogins = value;
    }

    public void denyWrites(boolean value) {
        denyWrites = value;
    }

    public void diskFull(boolean value) {
        diskFull = value;
    }

    public void dropWhileWriting(boolean value) {
        dropWhileWriting = value;
    }

    // ---- the directory, seen the way a person with a file manager would ----

    /** Makes a directory (and its parents) below the root. */
    public Path directory(String path) throws IOException {
        return Files.createDirectories(resolve(path));
    }

    /** Puts a file in place, as the other side would, with a modification time. */
    public Path put(String directory, String name, String content, Instant modified) throws IOException {
        Path file = directory(directory).resolve(name);
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    /** The names in a directory, sorted. */
    public List<String> names(String directory) {
        Path dir = resolve(directory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /** The names in a directory that are not temporary names (they do not start with a dot). */
    public List<String> visibleNames(String directory) {
        return names(directory).stream().filter(name -> !name.startsWith(".")).toList();
    }

    /** The content of a file. */
    public String read(String directory, String name) {
        try {
            return Files.readString(resolve(directory).resolve(name));
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /** The contents of every file in a directory, in the order of their names. */
    public List<String> contents(String directory) {
        List<String> all = new ArrayList<>();
        for (String name : visibleNames(directory)) {
            all.add(read(directory, name));
        }
        return all;
    }

    private Path resolve(String path) {
        return root.resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    // ---- shutting down ----

    private void stopServer() throws IOException {
        SshServer running = server;
        server = null;
        if (running != null) {
            running.stop(true);
        }
    }

    private void closeBlackhole() throws IOException {
        ServerSocket socket = blackhole;
        blackhole = null;
        if (socket != null) {
            socket.close();
        }
        for (Socket one : held) {
            one.close();
        }
        held.clear();
    }

    @Override
    public void close() {
        try {
            down();
            Files.deleteIfExists(keyFile);
            if (backupKey != null) {
                Files.deleteIfExists(backupKey);
            }
            try (Stream<Path> tree = Files.walk(root)) {
                tree.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        } catch (IOException failure) {
            throw new IllegalStateException("The test server could not be removed", failure);
        }
    }

    /** What the server does wrong when it is told to. */
    private final class Misbehaviour implements SftpEventListener {

        @Override
        public void creating(ServerSession session, Path path, Map<String, ?> attributes) throws IOException {
            refuseWrites();
        }

        @Override
        public void moving(ServerSession session, Path source, Path target, Collection<CopyOption> options)
                throws IOException {
            refuseWrites();
        }

        @Override
        public void writing(ServerSession session, String handle, FileHandle local, long offset, byte[] data,
                int dataOffset, int length) throws IOException {
            writes.incrementAndGet();
            refuseWrites();
            if (dropWhileWriting) {
                session.close(true);
                throw new IOException("The connection was cut");
            }
        }

        private void refuseWrites() throws IOException {
            if (denyWrites) {
                throw new AccessDeniedException("denied");
            }
            if (diskFull) {
                throw new IOException("No space left on device");
            }
        }
    }
}
