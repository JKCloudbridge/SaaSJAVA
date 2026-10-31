package app.platform.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The external system of the connector tests: a small web service started by the test on this machine, answering as
 * it is told and remembering what it was asked. It is no real service and has no name; the sample connector talks to it
 * over plain HTTP because the test profile names its address as a development host.
 */
public final class FakeService implements AutoCloseable {

    /** What the service was asked. Header values are kept so a test can see what the platform sent. */
    public record Seen(String method, String path, String authorization, String apiKey, String contentType,
            String body) {

        /** A request without a body (every request before Sprint 28). */
        public Seen(String method, String path, String authorization, String apiKey) {
            this(method, path, authorization, apiKey, null, "");
        }
    }

    /** How the service answers one path. */
    public record Reply(int status, Map<String, String> headers, long delayMillis, String body, boolean chunked) {

        /** An answer that carries the platform's old fixed body (every answer before Sprint 28). */
        public Reply(int status, Map<String, String> headers, long delayMillis) {
            this(status, headers, delayMillis, null, false);
        }

        /** A success that carries this JSON, announced by its length. */
        public static Reply json(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), 0, body, false);
        }

        /** The same body sent in chunks, to prove the platform reads both ways of announcing a body. */
        public static Reply chunkedJson(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), 0, body, true);
        }

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

    private static final String BODY = "the body is never read by the platform";

    private final ServerSocket server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    public FakeService() throws IOException {
        server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Thread.ofVirtual().start(() -> serve(client));
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    private void serve(Socket client) {
        try (client) {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(),
                    StandardCharsets.ISO_8859_1));
            String[] line = in.readLine().split(" ");
            String authorization = null;
            String apiKey = null;
            String contentType = null;
            int length = 0;
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                String lower = header.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("authorization:")) {
                    authorization = header.substring(header.indexOf(':') + 1).strip();
                } else if (lower.startsWith("x-api-key:")) {
                    apiKey = header.substring(header.indexOf(':') + 1).strip();
                } else if (lower.startsWith("content-type:")) {
                    contentType = header.substring(header.indexOf(':') + 1).strip();
                } else if (lower.startsWith("content-length:")) {
                    length = Integer.parseInt(header.substring(header.indexOf(':') + 1).strip());
                }
            }
            char[] chars = new char[length];
            int read = 0;
            while (read < length) {
                int more = in.read(chars, read, length - read);
                if (more < 0) {
                    break;
                }
                read += more;
            }
            String body = new String(chars, 0, read);
            seen.add(new Seen(line[0], line[1], authorization, apiKey, contentType, body));
            Reply reply = replies.getOrDefault(line[0] + " " + line[1], replies.getOrDefault(line[1],
                    Reply.status(200)));
            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(reply.status()).append(" X\r\n");
            reply.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            boolean headOnly = line[0].equals("HEAD");
            String answer = reply.body() == null ? BODY : reply.body();
            if (reply.chunked() && !headOnly) {
                head.append("Transfer-Encoding: chunked");
            } else {
                head.append("Content-Length: ").append(headOnly ? 0 : answer.getBytes(StandardCharsets.UTF_8).length);
            }
            head.append("\r\nConnection: close\r\n\r\n");
            OutputStream out = client.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!headOnly) {
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                if (reply.chunked()) {
                    int half = Math.max(1, bytes.length / 2);
                    out.write((Integer.toHexString(half) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(bytes, 0, half);
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    int rest = bytes.length - half;
                    if (rest > 0) {
                        out.write((Integer.toHexString(rest) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        out.write(bytes, half, rest);
                        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                } else {
                    out.write(bytes);
                }
            }
            out.flush();
        } catch (IOException | InterruptedException | RuntimeException gone) {
            // The platform gave up (a timeout test) or the test is over; nothing to report.
        }
    }

    /** The address to give a connection: plain HTTP on this machine. */
    public String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
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

    /** The bodies the path was sent, oldest first. */
    public List<String> bodies(String path) {
        return seen.stream().filter(one -> one.path().equals(path)).map(Seen::body).toList();
    }

    /** How many times the path was asked. */
    public long asked(String path) {
        return seen.stream().filter(one -> one.path().equals(path)).count();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
