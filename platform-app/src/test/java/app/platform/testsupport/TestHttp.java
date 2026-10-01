package app.platform.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Minimal HTTP client for integration tests: returns every status (no exceptions on 4xx or 5xx) and the headers. */
public final class TestHttp {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    /** A client for a server on localhost. */
    public TestHttp(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    /** Sends a GET request. {@code headers} are name/value pairs. */
    public Response get(String path, String... headers) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET(), headers);
    }

    /** Sends a POST request with a body. {@code headers} are name/value pairs. */
    public Response post(String path, String body, String... headers) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)), headers);
    }

    private Response send(HttpRequest.Builder builder, String... headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = client.send(
                    builder.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    /**
     * A response.
     *
     * @param status the HTTP status
     * @param headers all headers, names in lower case as the client reports them
     * @param body the body text
     */
    public record Response(int status, Map<String, java.util.List<String>> headers, String body) {

        /** The first value of a header, if present. */
        public Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst();
        }
    }
}
