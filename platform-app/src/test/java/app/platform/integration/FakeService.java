package app.platform.integration;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * The external system of the connector tests: a small web service started by the test on this machine, answering as
 * it is told and remembering what it was asked. It is no real service and has no name; the sample connector talks to it
 * over plain HTTP because the test profile names its address as a development host.
 */
public final class FakeService implements AutoCloseable {

    /** What the service was asked. Header values are kept so a test can see what the platform sent. */
    public record Seen(String method, String path, String authorization, String apiKey) {
    }

    /** How the service answers one path. */
    public record Reply(int status, Map<String, String> headers, long delayMillis) {

        public static Reply status(int status) {
            return new Reply(status, Map.of(), 0);
        }

        public static Reply redirectTo(String location) {
            return new Reply(302, Map.of("Location", location), 0);
        }

        public static Reply slow(long millis) {
            return new Reply(200, Map.of(), millis);
        }
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    public FakeService() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            seen.add(new Seen(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Api-Key")));
            Reply reply = replies.getOrDefault(path, Reply.status(200));
            try {
                if (reply.delayMillis() > 0) {
                    Thread.sleep(reply.delayMillis());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] body = "the body is never read by the platform".getBytes(StandardCharsets.UTF_8);
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.sendResponseHeaders(reply.status(), -1);
            } else {
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    /** The address to give a connection: plain HTTP on this machine. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Answers the path in this way from now on. */
    public FakeService answer(String path, Reply reply) {
        replies.put(path, reply);
        return this;
    }

    /** Everything it was asked, oldest first. */
    public List<Seen> seen() {
        return List.copyOf(seen);
    }

    /** How many times the path was asked. */
    public long asked(String path) {
        return seen.stream().filter(one -> one.path().equals(path)).count();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
