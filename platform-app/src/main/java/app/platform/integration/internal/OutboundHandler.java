package app.platform.integration.internal;

import app.platform.integration.Integrations;
import app.platform.integration.Integrations.ScopeAction;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.MappingStore.Live;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the event of a change to a record into sends (Sprint 28, ADR-0220): for every switched-on connection that
 * listens for the event on the object and has a live mapping for it, one delivery is started, once per event and
 * connection, and the send itself is made later by {@link OutboundWorker}, from the queue, on any instance.
 *
 * <p><strong>Cheap by construction.</strong> Every record write announces an event, so this handler runs for every
 * write of every organization. An organization with no switched-on connection that listens costs one read of the
 * connections; nothing is added to the write path, and this runs after the commit of the write, in the outbox relay.
 *
 * <p><strong>No echo (ADR-0222).</strong> A change made by the receiver of a connection carries that connection in the
 * chain of its event, so the same connection is not sent its own change back: a delivery is recorded as skipped with
 * the reason {@code echo}. Another connection that listens still gets it.
 *
 * <p>The event carries the names of the changed fields and never their values; the record is read when the send is
 * prepared. Nothing here logs a value; a payload this handler cannot read is counted and skipped.
 */
@Component
class OutboundHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OutboundHandler.class);
    private static final Set<String> TYPES = Set.of("record.created", "record.updated", "record.deleted");
    private static final UUID ACTOR = ActorId.SYSTEM.value();

    private final ConnectionStore connections;
    private final MappingStore mappings;
    private final DeliveryStore deliveries;
    private final Integrations integrations;
    private final IntegrationProperties.Reliability reliability;
    private final MeterRegistry meters;
    private final JsonMapper json = JsonMapper.builder().build();

    OutboundHandler(ConnectionStore connections, MappingStore mappings, DeliveryStore deliveries,
            Integrations integrations, IntegrationProperties properties, MeterRegistry meters) {
        this.connections = connections;
        this.mappings = mappings;
        this.deliveries = deliveries;
        this.integrations = integrations;
        this.reliability = properties.reliability();
        this.meters = meters;
    }

    @Override
    public String consumerName() {
        return "integration.outbound";
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode payload;
        String object;
        UUID record;
        try {
            payload = json.readTree(event.payload());
            object = payload.path("object").asString("");
            record = UUID.fromString(payload.path("recordId").asString(""));
        } catch (RuntimeException unreadable) {
            LOG.warn("A record event the integrations cannot read was skipped: type {}", event.type());
            count("malformed");
            return;
        }
        if (object.isEmpty()) {
            count("malformed");
            return;
        }
        Set<UUID> origin = chainOf(payload);
        boolean any = false;
        for (Connection connection : connections.enabled()) {
            if (!listensTo(connection, event.type(), object)) {
                continue;
            }
            any = true;
            decide(event, connection, object, record, origin);
        }
        count(any ? "handled" : "no_subscriber");
    }

    private void decide(EventEnvelope event, Connection connection, String object, UUID record, Set<UUID> origin) {
        Optional<Live> live = mappings.liveFor(connection.id(), object);
        if (live.isEmpty()) {
            count("no_mapping");
            return;
        }
        UUID version = live.get().version().id();
        String kind = kind(event.type());
        if (origin.contains(connection.id())) {
            deliveries.skipped(connection.id(), version, "OUT", kind, event.eventId(), object, record,
                    FlowReason.ECHO.key(), ACTOR);
            count("echo");
            return;
        }
        if (!integrations.allows(connection.id(), object, ScopeAction.READ)) {
            deliveries.skipped(connection.id(), version, "OUT", kind, event.eventId(), object, record,
                    FlowReason.OUT_OF_SCOPE.key(), ACTOR);
            count("out_of_scope");
            return;
        }
        int bound = reliability.queue().maxPerConnection();
        if (deliveries.openCount(connection.id(), bound) >= bound) {
            // Refused visibly, never dropped: the change is recorded as a dead letter that can be sent again.
            Optional<UUID> refused = deliveries.deadLettered(connection.id(), version, kind, event.eventId(), object,
                    record, FlowReason.QUEUE_FULL.key(), ACTOR);
            if (refused.isPresent()) {
                meters.counter("platform.integration.dead").increment();
                count("queue_full");
            }
            return;
        }
        Optional<UUID> started = deliveries.start(connection.id(), version, "OUT", kind, event.eventId(), object,
                record, ACTOR);
        if (started.isEmpty()) {
            count("duplicate");
            return;
        }
        if (!"CREATE".equals(kind)) {
            // Latest state wins: a send reads the record as it is when it is made, so an older change that is still
            // only waiting says nothing the newer one does not (ADR-0230).
            int replaced = deliveries.supersedeOlder(connection.id(), object, record, started.get());
            if (replaced > 0) {
                meters.counter("platform.integration.superseded").increment(replaced);
            }
        }
        count("started");
    }

    private static boolean listensTo(Connection connection, String event, String object) {
        return connection.definition().subscriptions().stream()
                .anyMatch(subscription -> subscription.event().equals(event) && subscription.object().equals(object));
    }

    private static String kind(String eventType) {
        return switch (eventType) {
            case "record.created" -> "CREATE";
            case "record.deleted" -> "DELETE";
            default -> "UPDATE";
        };
    }

    /** The connections whose receiver made the change this event announces; empty for a person's change. */
    private static Set<UUID> chainOf(JsonNode payload) {
        Set<UUID> origin = new HashSet<>();
        try {
            payload.path("chain").path("workflows").forEach(id -> origin.add(UUID.fromString(id.asString(""))));
        } catch (IllegalArgumentException unreadable) {
            origin.clear();
        }
        return origin;
    }

    private void count(String result) {
        meters.counter("platform.integration.outbound.events", "result", result).increment();
    }
}
