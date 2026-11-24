package app.platform.integration.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the delivery log from growing without end (Sprint 32, ADR-0278): the record of a delivery that ended, and its
 * attempts, are removed once they are older than {@code platform.webhooks.retention.deliveries} (never fewer than one
 * day; the database also refuses to delete anything younger or anything still open). The clean-up is bounded (a batch
 * of
 * rows at a time) and idempotent (a second run finds what the first left), and it runs inside the organization whose
 * delivery has just ended, so it needs no scope that spans organizations and costs a busy organization exactly what its
 * own traffic is.
 *
 * <p>It is not run on every success: one in fifty is enough to keep pace with the writes, and a quiet organization
 * writes nothing and needs nothing. Must run in a transaction in the organization's own tenant context.
 */
@Component
class WebhookRetention {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookRetention.class);
    private static final int ONE_IN = 50;

    private final WebhookDeliveryStore deliveries;
    private final WebhookProperties settings;
    private final MeterRegistry meters;

    WebhookRetention(WebhookDeliveryStore deliveries, WebhookProperties settings, MeterRegistry meters) {
        this.deliveries = deliveries;
        this.settings = settings;
        this.meters = meters;
    }

    /** Runs a clean-up now and then. Never throws: a failed clean-up is tried again by a later success. */
    void maybePurge() {
        if (ThreadLocalRandom.current().nextInt(ONE_IN) == 0) {
            purge();
        }
    }

    /** Removes one batch of what is past its retention; the number removed. */
    int purge() {
        try {
            int removed = deliveries.purge(settings.retention().deliveries(), settings.retention().batch());
            if (removed > 0) {
                meters.counter("platform.webhook.purged").increment(removed);
            }
            return removed;
        } catch (RuntimeException failure) {
            LOG.warn("The webhook delivery clean-up failed: {}", failure.getClass().getName());
            return 0;
        }
    }
}
