package app.platform.integration.internal;

import app.platform.integration.internal.CircuitBreaker.Mode;
import app.platform.integration.internal.WebhookDeliveryStore.Counts;
import app.platform.integration.internal.WebhookDeliveryStore.Delivery;
import app.platform.integration.internal.WebhookSubscriptionStore.Subscription;
import app.platformapi.WebhookAttemptView;
import app.platformapi.WebhookDeliveryView;
import app.platformapi.WebhookQueueView;
import app.platformapi.WebhookView;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns the rows of a webhook subscription and its queue into what an administrator sees (Sprint 32, ADR-0274). The
 * address is shown here because this is the organization's own screen of its own data; no view carries a secret, a
 * header, a payload or an answer. "Running" and "stopped" are judged now from the subscription's state and from the
 * standing of the member it acts as, so the screen never shows a subscription as sending when it cannot.
 */
@Component
class WebhookViews {

    private final WebhookVisibility visibility;

    WebhookViews(WebhookVisibility visibility) {
        this.visibility = visibility;
    }

    /** The subscription as the caller sees it. */
    WebhookView view(Subscription subscription, Counts counts, UUID callerUser, Instant now) {
        String actsAs = visibility.nameOf(subscription.userId());
        boolean off = subscription.state() == SubscriptionMachine.State.DISABLED;
        String stopped = off ? "disabled" : actsAs == null ? "member_not_active" : null;
        // A circuit whose time is up is half open: the next delivery is the probe.
        Mode shown = subscription.circuit().mode() == Mode.OPEN && subscription.circuit().openUntil() != null
                && !now.isBefore(subscription.circuit().openUntil()) ? Mode.HALF_OPEN : subscription.circuit().mode();
        Instant overlap = subscription.previousSecretNumber() != null && subscription.previousSecretUntil() != null
                && subscription.previousSecretUntil().isAfter(now) ? subscription.previousSecretUntil() : null;
        return new WebhookView(subscription.id(), subscription.name(), subscription.address(), subscription.events(),
                subscription.objects(), subscription.state().name(), stopped == null, stopped,
                off ? subscription.disabledReason() : null, actsAs, subscription.userId().equals(callerUser),
                subscription.verified(), subscription.secretNumber(), subscription.secretMadeAt(), overlap,
                shown.name(), subscription.lastSuccessAt(), subscription.lastFailureAt(), subscription.failingSince(),
                subscription.createdAt(), subscription.version(), queue(counts));
    }

    static WebhookQueueView queue(Counts counts) {
        return new WebhookQueueView(counts.pending(), counts.deadLettered(), counts.done(), counts.skipped(),
                counts.oldestOpen());
    }

    static WebhookDeliveryView delivery(Delivery delivery) {
        boolean open = "PENDING".equals(delivery.state()) || "SENDING".equals(delivery.state());
        // A delivery that was set aside because the member could not read the record never names it: the log of a
        // subscription must not tell anyone that a record exists which its member may not know about (ADR-0277).
        boolean hidden = "SKIPPED".equals(delivery.state()) && (WebhookReason.NOT_VISIBLE.key().equals(
                delivery.reason()) || WebhookReason.MEMBER_INACTIVE.key().equals(delivery.reason()));
        return new WebhookDeliveryView(delivery.id(), delivery.eventType(), delivery.object(),
                hidden ? null : delivery.recordId(), hidden ? null : delivery.recordVersion(), delivery.state(),
                delivery.reason(), WebhookReasons.words(delivery.reason()), delivery.queuedReason(),
                WebhookReasons.words(delivery.queuedReason()),
                delivery.generation(), delivery.attempts(), delivery.createdAt(), delivery.finishedAt(),
                open ? delivery.runAt() : null, delivery.lastStatusClass());
    }

    static WebhookAttemptView attempt(WebhookAttemptStore.Attempt attempt) {
        return new WebhookAttemptView(attempt.generation(), attempt.attemptNo(), attempt.startedAt(),
                attempt.durationMs(), attempt.outcome(), attempt.reason(), WebhookReasons.words(attempt.reason()),
                attempt.statusClass(), attempt.httpStatus());
    }
}
