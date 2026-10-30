package app.platform.integration.internal;

import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.ConnectionStore.Definition;
import app.platformapi.ConnectorTypeView;
import app.platformapi.IntegrationView;
import org.springframework.stereotype.Component;

/** What an administrator is shown of a connection. Never a secret: only the name of a credential (ADR-0208). */
@Component
class IntegrationViews {

    private final ConnectorRegistry registry;

    IntegrationViews(ConnectorRegistry registry) {
        this.registry = registry;
    }

    IntegrationView view(Connection connection) {
        Definition d = connection.definition();
        String typeLabel = registry.view(connection.connectorType()).map(ConnectorTypeView::label)
                .orElse(connection.connectorType());
        IntegrationView.LastTest last = connection.lastTestAt() == null ? null : lastTest(connection);
        boolean canEnable = ConnectionMachine.mayEnable(connection.state()) && testedNow(connection)
                && registry.find(connection.connectorType()).isPresent();
        return new IntegrationView(connection.id(), connection.name(), d.label(), connection.connectorType(),
                typeLabel, d.connectorVersion(), d.baseUrl(), d.authKind(), d.authHeaderName(), d.credentialName(),
                d.settings(), d.subscriptions(), d.scope(), connection.state().name(), connection.state().label(),
                last, canEnable, ConnectionMachine.canChange(connection.state()), connection.version());
    }

    /** Whether the current definition has a successful test. The fact the switch-on and the database rest on. */
    static boolean testedNow(Connection connection) {
        return connection.testedFingerprint() != null
                && connection.testedFingerprint().equals(connection.definition().fingerprint());
    }

    private static IntegrationView.LastTest lastTest(Connection connection) {
        TestReason reason = TestReason.ofKey(connection.lastTestReason());
        return new IntegrationView.LastTest(connection.lastTestResult(), reason.key(), reason.words(),
                connection.lastTestAt());
    }
}
