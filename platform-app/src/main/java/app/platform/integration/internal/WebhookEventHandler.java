package app.platform.integration.internal;

import app.platform.integration.internal.WebhookSubscriptionStore.Subscription;
import app.platform.licensing.Entitlements;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the event of a change to a record into deliveries (Sprint 32, ADR-0278): for every switched-on subscription
 * that
 * asks for the event and is not limited to other objects, one delivery is queued, once per event and subscription, and
 * the call itself is made later by {@link WebhookWorker}, outside any transaction, on any instance. This handler only
 * writes a row and returns; it never calls out and never judges who may see the record, because an event has no viewer:
 * that is asked when the delivery is made ({@link WebhookVisibility}).
 *
 * <p><strong>Cheap by construction.</strong> Every record write announces an event, so this runs for every write of
 * every organization. An organization with no switched-on subscription costs one read of the matching subscriptions;
 * nothing is added to the write path, and this runs after the commit of the write, in the outbox relay.
 *
 * <p>The event carries the names of the changed fields and never their values; a delivery carries neither. Nothing here
 * logs an object, a record or an address: an event this handler cannot read is counted and skipped, by type only.
 */
@Component
class WebhookEventHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookEventHandler.class);
    private static final Set<String> TYPES = Set.of(WebhookEvents.CREATED, WebhookEvents.UPDATED,
            WebhookEvents.DELETED, WebhookEvents.RESTORED);
    private static final String FEATURE = "integrations";

    private final WebhookSubscriptionStore subscriptions;
    private final WebhookDeliveryStore deliveries;
    private final WebhookProperties properties;
    private final Entitlements entitlements;
    private final MeterRegistry meters;
    private final JsonMapper json = JsonMapper.builder().build();

    WebhookEventHandler(WebhookSubscriptionStore subscriptions, WebhookDeliveryStore deliveries,
            WebhookProperties properties, Entitlements entitlements, MeterRegistry meters) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.properties = properties;
        this.entitlements = entitlements;
        this.meters = meters;
    }

    @Override
    public String consumerName() {
        return "integration.webhooks";
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public void handle(EventEnvelope event) {
        if (!properties.enabled()) {
            count("switched_off");
            return;
        }
        String object;
        UUID record;
        Long version = null;
        try {
            JsonNode payload = json.readTree(event.payload());
            object = payload.path("object").asString("");
            record = UUID.fromString(payload.path("recordId").asString(""));
            JsonNode number = payload.path("version");
            if (number.isIntegralNumber()) {
                version = number.asLong();
            }
        } catch (RuntimeException unreadable) {
            LOG.warn("A record event the webhooks cannot read was skipped: type {}", event.type());
            count("malformed");
            return;
        }
        if (object.isEmpty()) {
            count("malformed");
            return;
        }
        if (!entitlements.enabled(FEATURE)) {
            count("no_feature");
            return;
        }
        boolean any = false;
        for (Subscription subscription : subscriptions.matching(event.type(), object)) {
            any = true;
            queue(event, subscription, object, record, version);
        }
        count(any ? "handled" : "no_subscriber");
    }

    private void queue(EventEnvelope event, Subscription subscription, String object, UUID record, Long version) {
        int bound = properties.queue().maxPerSubscription();
        if (deliveries.openCount(subscription.id(), bound) >= bound) {
            // Refused visibly, never dropped: the event is recorded as a dead letter that can be sent again.
            Optional<UUID> refused = deliveries.deadLettered(subscription.id(), event.eventId(), event.type(), object,
                    record, version, FlowReason.QUEUE_FULL.key());
            if (refused.isPresent()) {
                meters.counter("platform.webhook.deliveries", "result", "queue_full").increment();
            }
            return;
        }
        Optional<UUID> started = deliveries.start(subscription.id(), event.eventId(), event.type(), object, record,
                version);
        if (started.isEmpty()) {
            // The outbox handed over an event it had handed over before: one event, one delivery.
            meters.counter("platform.webhook.deliveries", "result", "duplicate").increment();
            return;
        }
        if (WebhookEvents.UPDATED.equals(event.type())) {
            // Latest state wins: a delivery names a record and never a value, so an older update that is still only
            // waiting says nothing the newer one does not (ADR-0278).
            int replaced = deliveries.supersedeOlder(subscription.id(), object, record, started.get());
            if (replaced > 0) {
                meters.counter("platform.webhook.superseded").increment(replaced);
            }
        }
        meters.counter("platform.webhook.deliveries", "result", "queued").increment();
    }

    private void count(String result) {
        meters.counter("platform.webhook.events", "result", result).increment();
    }
}
