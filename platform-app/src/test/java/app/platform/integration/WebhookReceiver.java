package app.platform.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The customer's software in the webhook tests (Sprint 32): a small web service started by the test on this machine
 * that answers as it is told, can be put into an outage, remembers every delivery it received (headers and exact body
 * bytes) and verifies a signature <em>independently</em> of the platform's own code, with the steps a receiver's guide
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

    private final ServerSocket server;
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
            server = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Thread.ofVirtual().name("webhook-receiver").start(this::accept);
    }

    /** The address of the receiver, with a path: what a person types into the form. */
    public String address(String path) {
        return "http://127.0.0.1:" + server.getLocalPort() + path;
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

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(30_000);
            handle(socket);
        } catch (IOException ignored) {
            // A caller that goes away mid-answer is the expected end of a call that reads only the status.
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        requests.incrementAndGet();
        String requestLine = line(in);
        String path = requestLine.split(" ", 3).length > 1 ? requestLine.split(" ", 3)[1] : "/";
        int query = path.indexOf('?');
        String onlyPath = query < 0 ? path : path.substring(0, query);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String header = line(in); !header.isEmpty(); header = line(in)) {
            int colon = header.indexOf(':');
            if (colon > 0) {
                headers.put(header.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                        header.substring(colon + 1).trim());
            }
        }
        byte[] body = body(in, headers);
        if (down) {
            return;
        }
        received.add(new Received(onlyPath, headers, body, Instant.now()));
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
            write(socket, 302, "Location: " + redirectTo + "\r\n", new byte[0]);
        } else if (answerText != null && answer / 100 == 2) {
            write(socket, answer, "", answerText.getBytes(StandardCharsets.UTF_8));
        } else if (largeAnswer && answer / 100 == 2) {
            write(socket, answer, "", "x".repeat(200_000).getBytes(StandardCharsets.UTF_8));
        } else {
            write(socket, answer, "", new byte[0]);
        }
    }

    private static void write(Socket socket, int answer, String extraHeaders, byte[] body) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + answer + " Answer\r\n" + extraHeaders + "Content-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static byte[] body(InputStream in, Map<String, String> headers) throws IOException {
        String length = headers.get("content-length");
        if (length != null) {
            return in.readNBytes(Integer.parseInt(length));
        }
        if (!"chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            return new byte[0];
        }
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        for (int size = Integer.parseInt(line(in).split(";")[0].trim(), 16); size > 0;
                size = Integer.parseInt(line(in).split(";")[0].trim(), 16)) {
            whole.write(in.readNBytes(size));
            line(in);
        }
        line(in);
        return whole.toByteArray();
    }

    /** One line of the request, without its end; an end of the stream is an empty line. */
    private static String line(InputStream in) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int next = in.read(); next >= 0 && next != '\n'; next = in.read()) {
            if (next != '\r') {
                text.append((char) next);
            }
        }
        return text.toString();
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
        try {
            server.close();
        } catch (IOException alreadyClosed) {
            // Closing a receiver that is gone is not a failure of the test.
        }
    }
}
