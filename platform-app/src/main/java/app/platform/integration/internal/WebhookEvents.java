package app.platform.integration.internal;

import app.platformapi.WebhookEventView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The closed catalogue of events a webhook may ask for, in one place (Sprint 32, ADR-0275). The screens draw their
 * choices from the catalogue endpoint and hold no list; the database holds the same four keys in a check, so a key that
 * is not here cannot be stored. V1 offers the record events of the public interface and nothing else; an event of an
 * approval, a workflow or an import is a later addition to this list, never a change to a message that exists.
 *
 * <p>{@link #PING} is not asked for: it is the message of the verification and of the test, sent to any address a
 * person is about to switch on.
 */
final class WebhookEvents {

    /** A record was made. */
    static final String CREATED = "record.created";

    /** A record was changed. */
    static final String UPDATED = "record.updated";

    /** A record was removed (to the recycle bin). */
    static final String DELETED = "record.deleted";

    /** A record was brought back from the recycle bin. */
    static final String RESTORED = "record.restored";

    /** The verification and test message: no record, no object. */
    static final String PING = "ping";

    /** The version of the message a delivery carries. A property is only ever added to it. */
    static final int PAYLOAD_VERSION = 1;

    /** The events a subscription may ask for, in the order a screen shows them. */
    static final List<WebhookEventView> CATALOGUE = List.of(
            new WebhookEventView(CREATED, "Record created", "A record of an object was made."),
            new WebhookEventView(UPDATED, "Record changed", "A record of an object was changed."),
            new WebhookEventView(DELETED, "Record removed", "A record of an object was moved to the recycle bin."),
            new WebhookEventView(RESTORED, "Record restored",
                    "A record of an object was brought back from the recycle bin."));

    private static final Set<String> KEYS;

    static {
        Set<String> keys = new LinkedHashSet<>();
        CATALOGUE.forEach(event -> keys.add(event.key()));
        KEYS = Set.copyOf(keys);
    }

    private WebhookEvents() {
    }

    /** Whether the key is an event a subscription may ask for. */
    static boolean known(String key) {
        return key != null && KEYS.contains(key);
    }

    /** The keys of the events a subscription may ask for. */
    static Set<String> keys() {
        return KEYS;
    }
}
