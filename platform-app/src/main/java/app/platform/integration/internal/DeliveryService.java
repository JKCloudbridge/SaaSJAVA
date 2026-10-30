package app.platform.integration.internal;

import app.platform.integration.internal.DeliveryStore.Delivery;
import app.platform.security.Ability;
import app.platformapi.ApiException;
import app.platformapi.DeliveryView;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists what was sent and received (Sprint 28, ADR-0220): the newest deliveries of a connection, as facts. A delivery
 * that is still pending long after it should have ended is shown as interrupted: the instance that began it stopped,
 * and the platform does not repeat a send that may have reached the other side.
 */
@Service
@Transactional(readOnly = true)
class DeliveryService {

    private static final int MAX = 100;

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

    List<DeliveryView> recent(String connectionName, int limit) {
        access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW);
        var connection = connections.find(connectionName)
                .orElseThrow(() -> ApiException.notFound("This connection does not exist."));
        return store.recent(connection.id(), Math.max(1, Math.min(limit, MAX))).stream().map(this::view).toList();
    }

    private DeliveryView view(Delivery delivery) {
        boolean stale = "PENDING".equals(delivery.state())
                && delivery.createdAt().plus(properties.flow().staleAfter()).isBefore(clock.instant());
        return new DeliveryView(delivery.id(), delivery.direction(), delivery.kind(), delivery.object(),
                delivery.recordId(), delivery.state(), delivery.reason(), FlowReason.wordsFor(delivery.reason()),
                delivery.httpStatus(), delivery.createdAt(), delivery.finishedAt(), stale);
    }
}
