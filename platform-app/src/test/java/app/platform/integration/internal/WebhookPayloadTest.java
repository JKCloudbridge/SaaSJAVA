package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The message of a webhook delivery carries identifiers and nothing else (Sprint 32, ADR-0275): a test fails if a field
 * name or a value can enter it.
 */
class WebhookPayloadTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant AT = Instant.parse("2026-11-23T09:30:00.123456Z");

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void aRecordEventIsTheDeliveryTheTypeTheTimeTheObjectTheRecordAndTheVersion() throws Exception {
        UUID delivery = UUID.randomUUID();
        UUID record = UUID.randomUUID();

        byte[] body = WebhookPayload.record(delivery, "record.updated", AT, "Employee__c", record, 5L);
        JsonNode parsed = JSON.readTree(body);

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"payloadVersion\":1,\"id\":\"" + delivery
                + "\",\"type\":\"record.updated\",\"createdAt\":\"2026-11-23T09:30:00Z\",\"object\":\"Employee__c\","
                + "\"recordId\":\"" + record + "\",\"version\":5}");
        assertThat(names(parsed)).containsExactly("payloadVersion", "id", "type", "createdAt", "object", "recordId",
                "version");
        assertThat(parsed.path("payloadVersion").asInt()).isEqualTo(WebhookEvents.PAYLOAD_VERSION);
    }

    @Test
    void theVersionIsLeftOutWhenTheEventHadNone() throws Exception {
        byte[] body = WebhookPayload.record(UUID.randomUUID(), "record.deleted", AT, "Employee__c", UUID.randomUUID(),
                null);

        assertThat(names(JSON.readTree(body))).doesNotContain("version");
    }

    @Test
    void aPingHasNoObjectAndNoRecord() throws Exception {
        UUID delivery = UUID.randomUUID();

        JsonNode parsed = JSON.readTree(WebhookPayload.ping(delivery, AT));

        assertThat(names(parsed)).containsExactly("payloadVersion", "id", "type", "createdAt");
        assertThat(parsed.path("type").asString()).isEqualTo("ping");
        assertThat(parsed.path("id").asString()).isEqualTo(delivery.toString());
    }

    @Test
    void anEventThatIsNotInTheCatalogueOrAnObjectNameThatIsNotAnApiNameCannotEnter() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> WebhookPayload.record(id, "record.purged", AT, "Employee__c", id, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookPayload.record(id, "ping", AT, "Employee__c", id, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookPayload.record(id, "record.created", AT, "Employee__c\",\"name\":\"Ada", id,
                1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookPayload.record(id, "record.created", AT, "a b", id, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookPayload.record(id, "record.created", AT, null, id, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOnlyTypesTheBuilderTakesAreIdentifiersTimesAndNumbers() throws Exception {
        // The signature of the builder is the proof that no text of a record can be passed: no String but the event key
        // and the API name, both checked against the catalogue and the shape of a name.
        for (java.lang.reflect.Method method : WebhookPayload.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isSynthetic()
                    || java.lang.reflect.Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            long strings = java.util.Arrays.stream(method.getParameterTypes()).filter(String.class::equals).count();
            assertThat(strings).as(method.getName()).isLessThanOrEqualTo(2);
            assertThat(java.util.Arrays.stream(method.getParameterTypes()))
                    .noneMatch(type -> type == Object.class || type == java.util.Map.class
                            || java.util.Collection.class.isAssignableFrom(type) || type == byte[].class);
        }
    }

    @Property
    void whateverTheApiNameTheBodyHasExactlyTheDocumentedProperties(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String name,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long version) throws Exception {
        byte[] body = WebhookPayload.record(UUID.randomUUID(), "record.created", AT, name, UUID.randomUUID(), version);

        assertThat(names(JSON.readTree(body))).containsExactly("payloadVersion", "id", "type", "createdAt", "object",
                "recordId", "version");
    }
}
