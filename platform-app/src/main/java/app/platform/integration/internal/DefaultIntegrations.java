package app.platform.integration.internal;

import app.platform.integration.Integrations;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platformapi.IntegrationView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the two questions of {@link Integrations} from the stored connections. Nothing is cached: a connection
 * switched off is unusable at the next question, on every instance.
 */
@Service
@Transactional(readOnly = true)
class DefaultIntegrations implements Integrations {

    private final ConnectionStore store;

    DefaultIntegrations(ConnectionStore store) {
        this.store = store;
    }

    @Override
    public List<UUID> subscribersOf(String event, String object) {
        return store.enabled().stream().filter(connection -> connection.definition().subscriptions().stream()
                .anyMatch(subscription -> subscription.event().equals(event)
                        && subscription.object().equals(object))).map(Connection::id).toList();
    }

    @Override
    public boolean allows(UUID connection, String object, ScopeAction action) {
        return store.findById(connection).filter(found -> ConnectionMachine.isUsable(found.state()))
                .map(found -> found.definition().scope().stream()
                        .anyMatch(entry -> entry.object().equals(object) && permits(entry, action)))
                .orElse(false);
    }

    private static boolean permits(IntegrationView.ScopeEntry entry, ScopeAction action) {
        return switch (action) {
            case READ -> Boolean.TRUE.equals(entry.read());
            case CREATE -> Boolean.TRUE.equals(entry.create());
            case UPDATE -> Boolean.TRUE.equals(entry.update());
        };
    }
}
