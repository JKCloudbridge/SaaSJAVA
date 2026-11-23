package app.platform.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The customer's software in the webhook tests (Sprint 32): a small web service started by the test on this machine
 * that
 * answers as it is told, can be put into an outage, remembers every delivery it received (headers and exact body bytes)
 * and verifies a signature <em>independently</em> of the platform's own code, with the steps a receiver's guide
 * gives. It is no real service and has no name; the platform reaches it over plain HTTP because the test profile names
 * its address as a development host (the rules for a real address are tested at {@code EgressClient}).
 */
public final class WebhookReceiver implements AutoCloseable {

    /** One delivery as it arrived. */
    public record Received(String path, Map<String, String> headers, byte[] body, Instant at) {

        /** The value of a header, by name without regard to case, or null. */
        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        /** The body as text. */
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** The event the delivery says it is about ({@code Webhook-Event}). */
        public String event() {
            return header("Webhook-Event");
        }

        /** The delivery identifier ({@code Webhook-Id}). */
        public String id() {
            return header("Webhook-Id");
        }

        /** Prints the identity only: the body is a message about a record. */
        @Override
        public String toString() {
            return "Received[" + id() + "]";
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Deque<Integer> script = new ConcurrentLinkedDeque<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile boolean down;
    private volatile long delayMillis;
    private volatile boolean largeAnswer;
    private volatile String redirectTo;
    private volatile String answerText;

    /** Starts the receiver on a free port of this machine. */
    public WebhookReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 128);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** The address of the receiver, with a path: what a person types into the form. */
    public String address(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** The answer for every later request, until another is set. */
    public void respondWith(int answer) {
        this.status = answer;
    }

    /** The next {@code count} requests are answered with this status; then the usual answer. */
    public void failNext(int count, int answer) {
        for (int i = 0; i < count; i++) {
            script.addLast(answer);
        }
    }

    /** An outage: the connection is closed without any answer until it is ended. */
    public void outage(boolean on) {
        this.down = on;
    }

    /** Every answer waits this long first. */
    public void delay(Duration wait) {
        this.delayMillis = wait.toMillis();
    }

    /** Answers with a large page (200 kilobytes) as a receiver that serves a web page would. */
    public void largeAnswer(boolean on) {
        this.largeAnswer = on;
    }

    /** Answers every successful request with this text as the body, as a receiver that explains itself would. */
    public void answerBody(String text) {
        this.answerText = text;
    }

    /** Answers every request with a redirect to this address. */
    public void redirectTo(String address) {
        this.redirectTo = address;
    }

    /** Every delivery that arrived so far, oldest first (a ping is among them). */
    public List<Received> received() {
        return List.copyOf(received);
    }

    /** The deliveries about this event ({@code record.created}, {@code ping}, ...). */
    public List<Received> of(String event) {
        return received.stream().filter(one -> event.equals(one.event())).toList();
    }

    /** How many requests came, answered or not, including those cut off by an outage. */
    public int requests() {
        return requests.get();
    }

    /** Forgets what was received (the requests counter stays). */
    public void clear() {
        received.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT),
                String.join(",", values)));
        if (down) {
            exchange.close();
            return;
        }
        received.add(new Received(exchange.getRequestURI().getPath(), headers, body, Instant.now()));
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Integer scripted = script.pollFirst();
        int answer = scripted != null ? scripted : status;
        if (redirectTo != null) {
            exchange.getResponseHeaders().add("Location", redirectTo);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        if (answerText != null && answer / 100 == 2) {
            byte[] text = answerText.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer, text.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(text);
            } catch (IOException cut) {
                // The platform reads the status and closes: a cut body is the expected end.
            }
            return;
        }
        if (largeAnswer && answer / 100 == 2) {
            byte[] page = "x".repeat(200_000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            } catch (IOException cut) {
                // The platform reads the status and closes: a cut body is the expected end.
            }
            return;
        }
        exchange.sendResponseHeaders(answer, -1);
        exchange.close();
    }

    // ---- what a receiver does with a delivery, written independently of the platform's code ----

    /**
     * Verifies a delivery the way the guide says: the time is inside the tolerance, and one of the {@code v1=} parts of
     * the signature header is the HMAC-SHA256, in hexadecimal, of {@code <timestamp>.<body>} made with the secret.
     */
    public static boolean verify(Received delivery, String secret, long toleranceSeconds) {
        String signature = delivery.header("Webhook-Signature");
        String timestamp = delivery.header("Webhook-Timestamp");
        if (signature == null || timestamp == null) {
            return false;
        }
        long sent = Long.parseLong(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - sent) > toleranceSeconds) {
            return false;
        }
        String expected = hmac(secret, timestamp + "." + delivery.text());
        for (String part : signature.split(" ")) {
            if (part.equals("v1=" + expected)) {
                return true;
            }
        }
        return false;
    }

    /** How many signatures the delivery carries (two during the overlap of a rotation). */
    public static int signatures(Received delivery) {
        String header = delivery.header("Webhook-Signature");
        List<String> parts = new ArrayList<>();
        if (header != null) {
            for (String part : header.split(" ")) {
                if (part.startsWith("v1=")) {
                    parts.add(part);
                }
            }
        }
        return parts.size();
    }

    private static String hmac(String secret, String signed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
