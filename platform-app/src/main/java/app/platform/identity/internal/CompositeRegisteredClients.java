package app.platform.identity.internal;

import java.util.List;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * The one client repository of the authorization server (Sprint 31): the platform's own web client first, then the
 * connected applications of the organization the host names. The first answer wins; nothing is ever saved through it.
 */
final class CompositeRegisteredClients implements RegisteredClientRepository {

    private final List<RegisteredClientRepository> parts;

    CompositeRegisteredClients(List<RegisteredClientRepository> parts) {
        this.parts = List.copyOf(parts);
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Clients are registered through their own services.");
    }

    @Override
    public RegisteredClient findById(String id) {
        for (RegisteredClientRepository part : parts) {
            RegisteredClient found = part.findById(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        for (RegisteredClientRepository part : parts) {
            RegisteredClient found = part.findByClientId(clientId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
