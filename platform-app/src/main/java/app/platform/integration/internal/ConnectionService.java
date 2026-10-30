package app.platform.integration.internal;

import app.platform.integration.adapter.Connector;
import app.platform.integration.internal.ConnectionMachine.Event;
import app.platform.integration.internal.ConnectionMachine.State;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.ConnectionStore.Definition;
import app.platform.security.Ability;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platformapi.ApiException;
import app.platformapi.ConnectorTypeView;
import app.platformapi.ErrorCode;
import app.platformapi.IntegrationView;
import app.platformapi.SaveIntegrationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The definition and the life of a connection (ADR-0208, ADR-0210): create, read, change, switch on and off, remove.
 * Testing is {@link ConnectionTesting}, because a test must not hold a transaction while it waits for another system.
 *
 * <p>Every call decides who may ask first (membership, ability, plan feature), then what is allowed in the state the
 * connection is in; the same table is held by the database. Nothing here logs, and the audit and the metrics carry
 * identifiers, keys and counts only: never an address, a name chosen by the organization or the name of a credential.
 */
@Service
@Transactional
class ConnectionService {

    private static final int CLASH_TRIES = 3;
    private final ConnectionStore store;
    private final ConnectorRegistry registry;
    private final DefinitionChecks checks;
    private final IntegrationAccess access;
    private final IntegrationViews views;
    private final IntegrationProperties properties;
    private final AuditRecorder audit;
    private final MeterRegistry meters;

    ConnectionService(ConnectionStore store, ConnectorRegistry registry, DefinitionChecks checks,
            IntegrationAccess access, IntegrationViews views, IntegrationProperties properties, AuditRecorder audit,
            MeterRegistry meters) {
        this.views = views;
        this.store = store;
        this.registry = registry;
        this.checks = checks;
        this.access = access;
        this.properties = properties;
        this.audit = audit;
        this.meters = meters;
    }

    @Transactional(readOnly = true)
    List<ConnectorTypeView> connectorTypes() {
        access.require(Ability.INTEGRATIONS_VIEW);
        return registry.views();
    }

    @Transactional(readOnly = true)
    List<IntegrationView> list() {
        access.require(Ability.INTEGRATIONS_VIEW);
        return store.list().stream().map(views::view).toList();
    }

    @Transactional(readOnly = true)
    IntegrationView get(String name) {
        access.require(Ability.INTEGRATIONS_VIEW);
        return views.view(named(name));
    }

    IntegrationView create(SaveIntegrationRequest request) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_MANAGE);
        if (request.version() != 0) {
            throw ApiException.validation("version", "A new connection starts at version 0.");
        }
        if (!DefinitionChecks.NAME.matcher(request.name()).matches()) {
            throw ApiException.validation("name", "Use 2 to 40 lower case letters, digits and dashes, starting with "
                    + "a letter.");
        }
        Connector connector = connector(request.connectorType());
        Definition definition = checks.check(request, connector);
        if (store.count() >= properties.limits().maxConnections()) {
            throw new ApiException(ErrorCode.CONFLICT, "The organization has reached its limit of connections.");
        }
        if (store.find(request.name()).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "A connection with this name already exists.");
        }
        store.insert(request.name(), request.connectorType(), definition, caller.userId());
        Connection made = named(request.name());
        changed("created", caller, made);
        return views.view(made);
    }

    IntegrationView update(String name, SaveIntegrationRequest request) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_MANAGE);
        Connection current = named(name);
        if (!current.name().equals(request.name()) || !current.connectorType().equals(request.connectorType())) {
            throw ApiException.validation("name", "The name and the kind of a connection cannot be changed.");
        }
        if (!ConnectionMachine.canChange(current.state())) {
            throw new ApiException(ErrorCode.CONFLICT, "Switch the connection off before changing it.");
        }
        Definition definition = checks.check(request, connector(current.connectorType()));
        boolean connectivityChanged = !definition.fingerprint().equals(current.definition().fingerprint());
        State state = current.state();
        String tested = current.testedFingerprint();
        if (connectivityChanged) {
            state = ConnectionMachine.next(state, Event.EDIT).orElseThrow();
            tested = null;
        }
        if (!store.update(current.id(), request.version(), definition, state, tested, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Connection saved = named(name);
        changed("updated", caller, saved);
        return views.view(saved);
    }

    IntegrationView enable(String name, long version) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_MANAGE);
        Connection current = named(name);
        if (!ConnectionMachine.mayEnable(current.state())) {
            throw new ApiException(ErrorCode.CONFLICT, "This connection cannot be switched on from where it is.");
        }
        if (registry.find(current.connectorType()).isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "This kind of connection is no longer available.");
        }
        if (!IntegrationViews.testedNow(current)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Test the connection successfully, with its current settings, before switching it on.");
        }
        if (!store.setState(current.id(), version, State.ENABLED, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Connection saved = named(name);
        changed("enabled", caller, saved);
        return views.view(saved);
    }

    /** Switching off stops all use at once: the state is read afresh by every question, nothing is cached. */
    IntegrationView disable(String name) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_MANAGE);
        for (int attempt = 0; attempt < CLASH_TRIES; attempt++) {
            Connection current = named(name);
            if (ConnectionMachine.next(current.state(), Event.DISABLE).isEmpty()) {
                throw new ApiException(ErrorCode.CONFLICT, "This connection is not switched on.");
            }
            if (store.setState(current.id(), current.version(), State.DISABLED, caller.userId())) {
                Connection saved = named(name);
                changed("disabled", caller, saved);
                return views.view(saved);
            }
        }
        throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
    }

    void remove(String name) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_MANAGE);
        Connection current = named(name);
        if (!ConnectionMachine.canChange(current.state())) {
            throw new ApiException(ErrorCode.CONFLICT, "Switch the connection off before removing it.");
        }
        if (!store.remove(current.id(), current.version(), caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        changed("removed", caller, current);
    }

    // ---- shared with the tester ----

    Connection named(String name) {
        return store.find(name).orElseThrow(() -> ApiException.notFound("This connection does not exist."));
    }

    private Connector connector(String key) {
        return registry.find(key).orElseThrow(() -> ApiException.validation("connectorType",
                "This kind of connection is not available."));
    }

    private void changed(String action, TenantContext caller, Connection connection) {
        audit.record(AuditRecord.of("integration." + action, AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(caller.tenantId()).with("integration", connection.id().toString())
                .with("connector", connection.connectorType())
                .with("subscriptions", Integer.toString(connection.definition().subscriptions().size()))
                .with("scope", Integer.toString(connection.definition().scope().size())));
        meters.counter("platform.integration.change", "action", action).increment();
    }
}
