package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rule of ADR-0220 and ADR-0234 held by a test (Sprint 29): only {@code DeliveryStore} writes a statement against
 * the delivery table, only {@code AttemptStore} against the attempt log and only {@code FlowStateStore} against the
 * flow state. Every statement is a constant with parameters, the organization is named in one place, and the attempt
 * log can only ever be written with identifiers, enumerations and numbers.
 */
class OnlyTheQueueStoresNameTheTablesTest {

    private static final Path INTEGRATION = Path.of("src/main/java/app/platform/integration");
    private static final Map<String, String> OWNERS = Map.of(
            "integration_delivery", "DeliveryStore.java",
            "integration_attempt", "AttemptStore.java",
            "integration_flow_state", "FlowStateStore.java",
            "integration_poll", "PollStore.java",
            "integration_file", "FileLedgerStore.java",
            "webhook_subscription", "WebhookSubscriptionStore.java",
            "webhook_delivery", "WebhookDeliveryStore.java",
            "webhook_attempt", "WebhookAttemptStore.java");

    @Test
    void noOtherClassOfTheModuleWritesAStatementAgainstAQueueTable() throws IOException {
        for (Map.Entry<String, String> owner : OWNERS.entrySet()) {
            Pattern statement = Pattern.compile("(?i)(from|join|into|update|delete\\s+from|table)\\s+"
                    + owner.getKey() + "\\b");
            List<Path> offenders;
            try (Stream<Path> files = Files.walk(INTEGRATION)) {
                offenders = files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals(owner.getValue()))
                        .filter(path -> mentions(path, statement)).toList();
            }
            assertThat(offenders).as("classes that name %s in a statement", owner.getKey()).isEmpty();
        }
    }

    @Test
    void eachStoreDoesNameItsTable() throws IOException {
        for (Map.Entry<String, String> owner : OWNERS.entrySet()) {
            assertThat(Files.readString(INTEGRATION.resolve("internal/" + owner.getValue())))
                    .contains(owner.getKey());
        }
    }

    @Test
    void theAttemptStoreCanWriteNoTextButAReason() throws IOException {
        String source = Files.readString(INTEGRATION.resolve("internal/AttemptStore.java"));

        assertThat(source).contains("String reason, String statusClass, Integer httpStatus");
        assertThat(source).doesNotContain("byte[]").doesNotContain("payload").doesNotContain("header");
    }

    private static boolean mentions(Path path, Pattern statement) {
        try {
            return statement.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
