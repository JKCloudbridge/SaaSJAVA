package app.platform.integration.internal;

import app.platform.integration.internal.CredentialLogins.Login;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.ChannelSftp.LsEntrySelector;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The secure shell transport of file transfer (Sprint 29A, ADR-0237 to ADR-0239). It is the <em>only</em> class that
 * names the SSH library, and the only class besides the web client that holds a socket (architecture tests).
 *
 * <ul>
 *   <li><strong>The address rules are the web client's.</strong> The socket comes from {@link EgressClient#open}, which
 *       resolves the name, requires every address to be public and connects to the address it checked. The library is
 *       given that open socket and never looks a name up, so a name that answers differently a moment later gains
 *       nothing.
 *   <li><strong>No trust on first sight.</strong> The key the server shows is hashed and compared with the fingerprint
 *       pinned in the connection. The library's own known-hosts file is never read or written.
 *   <li><strong>Nothing is classified from a message.</strong> A failure is told from the stage it happened in (before
 *       the key was checked, a refused key, after it) and from the status code of the file protocol; what the library
 * or
 *       the server wrote is never read, logged or kept.
 *   <li><strong>One deadline.</strong> A watchdog closes the socket when the visit's time is up, whatever a blocked
 * read
 *       is waiting for.
 * </ul>
 */
@Component
class SftpTransport implements FileTransport {

    private final EgressClient egress;
    private final ScheduledExecutorService watchdogs = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "integration-ssh-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    SftpTransport(EgressClient egress) {
        this.egress = egress;
    }

    @PreDestroy
    void close() {
        watchdogs.shutdownNow();
    }

    /** What happened on the wire and in the key check; the only evidence a failure is classified from. */
    private static final class Evidence {

        private volatile boolean keyAccepted;
        private volatile boolean keyRefused;
        private volatile boolean broken;
        private volatile boolean timedOut;
    }

    @Override
    public FileVisit open(Target target, Login login) {
        EgressClient.Opened opened = egress.open(target.endpoint(), target.timeout());
        if (opened.failure() != null) {
            throw new TransportFailure(opened.failure());
        }
        Socket socket = opened.socket();
        Evidence evidence = new Evidence();
        ScheduledFuture<?> watchdog = watchdogs.schedule(() -> {
            evidence.timedOut = true;
            closeQuietly(socket);
        }, Math.max(1, target.timeout().toMillis()), TimeUnit.MILLISECONDS);
        Session session = null;
        try {
            JSch jsch = new JSch();
            if (login.privateKey() != null) {
                jsch.addIdentity("platform", login.privateKey().clone(), null, null);
            }
            session = jsch.getSession(login.user(), target.endpoint().host(), target.endpoint().port());
            session.setSocketFactory(new Wire(socket, evidence));
            session.setHostKeyRepository(new PinnedKey(target.hostKey(), evidence));
            session.setUserInfo(NO_PROMPTS);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", login.privateKey() != null ? "publickey" : "password");
            session.setConfig("compression.s2c", "none");
            session.setConfig("compression.c2s", "none");
            if (login.password() != null) {
                session.setPassword(login.password().clone());
            }
            int millis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, target.timeout().toMillis()));
            session.setTimeout(millis);
            session.connect(millis);
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(millis);
            return new Visit(session, channel, socket, watchdog, evidence);
        } catch (JSchException | ClassCastException failure) {
            watchdog.cancel(false);
            if (session != null) {
                session.disconnect();
            }
            closeQuietly(socket);
            throw new TransportFailure(connectFailure(evidence));
        }
    }

    /** Why a session could not be set up, from what was seen on the wire and never from a message. */
    private static TestReason connectFailure(Evidence evidence) {
        return SshOutcome.connect(evidence.keyRefused, evidence.timedOut, evidence.broken, evidence.keyAccepted);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing more can be done with a socket that will not close.
        }
    }

    /** The fingerprint of a server's key as the usual tools print it: SHA256: and the unpadded base64 of the digest. */
    static String fingerprint(byte[] keyBlob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBlob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    /** Says nothing and answers nothing: no prompt can be shown to anybody, so a server cannot ask for more. */
    private static final UserInfo NO_PROMPTS = new UserInfo() {
        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public boolean promptPassword(String message) {
            return false;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return false;
        }

        @Override
        public void showMessage(String message) {
            // A banner or a notice from the server is not shown, kept or logged.
        }
    };

    /** The pinned key: the only host key repository the session has. It trusts one fingerprint and nothing else. */
    private static final class PinnedKey implements HostKeyRepository {

        private final String pinned;
        private final Evidence evidence;

        PinnedKey(String pinned, Evidence evidence) {
            this.pinned = pinned;
            this.evidence = evidence;
        }

        @Override
        public int check(String host, byte[] key) {
            // Constant time is not needed: the fingerprint is public.
            if (key != null && pinned.equals(fingerprint(key))) {
                evidence.keyAccepted = true;
                return OK;
            }
            evidence.keyRefused = true;
            return CHANGED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
            // The platform never learns a key by itself.
        }

        @Override
        public void remove(String host, String type) {
            // Nothing is stored.
        }

        @Override
        public void remove(String host, String type, byte[] key) {
            // Nothing is stored.
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "pinned";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    /** Hands the library the socket that was already opened to the checked address, and notes how the wire behaves. */
    private static final class Wire implements com.jcraft.jsch.SocketFactory {

        private final Socket socket;
        private final Evidence evidence;

        Wire(Socket socket, Evidence evidence) {
            this.socket = socket;
            this.evidence = evidence;
        }

        @Override
        public Socket createSocket(String host, int port) {
            return socket;
        }

        @Override
        public InputStream getInputStream(Socket opened) throws IOException {
            return new FilterInputStream(opened.getInputStream()) {
                @Override
                public int read() throws IOException {
                    try {
                        int value = super.read();
                        if (value < 0) {
                            evidence.broken = true;
                        }
                        return value;
                    } catch (IOException failure) {
                        note(failure);
                        throw failure;
                    }
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    try {
                        int count = super.read(buffer, offset, length);
                        if (count < 0) {
                            evidence.broken = true;
                        }
                        return count;
                    } catch (IOException failure) {
                        note(failure);
                        throw failure;
                    }
                }
            };
        }

        @Override
        public OutputStream getOutputStream(Socket opened) throws IOException {
            return opened.getOutputStream();
        }

        private void note(IOException failure) {
            if (failure instanceof SocketTimeoutException) {
                evidence.timedOut = true;
            } else {
                evidence.broken = true;
            }
        }
    }

    /** One open session with the file channel. */
    private final class Visit implements FileVisit {

        private final Session session;
        private final ChannelSftp channel;
        private final Socket socket;
        private final ScheduledFuture<?> watchdog;
        private final Evidence evidence;

        Visit(Session session, ChannelSftp channel, Socket socket, ScheduledFuture<?> watchdog, Evidence evidence) {
            this.session = session;
            this.channel = channel;
            this.socket = socket;
            this.watchdog = watchdog;
            this.evidence = evidence;
        }

        @Override
        public List<Entry> list(String directory, int maxEntries) {
            List<Entry> entries = new ArrayList<>();
            LsEntrySelector selector = (LsEntry entry) -> {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    return LsEntrySelector.CONTINUE;
                }
                entries.add(entry(name, entry.getAttrs()));
                return entries.size() >= maxEntries ? LsEntrySelector.BREAK : LsEntrySelector.CONTINUE;
            };
            try {
                channel.ls(directory, selector);
            } catch (SftpException failure) {
                throw fail(failure);
            }
            return entries;
        }

        @Override
        public Optional<Entry> stat(String path) {
            try {
                // lstat: a symbolic link is reported as a link, never followed.
                return Optional.of(entry(path.substring(path.lastIndexOf('/') + 1), channel.lstat(path)));
            } catch (SftpException failure) {
                if (failure.id == SshOutcome.NO_SUCH_FILE && !evidence.broken && !evidence.timedOut) {
                    return Optional.empty();
                }
                throw fail(failure);
            }
        }

        @Override
        public void rename(String from, String to) {
            if (stat(to).isPresent()) {
                throw new TransportFailure(TestReason.FILE_FAILED);
            }
            try {
                channel.rename(from, to);
            } catch (SftpException failure) {
                throw fail(failure);
            }
        }

        @Override
        public byte[] read(String path, int maxBytes) {
            try (InputStream in = channel.get(path)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[16_384];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    out.write(chunk, 0, read);
                    if (out.size() > maxBytes) {
                        throw new TransportFailure(TestReason.RESPONSE_TOO_LARGE);
                    }
                }
                return out.toByteArray();
            } catch (SftpException failure) {
                throw fail(failure);
            } catch (IOException failure) {
                throw fail(failure);
            }
        }

        @Override
        public void create(String path, byte[] content) {
            if (stat(path).isPresent()) {
                throw new TransportFailure(TestReason.FILE_FAILED);
            }
            try (OutputStream out = channel.put(path, ChannelSftp.OVERWRITE)) {
                out.write(content);
            } catch (SftpException failure) {
                throw fail(failure);
            } catch (IOException failure) {
                throw fail(failure);
            }
        }

        @Override
        public void close() {
            watchdog.cancel(false);
            try {
                channel.disconnect();
            } finally {
                session.disconnect();
                closeQuietly(socket);
            }
        }

        private Entry entry(String name, SftpATTRS attributes) {
            return new Entry(name, attributes.getSize(), attributes.getMTime() & 0xffffffffL, attributes.isReg());
        }

        private TransportFailure fail(SftpException failure) {
            return new TransportFailure(reasonOf(failure.id));
        }

        /** A failed read or write: the wire first, then the status the library carried when it has one. */
        private TransportFailure fail(IOException failure) {
            if (failure.getCause() instanceof SftpException status && !evidence.broken && !evidence.timedOut) {
                return fail(status);
            }
            return new TransportFailure(wireReason());
        }

        private TestReason reasonOf(int status) {
            return SshOutcome.status(status, evidence.timedOut, evidence.broken);
        }

        private TestReason wireReason() {
            return SshOutcome.wire(evidence.timedOut, evidence.broken);
        }
    }
}
