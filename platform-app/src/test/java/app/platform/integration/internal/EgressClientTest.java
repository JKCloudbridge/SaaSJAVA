package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.integration.internal.EgressClient.Header;
import app.platform.integration.internal.EgressClient.Outcome;
import app.platform.integration.internal.EgressClient.Request;
import app.platform.integration.internal.EndpointRules.Endpoint;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one outbound client (ADR-0208, ADR-0210) against small servers started by the test: what it writes, what it
 * refuses, every bound, and the secure connection checked against the name. No real service is called.
 */
class EgressClientTest {

    private static final IntegrationProperties.Egress SETTINGS = new IntegrationProperties.Egress(
            Duration.ofSeconds(2), Duration.ofSeconds(2), 16384, List.of());
    private static final Duration WHOLE = Duration.ofSeconds(5);

    /** A server that answers every connection with fixed bytes, optionally after silence, and records the requests. */
    private static final class Canned implements AutoCloseable {

        final ServerSocket socket;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Thread thread;

        Canned(byte[] answer, long silentMillis) throws IOException {
            socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            thread = Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        requests.add(readHead(client.getInputStream()));
                        if (silentMillis > 0) {
                            Thread.sleep(silentMillis);
                        }
                        OutputStream out = client.getOutputStream();
                        out.write(answer);
                        out.flush();
                    } catch (IOException | InterruptedException done) {
                        // The test closes the socket or the client gave up; either ends this exchange.
                    }
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        private static String readHead(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            int last = 0;
            while (!head.toString().endsWith("\r\n\r\n") && (last = in.read()) >= 0) {
                head.append((char) last);
            }
            return head.toString();
        }
    }

    private final EgressClient.Resolver loopback = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};
    private final List<AutoCloseable> open = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable one : open) {
            one.close();
        }
    }

    private Canned server(String answer, long silentMillis) throws IOException {
        Canned canned = new Canned(answer.getBytes(StandardCharsets.ISO_8859_1), silentMillis);
        open.add(canned);
        return canned;
    }

    private EgressClient client() throws Exception {
        return new EgressClient(SETTINGS, loopback, SSLContext.getDefault().getSocketFactory());
    }

    private static Endpoint local(int port, String basePath) {
        return new Endpoint(false, "127.0.0.1", port, basePath, true);
    }

    @Test
    void itWritesOneFixedRequestAndReadsOnlyTheStatus() throws Exception {
        Canned service = server("HTTP/1.1 204 No Content\r\nX-Anything: yes\r\n\r\nbody that is never read", 0);

        Outcome outcome = client().send(new Request(local(service.port(), "/base"), false, "/status",
                new Header("Authorization", "Bearer abc"), WHOLE));

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.status()).isEqualTo(204);
        String request = service.requests.get(0);
        assertThat(request).startsWith("GET /base/status HTTP/1.1\r\n").contains("Connection: close\r\n")
                .contains("User-Agent: platform-connector\r\n").contains("Authorization: Bearer abc\r\n")
                .contains("Host: 127.0.0.1:" + service.port() + "\r\n");
        assertThat(request.lines().count()).as("no other header").isEqualTo(7);
    }

    @Test
    void aHeadRequestIsSentAsHead() throws Exception {
        Canned service = server("HTTP/1.1 200 OK\r\n\r\n", 0);

        Outcome outcome = client().send(new Request(local(service.port(), ""), true, "/", null, WHOLE));

        assertThat(outcome.status()).isEqualTo(200);
        assertThat(service.requests.get(0)).startsWith("HEAD / HTTP/1.1\r\n").doesNotContain("Authorization");
    }

    @Test
    void aRedirectIsReportedAndNeverFollowed() throws Exception {
        Canned target = server("HTTP/1.1 200 OK\r\n\r\n", 0);
        Canned service = server("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + target.port() + "/x\r\n\r\n", 0);

        Outcome outcome = client().send(new Request(local(service.port(), ""), false, "/", null, WHOLE));

        assertThat(outcome.status()).isEqualTo(302);
        assertThat(target.requests).as("the redirect target was never contacted").isEmpty();
    }

    @Test
    void anAnswerLargerThanTheLimitIsRefusedAfterReadingNoMoreThanTheLimit() throws Exception {
        Canned service = server("HTTP/1.1 200 OK\r\nX-Padding: " + "a".repeat(20000) + "\r\n\r\n", 0);

        Outcome outcome = client().send(new Request(local(service.port(), ""), false, "/", null, WHOLE));

        assertThat(outcome.failure()).isEqualTo(TestReason.RESPONSE_TOO_LARGE);
    }

    @Test
    void anAnswerThatIsNotHttpOrIsCutShortIsInvalid() throws Exception {
        Canned notHttp = server("SSH-2.0-nothing\r\n\r\n", 0);
        Canned cut = server("HTTP/1.1 200 OK\r\nX-Half", 0);
        Canned oldVersion = server("HTTP/0.9 200 OK\r\n\r\n", 0);
        Canned informational = server("HTTP/1.1 100 Continue\r\n\r\n", 0);

        for (Canned service : List.of(notHttp, cut, oldVersion, informational)) {
            assertThat(client().send(new Request(local(service.port(), ""), false, "/", null, WHOLE)).failure())
                    .isEqualTo(TestReason.RESPONSE_INVALID);
        }
    }

    @Test
    void aServiceThatStaysSilentIsTimedOutWithinTheDeadline() throws Exception {
        Canned service = server("HTTP/1.1 200 OK\r\n\r\n", 5000);
        long started = System.nanoTime();

        Outcome outcome = client().send(new Request(local(service.port(), ""), false, "/", null,
                Duration.ofMillis(600)));

        assertThat(outcome.failure()).isEqualTo(TestReason.TIMED_OUT);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2500);
    }

    @Test
    void nothingListeningIsAFailedConnectionAndAnUnknownNameIsNotFound() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        Outcome refused = client().send(new Request(local(closedPort, ""), false, "/", null, WHOLE));
        Outcome unknown = new EgressClient(SETTINGS, host -> {
            throw new UnknownHostException();
        }, SSLContext.getDefault().getSocketFactory())
                .send(new Request(new Endpoint(true, "nowhere.example.com", 443, "", false), false, "/", null, WHOLE));

        assertThat(refused.failure()).isEqualTo(TestReason.CONNECT_FAILED);
        assertThat(unknown.failure()).isEqualTo(TestReason.NAME_NOT_FOUND);
    }

    @Test
    void aNameThatResolvesToAPrivateAddressIsRefusedBeforeAnythingIsSent() throws Exception {
        Canned service = server("HTTP/1.1 200 OK\r\n\r\n", 0);
        Endpoint publicName = new Endpoint(false, "api.example.com", service.port(), "", false);
        EgressClient toPrivate = new EgressClient(SETTINGS,
                host -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})},
                SSLContext.getDefault().getSocketFactory());
        EgressClient mixed = new EgressClient(SETTINGS,
                host -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {8, 8, 8, 8}),
                    InetAddress.getByAddress(host, new byte[] {10, 0, 0, 5})},
                SSLContext.getDefault().getSocketFactory());

        assertThat(toPrivate.send(new Request(publicName, false, "/", null, WHOLE)).failure())
                .isEqualTo(TestReason.ADDRESS_NOT_ALLOWED);
        assertThat(mixed.send(new Request(publicName, false, "/", null, WHOLE)).failure())
                .as("one private answer among public ones is enough").isEqualTo(TestReason.ADDRESS_NOT_ALLOWED);
        assertThat(service.requests).isEmpty();
    }

    @Test
    void aHeaderCannotCarryALineBreakAControlCharacterOrAnUnusualName() {
        assertThatThrownBy(() -> new Header("Authorization", "Bearer a\r\nX-Evil: 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Header("Authorization", "Bearer a\u0000b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Header("Authorization", "Bearer ä"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Header("X-Key\r\nX-Evil", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Header("X-Key", "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Header("X-Key", "top-secret-value").toString()).doesNotContain("top-secret");
    }

    // ---- the secure connection ----

    /** A key pair for the name {@code localhost}, made with the keytool of the running Java. */
    private static KeyStore keystore(Path directory) throws Exception {
        Path file = directory.resolve("server.p12");
        Process made = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "san=dns:localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        made.getInputStream().readAllBytes();
        assertThat(made.waitFor(60, TimeUnit.SECONDS)).isTrue();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            store.load(in, "changeit".toCharArray());
        }
        return store;
    }

    /** A TLS server on this machine that answers every connection with a 200 once the handshake is done. */
    private ServerSocket secureServer(KeyStore store) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 5,
                InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    Canned.readHead(client.getInputStream());
                    client.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                } catch (IOException ended) {
                    // A failed handshake or a closed socket ends this exchange.
                }
            }
        });
        open.add(server);
        return server;
    }

    private static SSLContext trusting(KeyStore store) throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    @Test
    void aSecureConnectionIsMadeWhenTheCertificateIsTrustedAndMatchesTheName(@TempDir Path directory)
            throws Exception {
        KeyStore store = keystore(directory);
        ServerSocket server = secureServer(store);
        EgressClient trusting = new EgressClient(SETTINGS, host -> new InetAddress[] {
            InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})}, trusting(store).getSocketFactory());

        Outcome outcome = trusting.send(new Request(new Endpoint(true, "localhost", server.getLocalPort(), "",
                true), false, "/", null, WHOLE));

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.status()).isEqualTo(200);
    }

    @Test
    void aCertificateForAnotherNameOrFromAnUntrustedIssuerIsRefused(@TempDir Path directory) throws Exception {
        KeyStore store = keystore(directory);
        ServerSocket server = secureServer(store);
        int port = server.getLocalPort();
        EgressClient.Resolver toServer = host -> new InetAddress[] {
            InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})};
        EgressClient trusting = new EgressClient(SETTINGS, toServer, trusting(store).getSocketFactory());
        EgressClient ordinary = new EgressClient(SETTINGS, toServer, SSLContext.getDefault().getSocketFactory());

        Outcome otherName = trusting.send(new Request(new Endpoint(true, "other.example.org", port, "", true), false,
                "/", null, WHOLE));
        Outcome untrusted = ordinary.send(new Request(new Endpoint(true, "localhost", port, "", true), false, "/",
                null, WHOLE));

        assertThat(otherName.failure()).as("the name does not match the certificate").isEqualTo(TestReason.TLS_FAILED);
        assertThat(untrusted.failure()).as("nobody trusts this issuer").isEqualTo(TestReason.TLS_FAILED);
    }
}
