package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platformapi.WebhookEventView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The closed catalogue of events (Sprint 32, ADR-0275): one place defines it, the handler listens for exactly those
 * events, the database holds the same keys, and every entry is words for a person.
 */
class WebhookCatalogueTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V096__create_webhooks_and_rate_limit_overrides.sql");

    @Test
    void theCatalogueHasTheRecordEventsOfThePublicInterfaceAndNothingElse() {
        assertThat(WebhookEvents.CATALOGUE).extracting(WebhookEventView::key).containsExactly("record.created",
                "record.updated", "record.deleted", "record.restored");
        assertThat(WebhookEvents.known("ping")).as("the ping is sent, never asked for").isFalse();
        assertThat(WebhookEvents.known("record.purged")).isFalse();
        assertThat(WebhookEvents.known(null)).isFalse();
        assertThat(WebhookEvents.PAYLOAD_VERSION).isEqualTo(1);
    }

    @Test
    void everyEntryHasAKeyATitleAndADescription() {
        Set<String> keys = new HashSet<>();
        for (WebhookEventView event : WebhookEvents.CATALOGUE) {
            assertThat(event.key()).matches("record\\.[a-z]+");
            assertThat(event.title()).isNotBlank();
            assertThat(event.description()).isNotBlank().endsWith(".");
            assertThat(keys.add(event.key())).as("no key twice").isTrue();
        }
        assertThat(WebhookEvents.keys()).isEqualTo(keys);
    }

    @Test
    void theDatabaseHoldsTheSameKeysInTheSubscriptionAndInTheDelivery() throws IOException {
        String sql = Files.readString(MIGRATION);
        Set<String> expected = WebhookEvents.keys();

        assertThat(quoted(sql, "events <@ array\\[([^\\]]*)\\]")).isEqualTo(expected);
        assertThat(quoted(sql, "event_type in \\(([^)]*)\\)")).isEqualTo(expected);
    }

    @Test
    void theHandlerListensForExactlyTheEventsOfTheCatalogue() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/app/platform/integration/internal/WebhookEventHandler.java"));

        assertThat(source).contains("WebhookEvents.CREATED, WebhookEvents.UPDATED,")
                .contains("WebhookEvents.DELETED, WebhookEvents.RESTORED");
    }

    @Test
    void everyReasonKeyHasTheShapeTheDatabaseChecksAndWordsOfItsOwn() {
        List<String> keys = java.util.Arrays.stream(WebhookReason.values()).map(WebhookReason::key).toList();

        assertThat(keys).allMatch(key -> key.matches("[a-z_]{1,40}"));
        assertThat(keys).doesNotHaveDuplicates();
        for (WebhookReason reason : WebhookReason.values()) {
            assertThat(reason.words()).isNotBlank().endsWith(".");
        }
    }

    private static Set<String> quoted(String sql, String regex) {
        Matcher block = Pattern.compile(regex).matcher(sql);
        assertThat(block.find()).as(regex).isTrue();
        Set<String> found = new HashSet<>();
        Matcher item = Pattern.compile("'([^']+)'").matcher(block.group(1));
        while (item.find()) {
            found.add(item.group(1));
        }
        return found;
    }
}
