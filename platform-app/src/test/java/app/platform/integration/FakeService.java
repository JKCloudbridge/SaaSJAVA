package app.platform.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The external system of the connector tests: a small web service started by the test on this machine, answering as
 * it is told and remembering what it was asked. It is no real service and has no name; the sample connector talks to it
 * over plain HTTP because the test profile names its address as a development host.
 *
 * <p>Since Sprint 29 it can be put into an outage and back ({@link #outage}, {@link #up}), and it honours an
 * idempotency key the way a careful service does: a key it has seen gets the answer it gave the first time and no
 * second effect. {@link #effects()} counts what really happened on its side, so a test can tell "the platform sent it
 * twice" from "the service did it twice".
 */
public final class FakeService implements AutoCloseable {

    /** What the service was asked. Header values are kept so a test can see what the platform sent. */
    public record Seen(String method, String path, String authorization, String apiKey, String contentType,
            String body, Map<String, String> headers) {

        /** A request without extra headers. */
        public Seen(String method, String path, String authorization, String apiKey, String contentType,
                String body) {
            this(method, path, authorization, apiKey, contentType, body, Map.of());
        }

        /** A request without a body (every request before Sprint 28). */
        public Seen(String method, String path, String authorization, String apiKey) {
            this(method, path, authorization, apiKey, null, "");
        }

        /** The value of a header the request carried, by lower case name, or null. */
        public String header(String lowerCaseName) {
            return headers.get(lowerCaseName);
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

    /** The ways the service can be down. */
    public enum Outage {
        /** The connection is opened and closed at once: a reset. */
        REFUSE,
        /** The request is read and never answered: the platform's deadline ends it. */
        HANG,
        /** Every request gets this status (and headers, such as Retry-After); nothing takes effect. */
        STATUS,
        /** The request takes effect and the connection is closed before the answer: the answer is lost. */
        LOSE_ANSWER
    }

    private static final String BODY = "the body is never read by the platform";

    private final ServerSocket server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<Reply>> sequences = new ConcurrentHashMap<>();
    private final Map<String, String> creations = new ConcurrentHashMap<>();
    private final Map<String, Reply> byKey = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<String> effectLog = new CopyOnWriteArrayList<>();
    private final AtomicInteger created = new AtomicInteger();
    private volatile Outage outage;
    private volatile Reply outageReply;

    public FakeService() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
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
            String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }
            String[] line = requestLine.split(" ");
            if (outage == Outage.REFUSE) {
                return;
            }
            String authorization = null;
            String apiKey = null;
            String contentType = null;
            int length = 0;
            Map<String, String> headers = new HashMap<>();
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = header.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                String value = header.substring(colon + 1).strip();
                headers.put(name, value);
                switch (name) {
                    case "authorization" -> authorization = value;
                    case "x-api-key" -> apiKey = value;
                    case "content-type" -> contentType = value;
                    case "content-length" -> length = Integer.parseInt(value);
                    default -> {
                        // Kept in the map for a test that wants to see it.
                    }
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
            seen.add(new Seen(line[0], line[1], authorization, apiKey, contentType, body, Map.copyOf(headers)));
            Reply reply = decide(line[0], line[1], headers.get("idempotency-key"));
            if (reply == null) {
                if (outage == Outage.HANG) {
                    Thread.sleep(60_000);
                }
                return;
            }
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

    /** What to answer, or null to drop the connection without an answer. */
    private Reply decide(String method, String path, String key) {
        Outage down = outage;
        if (down == Outage.HANG) {
            return null;
        }
        if (down == Outage.STATUS) {
            return outageReply;
        }
        boolean mutates = !method.equals("GET") && !method.equals("HEAD");
        Reply reply;
        if (mutates && key != null && byKey.containsKey(key)) {
            // A key it has seen: the answer it gave then, and no second effect.
            reply = byKey.get(key);
        } else {
            reply = configured(method, path);
            if (mutates) {
                String id = creations.get(path);
                if (id != null && method.equals("POST") && reply.status() < 300) {
                    reply = Reply.json(201, "{\"id\":\"" + id + "-" + created.incrementAndGet() + "\"}");
                }
                if (reply.status() < 300) {
                    effectLog.add(method + " " + path);
                    if (key != null) {
                        byKey.put(key, reply);
                    }
                }
            }
        }
        return down == Outage.LOSE_ANSWER && mutates ? null : reply;
    }

    private Reply configured(String method, String path) {
        for (String name : new String[] {method + " " + path, path}) {
            ConcurrentLinkedDeque<Reply> queue = sequences.get(name);
            if (queue != null) {
                Reply next = queue.size() > 1 ? queue.pollFirst() : queue.peekFirst();
                if (next != null) {
                    return next;
                }
            }
        }
        return replies.getOrDefault(method + " " + path, replies.getOrDefault(path, Reply.status(200)));
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

    /** Answers the path with these replies in turn; the last is repeated. */
    public FakeService answerInTurn(String path, Reply... inTurn) {
        sequences.put(path, new ConcurrentLinkedDeque<>(List.of(inTurn)));
        return this;
    }

    /** Makes every successful POST to the path create a new entity, named {@code prefix-1}, {@code prefix-2}, ... */
    public FakeService creates(String path, String prefix) {
        creations.put(path, prefix);
        return this;
    }

    /** Puts the service into an outage. {@code reply} is used by {@link Outage#STATUS} only. */
    public FakeService outage(Outage kind, Reply reply) {
        outageReply = reply;
        outage = kind;
        return this;
    }

    /** The same, for a kind that needs no reply. */
    public FakeService outage(Outage kind) {
        return outage(kind, Reply.status(503));
    }

    /** Brings the service back. */
    public FakeService up() {
        outage = null;
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

    /** How many requests really took effect on this side, as {@code METHOD path}, oldest first. */
    public List<String> effects() {
        return List.copyOf(effectLog);
    }

    /** How many times a request with this method and path took effect. */
    public long effectsOf(String method, String path) {
        return effectLog.stream().filter((method + " " + path)::equals).count();
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
