package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ConnectedAppCatalogue;
import app.platformapi.ConnectedAppDetail;
import app.platformapi.ConnectedAppView;
import app.platformapi.CreateConnectedAppRequest;
import app.platformapi.NewClientSecret;
import app.platformapi.RotateClientSecretRequest;
import app.platformapi.UpdateConnectedAppRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connected applications of the organization the host names (Sprint 31, ADR-0267 to ADR-0270). Thin: the rules are
 * in {@link ClientRegistry}. Organization hosts only; the caller must be a member who holds the abilities, and the
 * organization must have the plan feature. A token of a connected application never reaches these addresses (its own
 * filter refuses everything but the public API), so an application cannot register, change or remove applications.
 * The only answers that carry a secret are those of the two calls that make one, once, never cached; no call returns a
 * secret that exists.
 */
@RestController
@Tag(name = "Connected applications")
class ConnectedAppController {

    private static final String ROOT = ApiPaths.CONNECTED_APPS;
    private static final String NOT_FOUND = "NOT_FOUND on the platform host, for an application of another "
            + "organization, and when connected applications are switched off.";

    private final ClientRegistry clients;

    ConnectedAppController(ClientRegistry clients) {
        this.clients = clients;
    }

    @GetMapping(ROOT + "/catalogue")
    @Operation(operationId = "getConnectedAppCatalogue", summary = "What a connected application can be given",
            description = "Needs clients.view. The scopes with what each allows, the lifetimes a token may have, the "
                    + "address software asks for a token at, a worked request, and the words that explain whom an "
                    + "application acts as. " + NOT_FOUND)
    ApiResponse<ConnectedAppCatalogue> catalogue() {
        return ApiResponse.of(clients.catalogue());
    }

    @GetMapping(ROOT)
    @Operation(operationId = "listConnectedApps", summary = "The organization's connected applications",
            description = "Needs clients.view. Name, state, scopes, whom each acts as, whether it runs, and when it "
                    + "was last used. Never a secret. " + NOT_FOUND)
    ApiResponse<List<ConnectedAppView>> list() {
        return ApiResponse.of(clients.list());
    }

    @PostMapping(ROOT)
    @Operation(operationId = "registerConnectedApp", summary = "Register a connected application",
            description = "Needs clients.manage and clients.view. The application acts as the member who asks, and "
                    + "that can never be changed. The answer carries the client secret, this once: it is kept only as "
                    + "a hash and cannot be shown again. CONFLICT when the organization has the most applications it "
                    + "may have or the plan lacks the feature. " + NOT_FOUND)
    ResponseEntity<ApiResponse<NewClientSecret>> register(@Valid @RequestBody CreateConnectedAppRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(clients.register(body)));
    }

    @GetMapping(ROOT + "/{id}")
    @Operation(operationId = "getConnectedApp", summary = "One connected application with its secrets",
            description = "Needs clients.view. The secrets are described (number, state, when made, when last used, "
                    + "when they end); no answer ever carries one. " + NOT_FOUND)
    ApiResponse<ConnectedAppDetail> get(@PathVariable UUID id) {
        return ApiResponse.of(clients.read(id));
    }

    @PutMapping(ROOT + "/{id}")
    @Operation(operationId = "updateConnectedApp", summary = "Change what a connected application may do",
            description = "Needs clients.manage and clients.view. Carries the version read. The member it acts as "
                    + "cannot be changed. A change that narrows the scopes, the objects or the token life ends the "
                    + "tokens already issued. CONCURRENT_MODIFICATION when the version is not current. " + NOT_FOUND)
    ApiResponse<ConnectedAppDetail> update(@PathVariable UUID id, @Valid @RequestBody UpdateConnectedAppRequest body) {
        return ApiResponse.of(clients.update(id, body));
    }

    @PostMapping(ROOT + "/{id}/disable")
    @Operation(operationId = "disableConnectedApp", summary = "Switch a connected application off",
            description = "Needs clients.manage and clients.view. Its tokens end now and it cannot ask for another "
                    + "until it is switched on. " + NOT_FOUND)
    ApiResponse<ConnectedAppDetail> disable(@PathVariable UUID id) {
        return ApiResponse.of(clients.disable(id));
    }

    @PostMapping(ROOT + "/{id}/enable")
    @Operation(operationId = "enableConnectedApp", summary = "Switch a connected application on",
            description = "Needs clients.manage and clients.view. Its secrets that still work work again. "
                    + NOT_FOUND)
    ApiResponse<ConnectedAppDetail> enable(@PathVariable UUID id) {
        return ApiResponse.of(clients.enable(id));
    }

    @DeleteMapping(ROOT + "/{id}")
    @Operation(operationId = "removeConnectedApp", summary = "Remove a connected application",
            description = "Needs clients.manage and clients.view. Its secrets end and its tokens end now; it cannot "
                    + "be brought back. " + NOT_FOUND)
    ApiResponse<Boolean> remove(@PathVariable UUID id) {
        clients.remove(id);
        return ApiResponse.of(Boolean.TRUE);
    }

    @PostMapping(ROOT + "/{id}/secrets/rotate")
    @Operation(operationId = "rotateConnectedAppSecret", summary = "Make a new secret and end the older ones",
            description = "Needs clients.manage and clients.view. The older secrets stop working at once, or after an "
                    + "overlap of up to 24 hours chosen in the request. Tokens already issued live until they end "
                    + "unless the request says to end them now. The answer carries the new secret, this once. "
                    + "CONFLICT when the application would have more secrets at once than it may. " + NOT_FOUND)
    ResponseEntity<ApiResponse<NewClientSecret>> rotate(@PathVariable UUID id,
            @Valid @RequestBody(required = false) RotateClientSecretRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(clients.rotate(id, body)));
    }

    @DeleteMapping(ROOT + "/{id}/secrets/{secretId}")
    @Operation(operationId = "revokeConnectedAppSecret", summary = "End one secret now",
            description = "Needs clients.manage and clients.view. The secret stops working at once and the tokens it "
                    + "made end now. Ending a secret that has ended changes nothing. " + NOT_FOUND)
    ApiResponse<ConnectedAppDetail> revokeSecret(@PathVariable UUID id, @PathVariable UUID secretId) {
        return ApiResponse.of(clients.revokeSecret(id, secretId));
    }
}
