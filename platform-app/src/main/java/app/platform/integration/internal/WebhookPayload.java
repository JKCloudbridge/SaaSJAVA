package app.platform.integration.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The exact message of a webhook delivery (Sprint 32, ADR-0275): identifiers and nothing else. It is made from typed
 * parts only (a delivery identifier, an event key of the catalogue, a time, an object API name, a record identifier and
 * a version number), each checked against the shape of what it is, so no value, title, field name or typed word can
 * enter it however a caller tries; the signature is computed over exactly these bytes, which are written in a fixed
 * order so that the same delivery always has the same body.
 *
 * <pre>{"payloadVersion":1,"id":"&lt;delivery&gt;","type":"record.updated","createdAt":"2026-11-23T09:30:00Z",
 * "object":"Employee__c","recordId":"&lt;record&gt;","version":5}</pre>
 *
 * <p>The receiver fetches the record through the public API under its own credentials, so what it learns is decided by
 * the permissions of the member behind that credential and never by this message. {@code version} is left out when the
 * event did not carry one. The message of {@link WebhookEvents#PING} has no object and no record.
 */
final class WebhookPayload {

    private static final Pattern OBJECT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,79}");

    private WebhookPayload() {
    }

    /**
     * The body of a record event.
     *
     * @param delivery the delivery identifier, which the receiver deduplicates by
     * @param type an event key of the catalogue
     * @param createdAt when the delivery was made from the event
     * @param object the API name of the object
     * @param record the record
     * @param version the version of the record at the event, or null
     */
    static byte[] record(UUID delivery, String type, Instant createdAt, String object, UUID record, Long version) {
        if (!WebhookEvents.known(type) || object == null || !OBJECT.matcher(object).matches()) {
            throw new IllegalArgumentException("A webhook message names an event of the catalogue and an object");
        }
        StringBuilder text = new StringBuilder(220);
        head(text, delivery, type, createdAt);
        text.append(",\"object\":\"").append(object).append("\",\"recordId\":\"").append(record).append('"');
        if (version != null) {
            text.append(",\"version\":").append(version.longValue());
        }
        return text.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The body of the verification and test message: no object and no record. */
    static byte[] ping(UUID delivery, Instant createdAt) {
        StringBuilder text = new StringBuilder(120);
        head(text, delivery, WebhookEvents.PING, createdAt);
        return text.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void head(StringBuilder text, UUID delivery, String type, Instant createdAt) {
        text.append("{\"payloadVersion\":").append(WebhookEvents.PAYLOAD_VERSION).append(",\"id\":\"").append(delivery)
                .append("\",\"type\":\"").append(type).append("\",\"createdAt\":\"")
                .append(createdAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).append('"');
    }
}
