package app.platform.integration.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.EnableWebhookRequest;
import app.platformapi.NewWebhookSecret;
import app.platformapi.RotateWebhookSecretRequest;
import app.platformapi.SaveWebhookRequest;
import app.platformapi.WebhookAttemptView;
import app.platformapi.WebhookCatalogue;
import app.platformapi.WebhookDeliveryPage;
import app.platformapi.WebhookReplayView;
import app.platformapi.WebhookTestView;
import app.platformapi.WebhookView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The webhook subscriptions of the organization the host names, with their delivery log (Sprint 32, ADR-0274 to
 * ADR-0279). Thin: the rules are in {@link WebhookSubscriptions} and {@link WebhookTesting}. Organization hosts only;
 * the caller must be a member who holds the abilities, and the organization must have the plan feature. A token of a
 * connected application never reaches these addresses (its own filter refuses everything but the public API). The only
 * answers that carry a signing secret are those of the two calls that make one, once, never cached; no call returns a
 * secret that exists. Definitions travel in bodies, never in addresses.
 */
@RestController
@Tag(name = "Webhooks")
class WebhookController {

    private static final String ROOT = ApiPaths.WEBHOOKS;
    private static final String NOT_FOUND = "NOT_FOUND on the platform host and for a webhook of another organization.";

    private final WebhookSubscriptions subscriptions;
    private final WebhookTesting testing;

    WebhookController(WebhookSubscriptions subscriptions, WebhookTesting testing) {
        this.subscriptions = subscriptions;
        this.testing = testing;
    }

    @GetMapping(ROOT + "/catalogue")
    @Operation(operationId = "getWebhookCatalogue", summary = "What a webhook can ask for and how it works",
            description = "Needs webhooks.view. The events, the version of the message, the words about whom a "
                    + "webhook acts as and about duplicates. The screens draw their choices from this and hold no "
                    + "list of their own.")
    ApiResponse<WebhookCatalogue> catalogue() {
        return ApiResponse.of(subscriptions.catalogue());
    }

    @GetMapping(ROOT)
    @Operation(operationId = "listWebhooks", summary = "The organization's webhooks",
            description = "Needs webhooks.view. Never holds a secret. " + NOT_FOUND)
    ApiResponse<List<WebhookView>> list() {
        return ApiResponse.of(subscriptions.list());
    }

    @PostMapping(ROOT)
    @Operation(operationId = "createWebhook", summary = "Make a webhook",
            description = "Needs webhooks.view and webhooks.manage. The webhook acts as the person who makes it, for "
                    + "ever, and starts switched off until its address answers a test. The answer carries the signing "
                    + "secret once and is never cached; the platform cannot show it again. Nothing typed is logged.")
    ResponseEntity<ApiResponse<NewWebhookSecret>> create(@Valid @RequestBody SaveWebhookRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(subscriptions.create(body)));
    }

    @GetMapping(ROOT + "/{id}")
    @Operation(operationId = "getWebhook", summary = "One webhook",
            description = "Needs webhooks.view. " + NOT_FOUND)
    ApiResponse<WebhookView> get(@PathVariable UUID id) {
        return ApiResponse.of(subscriptions.get(id));
    }

    @PutMapping(ROOT + "/{id}")
    @Operation(operationId = "updateWebhook", summary = "Change a webhook that is switched off",
            description = "Needs webhooks.view, webhooks.manage and the version read. A change of the address, the "
                    + "events or the objects makes the last test void. A webhook that is switched on is switched off "
                    + "first. It cannot change whom the webhook acts as.")
    ApiResponse<WebhookView> update(@PathVariable UUID id, @Valid @RequestBody SaveWebhookRequest body) {
        return ApiResponse.of(subscriptions.update(id, body));
    }

    @PostMapping(ROOT + "/{id}/test")
    @Operation(operationId = "testWebhook", summary = "Send a signed test message to the address",
            description = "Needs webhooks.view and webhooks.manage. One bounded call outside any transaction; at most "
                    + "one test of a webhook every few seconds. The answer is a stable reason and the platform's own "
                    + "sentence, never anything the receiver said. A success verifies the address as it stands.")
    ApiResponse<WebhookTestView> test(@PathVariable UUID id) {
        return ApiResponse.of(testing.test(id));
    }

    @PostMapping(ROOT + "/{id}/enable")
    @Operation(operationId = "enableWebhook", summary = "Switch a verified webhook on",
            description = "Needs webhooks.view, webhooks.manage and the version read. Only after a successful test of "
                    + "exactly the current address, and only while the member it acts as can act.")
    ApiResponse<WebhookView> enable(@PathVariable UUID id, @Valid @RequestBody EnableWebhookRequest body) {
        return ApiResponse.of(subscriptions.enable(id, body.version()));
    }

    @PostMapping(ROOT + "/{id}/disable")
    @Operation(operationId = "disableWebhook", summary = "Switch a webhook off",
            description = "Needs webhooks.view and webhooks.manage. Takes effect at once: what is waiting stays in the "
                    + "queue, which is bounded, until the webhook is switched on again.")
    ApiResponse<WebhookView> disable(@PathVariable UUID id) {
        return ApiResponse.of(subscriptions.disable(id));
    }

    @PostMapping(ROOT + "/{id}/secret/rotate")
    @Operation(operationId = "rotateWebhookSecret", summary = "Replace the signing secret",
            description = "Needs webhooks.view and webhooks.manage. The new secret is current at once and is shown "
                    + "once, never cached. The old one keeps signing too for the hours asked (0 to 24) so a receiver "
                    + "can change over; 0 ends it at once, which is the answer to a leaked secret.")
    ResponseEntity<ApiResponse<NewWebhookSecret>> rotate(@PathVariable UUID id,
            @Valid @RequestBody RotateWebhookSecretRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(subscriptions.rotate(id, body.overlapHours())));
    }

    @PostMapping(ROOT + "/{id}/secret/end-overlap")
    @Operation(operationId = "endWebhookSecretOverlap", summary = "Stop the old signing secret now",
            description = "Needs webhooks.view and webhooks.manage. Ends the overlap of a replaced secret at once.")
    ApiResponse<WebhookView> endOverlap(@PathVariable UUID id) {
        return ApiResponse.of(subscriptions.endOverlap(id));
    }

    @DeleteMapping(ROOT + "/{id}")
    @Operation(operationId = "removeWebhook", summary = "Remove a webhook",
            description = "Needs webhooks.view and webhooks.manage. What was waiting is set aside as skipped and the "
                    + "signing secrets are removed; the delivery log is kept for its retention.")
    ApiResponse<Boolean> remove(@PathVariable UUID id) {
        subscriptions.remove(id);
        return ApiResponse.of(Boolean.TRUE);
    }

    @GetMapping(ROOT + "/{id}/deliveries")
    @Operation(operationId = "listWebhookDeliveries", summary = "The delivery log of a webhook, newest first",
            description = "Needs webhooks.view. Identifiers, a state and a stable reason: never a payload, a header or "
                    + "an answer. Filter by one or more states; pass the next value back as before to read on.")
    ApiResponse<WebhookDeliveryPage> deliveries(@PathVariable UUID id,
            @RequestParam(name = "state", required = false) List<String> state,
            @RequestParam(name = "before", required = false) UUID before,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.of(subscriptions.deliveriesOf(id, state == null ? List.of() : state, before, limit));
    }

    @GetMapping(ROOT + "/{id}/deliveries/{delivery}/attempts")
    @Operation(operationId = "listWebhookAttempts", summary = "The attempts of one delivery",
            description = "Needs webhooks.view. When, how long, how it ended, a stable reason and the class of the "
                    + "answer.")
    ApiResponse<List<WebhookAttemptView>> attempts(@PathVariable UUID id, @PathVariable UUID delivery) {
        return ApiResponse.of(subscriptions.attemptsOf(id, delivery));
    }

    @PostMapping(ROOT + "/{id}/deliveries/{delivery}/replay")
    @Operation(operationId = "replayWebhookDelivery", summary = "Send one delivery that was set aside again",
            description = "Needs webhooks.view and webhooks.replay. A new generation of the same delivery, with the "
                    + "same delivery identifier; two clicks make one. Only a delivery that was set aside can be "
                    + "sent again.")
    ApiResponse<WebhookReplayView> replay(@PathVariable UUID id, @PathVariable UUID delivery) {
        return ApiResponse.of(subscriptions.replay(id, delivery));
    }

    @PostMapping(ROOT + "/{id}/deliveries/replay")
    @Operation(operationId = "replayWebhookDeliveries", summary = "Send the deliveries that were set aside again",
            description = "Needs webhooks.view and webhooks.replay. At most 500 a call, oldest first; the answer says "
                    + "whether more remain.")
    ApiResponse<WebhookReplayView> replayAll(@PathVariable UUID id) {
        return ApiResponse.of(subscriptions.replayAll(id));
    }
}
