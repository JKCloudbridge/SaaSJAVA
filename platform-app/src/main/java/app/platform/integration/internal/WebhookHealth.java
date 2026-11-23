package app.platform.integration.internal;

import app.platform.sharedkernel.health.AutomationHealthSource;
import app.platform.sharedkernel.health.HealthItem;
import app.platform.sharedkernel.health.HealthSection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The part of the health of automation that belongs to the webhooks (Sprint 32, ADR-0278): what waits to be sent, what
 * is set aside, how many subscriptions are failing or were switched off by the platform, and how old the longest wait
 * is. Counts only, with the place to look; no name of a subscription, no address and no record.
 *
 * <p>For one organization it reads that organization's rows under its row level security. Across organizations it
 * reports what the webhook worker last measured for its own gauges, so asking costs nothing more.
 */
@Component
class WebhookHealth implements AutomationHealthSource {

    private static final Duration STALE = Duration.ofMinutes(10);
    private static final String PLACE = "/setup/webhooks";

    private final WebhookSubscriptionStore subscriptions;
    private final WebhookDeliveryStore deliveries;
    private final WebhookWorker worker;

    WebhookHealth(WebhookSubscriptionStore subscriptions, WebhookDeliveryStore deliveries, WebhookWorker worker) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.worker = worker;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HealthSection> organization() {
        long waiting = 0;
        long deadLetters = 0;
        long oldestSeconds = 0;
        long failing = 0;
        long stopped = 0;
        Instant now = Instant.now();
        for (WebhookSubscriptionStore.Subscription subscription : subscriptions.list()) {
            WebhookDeliveryStore.Counts counts = deliveries.counts(subscription.id());
            waiting += counts.pending();
            deadLetters += counts.deadLettered();
            if (subscription.state() == SubscriptionMachine.State.FAILING) {
                failing++;
            }
            if ("auto_disabled".equals(subscription.disabledReason())) {
                stopped++;
            }
            if (counts.oldestOpen() != null && counts.oldestOpen().isBefore(now)) {
                oldestSeconds = Math.max(oldestSeconds, Duration.between(counts.oldestOpen(), now).toSeconds());
            }
        }
        return List.of(new HealthSection("webhooks", "Webhooks", List.of(
                new HealthItem("webhooks.waiting", "Webhook deliveries waiting to be sent", waiting, false, PLACE),
                new HealthItem("webhooks.deadLettered", "Webhook deliveries set aside after their attempts",
                        deadLetters, deadLetters > 0, PLACE),
                new HealthItem("webhooks.failing", "Webhooks whose address is failing", failing, failing > 0, PLACE),
                new HealthItem("webhooks.stopped", "Webhooks the platform switched off after a long failure",
                        stopped, stopped > 0, PLACE),
                new HealthItem("webhooks.oldestSeconds", "Seconds the longest wait has lasted", oldestSeconds,
                        oldestSeconds > STALE.toSeconds(), null))));
    }

    @Override
    public List<HealthSection> platform() {
        WebhookWorker.Sample sample = worker.sample();
        return List.of(new HealthSection("webhooks", "Webhooks", List.of(
                new HealthItem("webhooks.waiting", "Webhook deliveries waiting to be sent", sample.open(), false, null),
                new HealthItem("webhooks.deadLettered", "Webhook deliveries set aside after their attempts",
                        sample.deadLetters(), sample.deadLetters() > 0, null),
                new HealthItem("webhooks.oldestSeconds", "Seconds the longest wait has lasted",
                        sample.oldestSeconds(), sample.oldestSeconds() > STALE.toSeconds(), null))));
    }
}
