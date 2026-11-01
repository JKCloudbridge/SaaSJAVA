package app.platform.integration.internal;

import app.platform.integration.internal.DeliveryStore.Delivery;
import app.platform.security.Ability;
import app.platformapi.ApiException;
import app.platformapi.DeliveryView;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists what was sent and received (Sprint 28, ADR-0220; the queue, Sprint 29, ADR-0230): the newest deliveries of a
 * connection, as facts, optionally only those in some states. An open delivery long overdue is shown as such.
 */
@Service
@Transactional(readOnly = true)
class DeliveryService {

    private static final int MAX = 100;
    private static final Set<String> STATES = Set.of("PENDING", "SENDING", "WAITING_REMOTE", "DONE", "FAILED",
            "SKIPPED", "SUPERSEDED", "DEAD_LETTERED");

    /** What an open delivery waits for, in the platform's words. */
    private static final Map<String, String> WAITING = Map.of(
            FlowReason.CIRCUIT_OPEN.key(), FlowReason.CIRCUIT_OPEN.words(),
            FlowReason.THROTTLED.key(), FlowReason.THROTTLED.words(),
            FlowReason.CONNECTION_OFF.key(), FlowReason.CONNECTION_OFF.words(),
            "backoff", FlowReason.BACKOFF.words(),
            "replayed", FlowReason.REPLAYED.words(),
            "waiting_remote", "The service accepted the work and has not finished it yet.");

    private final ConnectionStore connections;
    private final DeliveryStore store;
    private final IntegrationAccess access;
    private final IntegrationProperties properties;
    private final Clock clock;

    DeliveryService(ConnectionStore connections, DeliveryStore store, IntegrationAccess access,
            IntegrationProperties properties, Clock clock) {
        this.connections = connections;
        this.store = store;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
    }

    List<DeliveryView> recent(String connectionName, List<String> states, int limit) {
        access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW);
        List<String> wanted = states.stream().map(String::strip).filter(state -> !state.isEmpty()).toList();
        if (!STATES.containsAll(wanted)) {
            throw ApiException.validation(Map.of("state", List.of("Use one of the states the screen offers.")));
        }
        boolean canReplay = access.can(Ability.INTEGRATIONS_REPLAY);
        var connection = connections.find(connectionName)
                .orElseThrow(() -> ApiException.notFound("This connection does not exist."));
        return store.recent(connection.id(), wanted, Math.max(1, Math.min(limit, MAX))).stream()
                .map(delivery -> view(delivery, canReplay)).toList();
    }

    /** One delivery as the screens show it; {@code replayable} says whether the caller may send a dead letter again. */
    DeliveryView view(Delivery delivery, boolean replayable) {
        DeliveryState state = DeliveryState.valueOf(delivery.state());
        boolean stale = state.open() && delivery.runAt().plus(properties.flow().staleAfter()).isBefore(clock.instant());
        String waiting = state.open() ? delivery.queuedReason() : null;
        return new DeliveryView(delivery.id(), delivery.direction(), delivery.kind(), delivery.object(),
                delivery.recordId(), delivery.state(), delivery.reason(), FlowReason.wordsFor(delivery.reason()),
                delivery.httpStatus(), delivery.createdAt(), delivery.finishedAt(), stale, delivery.attempts(),
                delivery.generation(), state.open() ? delivery.runAt() : null, waiting,
                waiting == null ? null : WAITING.getOrDefault(waiting, FlowReason.UNEXPECTED.words()),
                delivery.lastStatusClass(), replayable && state == DeliveryState.DEAD_LETTERED);
    }
}
