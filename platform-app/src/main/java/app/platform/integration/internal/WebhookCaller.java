package app.platform.integration.internal;

import app.platform.integration.internal.EgressClient.Answer;
import app.platform.integration.internal.EndpointRules.Endpoint;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.TenantId;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Makes the one call of a webhook delivery or ping (Sprint 32, ADR-0277, ADR-0278): signs the exact body with every
 * secret that signs, sets the four headers the receiver reads, and calls the address through {@link EgressClient}, so
 * every rule of an outside address applies unchanged: https only, every address the name resolves to public, the socket
 * opened to the address that was checked (a name that changes its answer between the check and the connection gets no
 * second chance), no redirect followed, one deadline. Only the status and the headers of the answer are read: the body
 * is never read, never held and never logged, so a receiver that answers with a page costs nothing and tells the
 * platform nothing.
 *
 * <p>It is the one class of the webhooks, with {@link WebhookSigner} behind it, that calls out; it holds no transaction
 * and no row, and it is handed everything it needs. Nothing here logs an address, a header, a signature or a body.
 *
 * <p>The headers of a delivery ({@code Webhook-Id} the delivery identifier, which a receiver deduplicates by;
 * {@code Webhook-Event}; {@code Webhook-Timestamp} the whole seconds that are also signed; {@code Webhook-Signature}
 * one {@code v1=<hex>} for each secret that signs, two while a replaced secret overlaps; {@code Webhook-Attempt}).
 */
@Component
class WebhookCaller {

    /**
     * What to send.
     *
     * @param tenant the organization, whose scope holds the secrets
     * @param subscription the subscription whose secrets sign
     * @param secrets the numbers of the secrets that sign: the current one and, while it overlaps, the previous one
     * @param endpoint the checked address
     * @param event the event key, or {@code ping}
     * @param delivery the delivery identifier, which the receiver deduplicates by
     * @param attempt the number of this attempt of this generation
     * @param body the exact body that is signed and sent
     */
    record Request(TenantId tenant, UUID subscription, List<Integer> secrets, Endpoint endpoint, String event,
            UUID delivery, int attempt, byte[] body) {

        Request {
            secrets = List.copyOf(secrets);
        }

        /** Prints identifiers only: the endpoint is an address and the body is a message. */
        @Override
        public String toString() {
            return "Request[" + delivery + "]";
        }
    }

    private static final int STATUS_ONLY_LIMIT = 1024;

    private final WebhookSigner signer;
    private final EgressClient egress;
    private final WebhookProperties properties;
    private final ErrorTracker tracker;
    private final Clock clock;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    WebhookCaller(WebhookSigner signer, EgressClient egress, WebhookProperties properties, ErrorTracker tracker,
            Clock clock) {
        this.signer = signer;
        this.egress = egress;
        this.properties = properties;
        this.tracker = tracker;
        this.clock = clock;
    }

    /** Signs and sends. A failure of signing is an answer with its reason, and nothing is sent unsigned. */
    Answer send(Request request) {
        long timestamp = clock.instant().getEpochSecond();
        WebhookSigner.Signed signed = signer.sign(request.tenant(), request.subscription(), request.secrets(),
                timestamp, request.body());
        if (signed.failure() != null) {
            return Answer.failed(signed.failure());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Webhook-Id", request.delivery().toString());
        headers.put("Webhook-Event", request.event());
        headers.put("Webhook-Timestamp", Long.toString(timestamp));
        headers.put("Webhook-Signature", signed.header());
        headers.put("Webhook-Attempt", Integer.toString(Math.max(1, request.attempt())));
        // The address may name a path; a bare host is asked for its root.
        String path = request.endpoint().basePath().isEmpty() ? "/" : "";
        EgressClient.Call call = new EgressClient.Call(request.endpoint(), "POST", path, null, "application/json",
                request.body(), properties.delivery().sendTimeout(), STATUS_ONLY_LIMIT, headers, true);
        return bounded(call);
    }

    /** One deadline for the whole exchange, enforced here as well as inside the client. */
    private Answer bounded(EgressClient.Call call) {
        Future<Answer> pending = calls.submit(() -> egress.call(call));
        try {
            return pending.get(properties.delivery().sendTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException tooSlow) {
            pending.cancel(true);
            return Answer.failed(TestReason.TIMED_OUT);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return Answer.failed(TestReason.UNEXPECTED);
        } catch (ExecutionException failed) {
            tracker.track(ErrorReport.current(new ConnectionTesting.IntegrationFailure(failed.getCause()), "POST",
                    "/integration/webhooks"));
            return Answer.failed(TestReason.UNEXPECTED);
        }
    }

    @PreDestroy
    void close() {
        calls.shutdownNow();
    }
}
