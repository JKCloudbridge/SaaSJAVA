package app.platform.integration.internal;

import app.platform.integration.internal.EndpointRules.Endpoint;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
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
 * <p>Nothing from the other side is kept: not the body, not a header, not an error message. The outcome is a status
 * number or a stable reason.
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

    private static long remaining(long deadline) {
        return Duration.ofNanos(deadline - System.nanoTime()).toMillis();
    }
}
