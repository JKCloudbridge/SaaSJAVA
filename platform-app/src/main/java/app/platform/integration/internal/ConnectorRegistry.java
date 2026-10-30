package app.platform.integration.internal;

import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.ConnectorDescription;
import app.platformapi.ConnectorTypeView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The connector types this release ships: every {@link Connector} bean, found by its key (ADR-0207). Like the
 * catalogue of workflow actions it is closed and fixed at start; a duplicate key or an event the platform does not
 * offer stops the start with a message that names the type, so a broken adapter is found at deployment and never by a
 * customer.
 */
@Component
final class ConnectorRegistry {

    /** The platform events a connection may listen for. A connector offers a subset. */
    static final Set<String> SUBSCRIBABLE_EVENTS = Set.of("record.created", "record.updated", "record.deleted");

    private final Map<String, Connector> byKey;

    ConnectorRegistry(List<Connector> connectors) {
        Map<String, Connector> found = new LinkedHashMap<>();
        for (Connector connector : connectors) {
            ConnectorDescription description = connector.describe();
            if (found.put(description.key(), connector) != null) {
                throw new IllegalStateException("Two connectors declare the key " + description.key());
            }
            for (ConnectorDescription.EventSpec event : description.platformEvents()) {
                if (!SUBSCRIBABLE_EVENTS.contains(event.key())) {
                    throw new IllegalStateException(
                            "The connector " + description.key() + " names an event the platform does not offer");
                }
            }
        }
        this.byKey = Map.copyOf(found);
    }

    Optional<Connector> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** The descriptions in key order, as the catalogue endpoint shows them. */
    List<ConnectorTypeView> views() {
        return byKey.values().stream().map(Connector::describe)
                .sorted(java.util.Comparator.comparing(ConnectorDescription::key)).map(ConnectorRegistry::view)
                .toList();
    }

    Optional<ConnectorTypeView> view(String key) {
        return find(key).map(connector -> view(connector.describe()));
    }

    private static ConnectorTypeView view(ConnectorDescription description) {
        return new ConnectorTypeView(description.key(), description.label(), description.description(),
                description.version(),
                description.authKinds().stream()
                        .map(kind -> new ConnectorTypeView.ConnectorChoice(kind.name(), kind.label())).toList(),
                description.settings().stream().map(spec -> new ConnectorTypeView.Setting(spec.key(), spec.label(),
                        spec.hint(), spec.kind().name(), spec.required(), spec.defaultValue(),
                        spec.choices().stream()
                                .map(c -> new ConnectorTypeView.ConnectorChoice(c.key(), c.label())).toList(),
                        spec.min(), spec.max())).toList(),
                description.platformEvents().stream()
                        .map(event -> new ConnectorTypeView.ConnectorChoice(event.key(), event.label())).toList());
    }
}
