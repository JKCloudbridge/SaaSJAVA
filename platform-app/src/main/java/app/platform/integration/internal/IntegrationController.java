package app.platform.integration.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ConnectorTypeView;
import app.platformapi.EnableIntegrationRequest;
import app.platformapi.IntegrationTestView;
import app.platformapi.IntegrationView;
import app.platformapi.SaveIntegrationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The organization's connections to external systems (Sprint 26). Organization hosts only: the caller must be a
 * member, hold the ability and have the plan feature. Definitions travel in bodies, never in addresses.
 */
@RestController
@Tag(name = "Integrations")
class IntegrationController {

    private static final String ROOT = ApiPaths.INTEGRATIONS;
    private final ConnectionService connections;
    private final ConnectionTesting testing;

    IntegrationController(ConnectionService connections, ConnectionTesting testing) {
        this.connections = connections;
        this.testing = testing;
    }

    @GetMapping(ROOT + "/connector-types")
    @Operation(operationId = "listConnectorTypes", summary = "The kinds of external system that can be connected",
            description = "Needs integrations.view. Each kind describes its own settings, ways of signing in and "
                    + "events; the screens draw their forms from this and hold no list of their own.")
    ApiResponse<List<ConnectorTypeView>> types() {
        return ApiResponse.of(connections.connectorTypes());
    }

    @GetMapping(ROOT)
    @Operation(operationId = "listIntegrations", summary = "The organization's connections",
            description = "Needs integrations.view. Never holds a secret: a credential is shown only as its name.")
    ApiResponse<List<IntegrationView>> list() {
        return ApiResponse.of(connections.list());
    }

    @PostMapping(ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createIntegration", summary = "Make a connection",
            description = "Needs integrations.view and integrations.manage. Starts untested. The address, the "
                    + "settings, the events and the scope are checked on the server; nothing typed is logged.")
    ApiResponse<IntegrationView> create(@Valid @RequestBody SaveIntegrationRequest body) {
        return ApiResponse.of(connections.create(body));
    }

    @GetMapping(ROOT + "/{name}")
    @Operation(operationId = "getIntegration", summary = "One connection",
            description = "Needs integrations.view.")
    ApiResponse<IntegrationView> get(@PathVariable String name) {
        return ApiResponse.of(connections.get(name));
    }

    @PutMapping(ROOT + "/{name}")
    @Operation(operationId = "updateIntegration", summary = "Change a connection that is switched off",
            description = "Needs integrations.view, integrations.manage and the version read. A change of the "
                    + "address, the sign-in or the settings makes the last test void. A connection that is switched "
                    + "on is switched off first.")
    ApiResponse<IntegrationView> update(@PathVariable String name, @Valid @RequestBody SaveIntegrationRequest body) {
        return ApiResponse.of(connections.update(name, body));
    }

    @PostMapping(ROOT + "/{name}/test")
    @Operation(operationId = "testIntegration", summary = "Call the external system once",
            description = "Needs integrations.view and integrations.test. One bounded call outside any "
                    + "transaction; at most one test per connection every few seconds. The answer is a stable reason "
                    + "and the platform's own sentence, never anything the other system said.")
    ApiResponse<IntegrationTestView> test(@PathVariable String name) {
        return ApiResponse.of(testing.test(name));
    }

    @PostMapping(ROOT + "/{name}/enable")
    @Operation(operationId = "enableIntegration", summary = "Switch a tested connection on",
            description = "Needs integrations.view, integrations.manage and the version read. Only after a "
                    + "successful test of exactly the current settings.")
    ApiResponse<IntegrationView> enable(@PathVariable String name, @Valid @RequestBody EnableIntegrationRequest body) {
        return ApiResponse.of(connections.enable(name, body.version()));
    }

    @PostMapping(ROOT + "/{name}/disable")
    @Operation(operationId = "disableIntegration", summary = "Switch a connection off",
            description = "Needs integrations.view and integrations.manage. Takes effect at once: the next "
                    + "question about the connection is answered as for a connection that is off.")
    ApiResponse<IntegrationView> disable(@PathVariable String name) {
        return ApiResponse.of(connections.disable(name));
    }

    @DeleteMapping(ROOT + "/{name}")
    @Operation(operationId = "removeIntegration", summary = "Remove a connection that is switched off",
            description = "Needs integrations.view and integrations.manage.")
    ApiResponse<Boolean> remove(@PathVariable String name) {
        connections.remove(name);
        return ApiResponse.of(Boolean.TRUE);
    }
}
