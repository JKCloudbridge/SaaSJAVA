package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.EgressClient.Answer;
import app.platform.integration.internal.EgressClient.Call;
import app.platform.integration.internal.EgressClient.Header;
import app.platform.integration.internal.EndpointRules.Endpoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Sending an entity and reading a bounded answer through the one outbound client (Sprint 28, ADR-0223) against small
 * servers started by the test: what is written (the method, the length, the type, the credential header), a body read
 * by
 * its length, in chunks and until the other side closes, the limit on the answer, a redirect that is not followed, and
 * a refusal of an address that is not public. No real service is called.
 */
class EgressCallTest {

    private static final IntegrationProperties.Egress SETTINGS = new IntegrationProperties.Egress(
            Duration.ofSeconds(2), Duration.ofSeconds(2), 16384, List.of());
    private static final Duration WHOLE = Duration.ofSeconds(5);

    private record Seen(String head, String body) {
    }

    /** A server that reads a request with a length and answers every connection with fixed bytes. */
    private static final class Canned implements AutoCloseable {

        final ServerSocket socket;
        final List<Seen> seen = new CopyOnWriteArrayList<>();

        Canned(byte[] answer) throws IOException {
            socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        seen.add(read(client.getInputStream()));
                        OutputStream out = client.getOutputStream();
                        out.write(answer);
                        out.flush();
                    } catch (IOException done) {
                        // The test closes the socket or the client gave up.
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

        private static Seen read(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int next = in.read();
                if (next < 0) {
                    break;
                }
                head.append((char) next);
            }
            int length = 0;
            for (String line : head.toString().split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(in.readNBytes(length));
            return new Seen(head.toString(), body.toString(StandardCharsets.UTF_8));
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

    private Canned server(String answer) throws IOException {
        Canned canned = new Canned(answer.getBytes(StandardCharsets.UTF_8));
        open.add(canned);
        return canned;
    }

    private EgressClient client() throws Exception {
        return new EgressClient(SETTINGS, loopback, SSLContext.getDefault().getSocketFactory());
    }

    private static Call post(int port, String body, int maxResponse) {
        return new Call(new Endpoint(false, "127.0.0.1", port, "/base", true), "POST", "/item",
                new Header("Authorization", "Bearer abc"), "application/json",
                body.getBytes(StandardCharsets.UTF_8), WHOLE, maxResponse);
    }

    @Test
    void itWritesTheMethodTheLengthTheTypeAndTheCredentialAndReadsTheBodyByItsLength() throws Exception {
        Canned service = server("HTTP/1.1 201 Created\r\nContent-Length: 14\r\nConnection: close\r\n\r\n"
                + "{\"id\":\"ext-1\"}");

        Answer answer = client().call(post(service.port(), "{\"a\":1}", 1024));

        assertThat(answer.failure()).isNull();
        assertThat(answer.status()).isEqualTo(201);
        assertThat(new String(answer.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"ext-1\"}");
        Seen request = service.seen.get(0);
        assertThat(request.head()).startsWith("POST /base/item HTTP/1.1\r\n").contains("Host: 127.0.0.1")
                .contains("Content-Type: application/json").contains("Content-Length: 7")
                .contains("Authorization: Bearer abc");
        assertThat(request.body()).isEqualTo("{\"a\":1}");
    }

    @Test
    void aBodyAnnouncedInChunksIsReadWhole() throws Exception {
        Canned service = server("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                + "7\r\n{\"id\":\"\r\n6\r\next-2\"\r\n1\r\n}\r\n0\r\n\r\n");

        Answer answer = client().call(post(service.port(), "{}", 1024));

        assertThat(answer.failure()).isNull();
        assertThat(new String(answer.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"ext-2\"}");
    }

    @Test
    void aBodyWithNoAnnouncedLengthIsReadUntilTheOtherSideCloses() throws Exception {
        Canned service = server("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n{\"id\":\"ext-3\"}");

        Answer answer = client().call(post(service.port(), "{}", 1024));

        assertThat(new String(answer.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"ext-3\"}");
    }

    @Test
    void anAnswerLongerThanTheLimitIsRefusedByEveryWayOfAnnouncingIt() throws Exception {
        String big = "x".repeat(2000);
        Canned byLength = server("HTTP/1.1 200 OK\r\nContent-Length: 2000\r\n\r\n" + big);
        Canned untilClose = server("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + big);
        Canned chunked = server("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n7d0\r\n" + big + "\r\n0\r\n\r\n");

        for (Canned service : List.of(byLength, untilClose, chunked)) {
            Answer answer = client().call(post(service.port(), "{}", 1024));

            assertThat(answer.failure()).isEqualTo(TestReason.RESPONSE_TOO_LARGE);
            assertThat(answer.body()).isEmpty();
        }
    }

    @Test
    void aRedirectIsNotFollowedAndItsStatusIsReturnedAsIs() throws Exception {
        Canned service = server("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:9/elsewhere\r\n"
                + "Content-Length: 0\r\n\r\n");

        Answer answer = client().call(post(service.port(), "{}", 1024));

        assertThat(answer.status()).isEqualTo(302);
        assertThat(service.seen).hasSize(1);
    }

    @Test
    void aNoContentAnswerHasNoBodyAndANameThatIsNotPublicIsRefusedBeforeAnythingIsSent() throws Exception {
        Canned service = server("HTTP/1.1 204 No Content\r\n\r\n");
        assertThat(client().call(post(service.port(), "{}", 1024)).body()).isEmpty();

        EgressClient strict = new EgressClient(SETTINGS, host -> new InetAddress[] {
            InetAddress.getByName("10.0.0.5")}, SSLContext.getDefault().getSocketFactory());
        Answer refused = strict.call(new Call(new Endpoint(true, "partner.example.com", 443, "", false), "POST",
                "/item", null, "application/json", new byte[0], WHOLE, 1024));

        assertThat(refused.failure()).isEqualTo(TestReason.ADDRESS_NOT_ALLOWED);
    }

    @Test
    void whatItPrintsNeverShowsTheBodyOrTheHeader() throws Exception {
        Call call = post(1, "secret-body", 1024);

        assertThat(call.toString()).doesNotContain("secret-body").doesNotContain("abc");
        assertThat(new Answer(null, 200, "secret-answer".getBytes(StandardCharsets.UTF_8)).toString())
                .doesNotContain("secret-answer");
    }
}
