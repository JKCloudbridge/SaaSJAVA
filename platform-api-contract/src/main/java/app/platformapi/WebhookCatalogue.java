package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a screen needs to make and explain a webhook, told by the server so that the browser holds no list and no
 * rule (Sprint 32, ADR-0275 to ADR-0277).
 *
 * @param events the events a subscription may ask for
 * @param payloadVersion the version of the message a delivery carries; a property is only ever added
 * @param toleranceSeconds how far a receiver should let the time of a delivery be from its own clock
 * @param maxOverlapHours the longest time two signing secrets may work at once while one is replaced
 * @param maxSubscriptions how many subscriptions an organization may have
 * @param maxObjects how many objects one subscription may be limited to
 * @param acting the plain words that say whose rights a subscription uses
 * @param stopped the plain words for a subscription whose member can no longer act
 * @param secretOnce the plain words about the signing secret being shown once
 * @param duplicates the plain words about receiving the same delivery twice
 * @param deliveryStates the states a delivery can be in, for the filter of the delivery log
 */
public record WebhookCatalogue(@NotNull List<WebhookEventView> events, int payloadVersion, int toleranceSeconds,
        int maxOverlapHours, int maxSubscriptions, int maxObjects, @NotNull String acting, @NotNull String stopped,
        @NotNull String secretOnce, @NotNull String duplicates,
        @NotNull List<WebhookStateView> deliveryStates) {

    public WebhookCatalogue {
        events = List.copyOf(events);
        deliveryStates = List.copyOf(deliveryStates);
    }
}
