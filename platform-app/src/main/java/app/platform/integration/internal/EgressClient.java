package app.platform.integration.internal;

import app.platform.integration.internal.EndpointRules.Endpoint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The one place the platform opens a connection to an external system (ADR-0208, ADR-0210). Adapters cannot reach it
 * except through the framework, so its rules cannot be bypassed:
 *
 * <ul>
 *   <li>the name is resolved here, <em>every</em> address it resolves to must be public, and the socket is opened to
 *       the address that was checked, so a name that answers differently a moment later gains nothing;
 *   <li>a secure connection checks the certificate against the name and sends the name for server selection;
 *   <li>the request is written by hand from fixed parts, so nothing a person typed can add a header or a line;
 *   <li>a redirect is never followed, the answer is read only as far as its status line and headers, and no more than
 *       {@code maxHeaderBytes} are ever read;
 *   <li>one deadline covers connecting, handshaking and reading.
 * </ul>
 *
 * <p>Nothing from the other side is kept: not a header, not an error message. The outcome of a check is a status number
 * or a stable reason. Since Sprint 28 a {@linkplain #call send} may also carry a body and read a bounded body back
 * (never more than the caller's limit, never logged, only handed to the adapter that reads it); the same address,
 * certificate, redirect and deadline rules apply to it (ADR-0223).
 */
@Component
class EgressClient {

    /** Looks a name up. A seam so a test can answer without a network. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** A header the platform adds on behalf of the connection. The value is a secret and is never printed. */
    record Header(String name, String value) {

        private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,63}");

        public Header {
            if (!NAME.matcher(name).matches() || !printable(value)) {
                throw new IllegalArgumentException("Invalid header");
            }
        }

        /** Visible ASCII and spaces only: no line break, no control character, nothing outside ASCII. */
        static boolean printable(String value) {
            if (value == null || value.isEmpty() || value.length() > 4096) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c > 0x7e) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return "Header[redacted]";
        }
    }

    /**
     * One request.
     *
     * @param endpoint the checked address
     * @param head {@code GET} or {@code HEAD}
     * @param path the path below the endpoint's own path, starting with a slash
     * @param auth the header that carries the credential, or null
     * @param timeout how long the whole exchange may take
     */
    record Request(Endpoint endpoint, boolean head, String path, Header auth, Duration timeout) {
    }

    /**
     * What came back: a status, or the reason there is none.
     *
     * @param failure the reason, or null when a status was read
     * @param status the status code, or 0 when there is a failure
     */
    record Outcome(TestReason failure, int status) {

        static Outcome failed(TestReason reason) {
            return new Outcome(reason, 0);
        }

        static Outcome answered(int status) {
            return new Outcome(null, status);
        }
    }

    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] ([2-5][0-9][0-9])(?: [^\\r\\n]*)?");
    private static final byte[] END_OF_HEAD = {'\r', '\n', '\r', '\n'};

    private final Resolver resolver;
    private final SSLSocketFactory tls;
    private final IntegrationProperties.Egress settings;

    @Autowired
    EgressClient(IntegrationProperties properties) throws NoSuchAlgorithmException {
        this(properties.egress(), InetAddress::getAllByName, SSLContext.getDefault().getSocketFactory());
    }

    EgressClient(IntegrationProperties.Egress settings, Resolver resolver, SSLSocketFactory tls) {
        this.settings = settings;
        this.resolver = resolver;
        this.tls = tls;
    }

    Outcome send(Request request) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        Endpoint endpoint = request.endpoint();
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(endpoint.host());
        } catch (UnknownHostException unknown) {
            return Outcome.failed(TestReason.NAME_NOT_FOUND);
        }
        if (addresses == null || addresses.length == 0) {
            return Outcome.failed(TestReason.NAME_NOT_FOUND);
        }
        if (!endpoint.development()) {
            for (InetAddress address : addresses) {
                if (!HostAddresses.isPublic(address)) {
                    return Outcome.failed(TestReason.ADDRESS_NOT_ALLOWED);
                }
            }
        }
        Outcome last = Outcome.failed(TestReason.CONNECT_FAILED);
        for (InetAddress address : addresses) {
            if (remaining(deadline) <= 0) {
                return Outcome.failed(TestReason.TIMED_OUT);
            }
            last = exchange(request, address, deadline);
            if (last.failure() != TestReason.CONNECT_FAILED) {
                return last;
            }
        }
        return last;
    }

    private Outcome exchange(Request request, InetAddress address, long deadline) {
        Endpoint endpoint = request.endpoint();
        boolean connected = false;
        try (Socket plain = new Socket()) {
            int connectWait = (int) Math.max(1, Math.min(settings.connectTimeout().toMillis(), remaining(deadline)));
            plain.connect(new InetSocketAddress(address, endpoint.port()), connectWait);
            connected = true;
            Socket wire = plain;
            if (endpoint.secure()) {
                wire = secured(plain, endpoint, deadline);
            }
            wire.setSoTimeout((int) Math.max(1, Math.min(settings.readTimeout().toMillis(), remaining(deadline))));
            OutputStream out = wire.getOutputStream();
            out.write(requestBytes(request));
            out.flush();
            return read(wire.getInputStream(), wire, deadline);
        } catch (SocketTimeoutException slow) {
            return Outcome.failed(TestReason.TIMED_OUT);
        } catch (SSLException refused) {
            return Outcome.failed(TestReason.TLS_FAILED);
        } catch (IOException broken) {
            return Outcome.failed(connected ? TestReason.RESPONSE_INVALID : TestReason.CONNECT_FAILED);
        }
    }

    private Socket secured(Socket plain, Endpoint endpoint, long deadline) throws IOException {
        plain.setSoTimeout((int) Math.max(1, Math.min(settings.readTimeout().toMillis(), remaining(deadline))));
        SSLSocket ssl = (SSLSocket) tls.createSocket(plain, endpoint.host(), endpoint.port(), true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
        if (!isLiteral(endpoint.host())) {
            parameters.setServerNames(List.of(new SNIHostName(endpoint.host())));
        }
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    /** An IP address written out: no name to send for server selection. */
    private static boolean isLiteral(String host) {
        return host.contains(":") || host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'));
    }

    private static byte[] requestBytes(Request request) {
        Endpoint endpoint = request.endpoint();
        String path = endpoint.basePath() + request.path();
        StringBuilder text = new StringBuilder(256);
        text.append(request.head() ? "HEAD " : "GET ").append(path).append(" HTTP/1.1\r\n");
        text.append("Host: ").append(endpoint.hostHeader()).append("\r\n");
        text.append("User-Agent: platform-connector\r\n");
        text.append("Accept: */*\r\n");
        text.append("Connection: close\r\n");
        if (request.auth() != null) {
            text.append(request.auth().name()).append(": ").append(request.auth().value()).append("\r\n");
        }
        text.append("\r\n");
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Reads up to the end of the headers and not a byte more than the limit; the body is never read. */
    private Outcome read(InputStream in, Socket wire, long deadline) throws IOException {
        byte[] buffer = new byte[settings.maxHeaderBytes()];
        int length = 0;
        while (true) {
            long left = remaining(deadline);
            if (left <= 0) {
                return Outcome.failed(TestReason.TIMED_OUT);
            }
            wire.setSoTimeout((int) Math.max(1, Math.min(settings.readTimeout().toMillis(), left)));
            int read = in.read(buffer, length, buffer.length - length);
            if (read < 0) {
                return Outcome.failed(TestReason.RESPONSE_INVALID);
            }
            length += read;
            int end = indexOfEnd(buffer, length);
            if (end >= 0) {
                return parse(new String(buffer, 0, end, StandardCharsets.ISO_8859_1));
            }
            if (length == buffer.length) {
                return Outcome.failed(TestReason.RESPONSE_TOO_LARGE);
            }
        }
    }

    private static Outcome parse(String head) {
        int lineEnd = head.indexOf("\r\n");
        String statusLine = lineEnd < 0 ? head : head.substring(0, lineEnd);
        Matcher matcher = STATUS_LINE.matcher(statusLine);
        if (!matcher.matches()) {
            return Outcome.failed(TestReason.RESPONSE_INVALID);
        }
        return Outcome.answered(Integer.parseInt(matcher.group(1)));
    }

    private static int indexOfEnd(byte[] buffer, int length) {
        for (int i = 0; i + END_OF_HEAD.length <= length; i++) {
            if (buffer[i] == '\r' && buffer[i + 1] == '\n' && buffer[i + 2] == '\r' && buffer[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * One request that sends an entity.
     *
     * @param endpoint the checked address
     * @param method the method
     * @param path the path below the endpoint's own path, starting with a slash
     * @param auth the header that carries the credential, or null
     * @param contentType the media type of the body, or null
     * @param body the body, or an empty array
     * @param timeout how long the whole exchange may take
     * @param maxResponseBytes the most bytes of the answer's body that are read
     */
    record Call(Endpoint endpoint, String method, String path, Header auth, String contentType, byte[] body,
            Duration timeout, int maxResponseBytes, Map<String, String> headers) {

        Call {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        /** A call with no extra header. */
        Call(Endpoint endpoint, String method, String path, Header auth, String contentType, byte[] body,
                Duration timeout, int maxResponseBytes) {
            this(endpoint, method, path, auth, contentType, body, timeout, maxResponseBytes, Map.of());
        }

        @Override
        public String toString() {
            return "Call[redacted]";
        }
    }

    /**
     * The answer to a {@link Call}: a status and the body read, or the reason there is none.
     *
     * @param failure the reason, or null when a status was read
     * @param status the status code, or 0 when there is a failure
     * @param body the body, empty when there is none
     */
    record Answer(TestReason failure, int status, byte[] body, Integer retryAfterSeconds) {

        /** An answer that carries no Retry-After. */
        Answer(TestReason failure, int status, byte[] body) {
            this(failure, status, body, null);
        }

        static Answer failed(TestReason reason) {
            return new Answer(reason, 0, new byte[0], null);
        }

        @Override
        public String toString() {
            return "Answer[" + (failure == null ? status : failure.key()) + "]";
        }
    }

    /** Sends one request and reads its answer, under the rules at the top of this class. */
    Answer call(Call call) {
        long deadline = System.nanoTime() + call.timeout().toNanos();
        Endpoint endpoint = call.endpoint();
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(endpoint.host());
        } catch (UnknownHostException unknown) {
            return Answer.failed(TestReason.NAME_NOT_FOUND);
        }
        if (addresses == null || addresses.length == 0) {
            return Answer.failed(TestReason.NAME_NOT_FOUND);
        }
        if (!endpoint.development()) {
            for (InetAddress address : addresses) {
                if (!HostAddresses.isPublic(address)) {
                    return Answer.failed(TestReason.ADDRESS_NOT_ALLOWED);
                }
            }
        }
        Answer last = Answer.failed(TestReason.CONNECT_FAILED);
        for (InetAddress address : addresses) {
            if (remaining(deadline) <= 0) {
                return Answer.failed(TestReason.TIMED_OUT);
            }
            last = callExchange(call, address, deadline);
            if (last.failure() != TestReason.CONNECT_FAILED) {
                return last;
            }
        }
        return last;
    }

    /**
     * A connection that was opened for a transport that speaks its own protocol over it (Sprint 29A, ADR-0237).
     *
     * @param socket the open socket, to be closed by the caller; null when there is a failure
     * @param failure why there is none, or null
     */
    record Opened(Socket socket, TestReason failure) {

        static Opened failed(TestReason reason) {
            return new Opened(null, reason);
        }
    }

    /**
     * Resolves the name, requires <em>every</em> address it resolves to to be public (unless the host is a development
     * host) and opens a socket to the address that was checked, exactly as {@link #call} does for the web. A transport
     * of another protocol (the secure shell of file transfer) gets its socket here and nowhere else, so the same
     * address rules hold for it: the library is handed a connection that is already open and never looks a name up
     * itself.
     */
    Opened open(Endpoint endpoint, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(endpoint.host());
        } catch (UnknownHostException unknown) {
            return Opened.failed(TestReason.NAME_NOT_FOUND);
        }
        if (addresses == null || addresses.length == 0) {
            return Opened.failed(TestReason.NAME_NOT_FOUND);
        }
        if (!endpoint.development()) {
            for (InetAddress address : addresses) {
                if (!HostAddresses.isPublic(address)) {
                    return Opened.failed(TestReason.ADDRESS_NOT_ALLOWED);
                }
            }
        }
        for (InetAddress address : addresses) {
            long left = remaining(deadline);
            if (left <= 0) {
                return Opened.failed(TestReason.TIMED_OUT);
            }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, endpoint.port()),
                        (int) Math.max(1, Math.min(settings.connectTimeout().toMillis(), left)));
                return new Opened(socket, null);
            } catch (SocketTimeoutException slow) {
                closeQuietly(socket);
            } catch (IOException refused) {
                closeQuietly(socket);
            }
        }
        return Opened.failed(TestReason.CONNECT_FAILED);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing more can be done with a socket that will not close.
        }
    }

    private Answer callExchange(Call call, InetAddress address, long deadline) {
        Endpoint endpoint = call.endpoint();
        boolean connected = false;
        try (Socket plain = new Socket()) {
            int connectWait = (int) Math.max(1, Math.min(settings.connectTimeout().toMillis(), remaining(deadline)));
            plain.connect(new InetSocketAddress(address, endpoint.port()), connectWait);
            connected = true;
            Socket wire = plain;
            if (endpoint.secure()) {
                wire = secured(plain, endpoint, deadline);
            }
            wire.setSoTimeout((int) Math.max(1, Math.min(settings.readTimeout().toMillis(), remaining(deadline))));
            OutputStream out = wire.getOutputStream();
            out.write(callHead(call));
            out.write(call.body());
            out.flush();
            return readAnswer(wire.getInputStream(), wire, deadline, "HEAD".equals(call.method()),
                    call.maxResponseBytes());
        } catch (SocketTimeoutException slow) {
            return Answer.failed(TestReason.TIMED_OUT);
        } catch (SSLException refused) {
            return Answer.failed(TestReason.TLS_FAILED);
        } catch (IOException broken) {
            return Answer.failed(connected ? TestReason.RESPONSE_INVALID : TestReason.CONNECT_FAILED);
        }
    }

    private static byte[] callHead(Call call) {
        Endpoint endpoint = call.endpoint();
        StringBuilder text = new StringBuilder(320);
        text.append(call.method()).append(' ').append(endpoint.basePath()).append(call.path())
                .append(" HTTP/1.1\r\n");
        text.append("Host: ").append(endpoint.hostHeader()).append("\r\n");
        text.append("User-Agent: platform-connector\r\n");
        text.append("Accept: application/json\r\n");
        text.append("Connection: close\r\n");
        if (call.contentType() != null && call.body().length > 0) {
            text.append("Content-Type: ").append(call.contentType()).append("\r\n");
        }
        if (!"GET".equals(call.method()) && !"HEAD".equals(call.method()) && !"DELETE".equals(call.method())) {
            text.append("Content-Length: ").append(call.body().length).append("\r\n");
        }
        for (Map.Entry<String, String> extra : call.headers().entrySet()) {
            // Names and values were checked when the call was built (Header); a line break cannot get here.
            text.append(extra.getKey()).append(": ").append(extra.getValue()).append("\r\n");
        }
        if (call.auth() != null) {
            text.append(call.auth().name()).append(": ").append(call.auth().value()).append("\r\n");
        }
        text.append("\r\n");
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Reads the head, then a body no longer than the limit: by length, by chunks or until the other side closes. */
    private Answer readAnswer(InputStream in, Socket wire, long deadline, boolean head, int maxBody)
            throws IOException {
        byte[] buffer = new byte[settings.maxHeaderBytes()];
        int length = 0;
        int end;
        while (true) {
            long left = remaining(deadline);
            if (left <= 0) {
                return Answer.failed(TestReason.TIMED_OUT);
            }
            wire.setSoTimeout((int) Math.max(1, Math.min(settings.readTimeout().toMillis(), left)));
            int read = in.read(buffer, length, buffer.length - length);
            if (read < 0) {
                return Answer.failed(TestReason.RESPONSE_INVALID);
            }
            length += read;
            end = indexOfEnd(buffer, length);
            if (end >= 0) {
                break;
            }
            if (length == buffer.length) {
                return Answer.failed(TestReason.RESPONSE_TOO_LARGE);
            }
        }
        String text = new String(buffer, 0, end, StandardCharsets.ISO_8859_1);
        Outcome status = parse(text);
        if (status.failure() != null) {
            return Answer.failed(status.failure());
        }
        int code = status.status();
        Integer retryAfter = retryAfterOf(text);
        if (head || code == 204 || code == 304 || code < 200) {
            return new Answer(null, code, new byte[0], retryAfter);
        }
        long declared = -1;
        boolean chunked = false;
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
            if (name.equals("content-length")) {
                try {
                    declared = Long.parseLong(value);
                } catch (NumberFormatException invalid) {
                    return Answer.failed(TestReason.RESPONSE_INVALID);
                }
            } else if (name.equals("transfer-encoding") && value.contains("chunked")) {
                chunked = true;
            }
        }
        InputStream rest = new SequenceInputStream(new ByteArrayInputStream(buffer, end + 4, length - end - 4), in);
        wire.setSoTimeout((int) Math.max(1, Math.min(settings.readTimeout().toMillis(), remaining(deadline))));
        byte[] body = chunked ? readChunked(rest, maxBody) : readPlain(rest, declared, maxBody);
        return body == null ? Answer.failed(TestReason.RESPONSE_TOO_LARGE) : new Answer(null, code, body, retryAfter);
    }

    /**
     * The wait the other side asked for with Retry-After, in whole seconds. Only the number-of-seconds form is read; a
     * date, a negative or an absurd number is treated as no request (the caller bounds it by a ceiling anyway).
     */
    private static Integer retryAfterOf(String head) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).strip().equalsIgnoreCase("retry-after")) {
                String value = line.substring(colon + 1).strip();
                if (value.length() >= 1 && value.length() <= 9 && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    return Integer.parseInt(value);
                }
            }
        }
        return null;
    }

    /** Null when the body is longer than the limit. */
    private static byte[] readPlain(InputStream in, long declared, int maxBody) throws IOException {
        if (declared > maxBody) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (declared < 0 || out.size() < declared) {
            int want = declared < 0 ? chunk.length : (int) Math.min(chunk.length, declared - out.size());
            int read = in.read(chunk, 0, want);
            if (read < 0) {
                break;
            }
            out.write(chunk, 0, read);
            if (out.size() > maxBody) {
                return null;
            }
        }
        return out.toByteArray();
    }

    /** Null when the body is longer than the limit. */
    private static byte[] readChunked(InputStream in, int maxBody) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            int semicolon = line.indexOf(';');
            int size;
            try {
                size = Integer.parseInt((semicolon < 0 ? line : line.substring(0, semicolon)).strip(), 16);
            } catch (NumberFormatException invalid) {
                throw new IOException("bad chunk");
            }
            if (size < 0) {
                throw new IOException("bad chunk");
            }
            if (size == 0) {
                while (!readLine(in).isEmpty()) {
                    // trailers are read and dropped
                }
                return out.toByteArray();
            }
            if ((long) out.size() + size > maxBody) {
                return null;
            }
            byte[] chunk = in.readNBytes(size);
            if (chunk.length != size) {
                throw new IOException("short chunk");
            }
            out.write(chunk);
            if (!readLine(in).isEmpty()) {
                throw new IOException("bad chunk end");
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("closed");
            }
            if (next == '\n') {
                int length = line.length();
                return length > 0 && line.charAt(length - 1) == '\r' ? line.substring(0, length - 1) : line.toString();
            }
            line.append((char) next);
            if (line.length() > 1024) {
                throw new IOException("line too long");
            }
        }
    }

    private static long remaining(long deadline) {
        return Duration.ofNanos(deadline - System.nanoTime()).toMillis();
    }
}
