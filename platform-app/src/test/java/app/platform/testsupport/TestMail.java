package app.platform.testsupport;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One real SMTP mail catcher for the whole integration-test run, started once and shared (the notification module of
 * Sprint 4 is the first use). Messages are read back through its HTTP interface. The container can be paused to stand
 * in for a mail server outage; {@link #resume()} brings it back.
 *
 * <p>Tests share one mailbox, so each test uses an address of its own and looks only at messages for it.
 */
public final class TestMail {

    // The image is the one the local environment runs, read from its compose file: one place names it, and that place
    // is an infrastructure file.
    private static final Path COMPOSE_FILE = Path.of("../infra/local/docker-compose.yml");
    private static final GenericContainer<?> CATCHER = new GenericContainer<>(catcherImage())
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern LINK = Pattern.compile("(https?://\\S+#token=[A-Za-z0-9_-]+)");

    static {
        CATCHER.start();
    }

    private TestMail() {
    }

    private static String catcherImage() {
        try {
            Matcher matcher = Pattern.compile("(?m)^\\s*image:\\s*(\\S*mail\\S*)\\s*$")
                    .matcher(Files.readString(COMPOSE_FILE));
            if (!matcher.find()) {
                throw new IllegalStateException("No mail catcher image in " + COMPOSE_FILE);
            }
            return matcher.group(1);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + COMPOSE_FILE, e);
        }
    }

    /** One message as the catcher stored it. */
    public record Message(String id, String to, String from, String subject, String text, String html) {

        /** The link with a token in the plain-text body, if there is one. */
        public Optional<String> link() {
            Matcher matcher = LINK.matcher(text);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }

        /** The token of that link (the part after {@code #token=}). */
        public Optional<String> token() {
            return link().map(link -> link.substring(link.indexOf("#token=") + "#token=".length()));
        }
    }

    /** Host of the SMTP server. */
    public static String host() {
        return CATCHER.getHost();
    }

    /** Mapped port of the SMTP server. */
    public static int smtpPort() {
        return CATCHER.getMappedPort(1025);
    }

    /** Stops the container's processes, so connections hang and time out: a mail server outage. */
    public static void pause() {
        CATCHER.getDockerClient().pauseContainerCmd(CATCHER.getContainerId()).exec();
    }

    /** Brings the container back after {@link #pause()}. */
    public static void resume() {
        CATCHER.getDockerClient().unpauseContainerCmd(CATCHER.getContainerId()).exec();
    }

    /** All messages addressed to the address, oldest first. */
    public static List<Message> to(String address) {
        // The catcher's identifiers are random, so messages are put in order by the time it received them.
        List<Map.Entry<String, Message>> found = new ArrayList<>();
        JsonNode list = get("/api/v1/search?query=" + java.net.URLEncoder.encode("to:" + address,
                java.nio.charset.StandardCharsets.UTF_8));
        for (JsonNode summary : list.path("messages")) {
            JsonNode full = get("/api/v1/message/" + summary.path("ID").asString());
            found.add(Map.entry(summary.path("Created").asString(), new Message(full.path("ID").asString(), address,
                    full.path("From").path("Address").asString(), full.path("Subject").asString(),
                    full.path("Text").asString(), full.path("HTML").asString())));
        }
        found.sort(Map.Entry.comparingByKey());
        return found.stream().map(Map.Entry::getValue).toList();
    }

    /** Everything the catcher holds, as one text, for "this secret appears nowhere" checks. */
    public static String everythingAsText() {
        return get("/api/v1/messages?limit=1000").toString();
    }

    private static JsonNode get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + CATCHER.getHost() + ":"
                    + CATCHER.getMappedPort(8025) + path)).timeout(Duration.ofSeconds(10)).GET().build();
            return JSON.readTree(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body());
        } catch (IOException e) {
            throw new IllegalStateException("The mail catcher did not answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while asking the mail catcher", e);
        }
    }
}
