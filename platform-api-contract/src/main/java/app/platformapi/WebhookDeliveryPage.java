package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * A page of the delivery log, newest first (Sprint 32, ADR-0278).
 *
 * @param items the deliveries of this page
 * @param next the identifier to pass as {@code before} for the next page, or null at the end
 */
public record WebhookDeliveryPage(@NotNull List<WebhookDeliveryView> items, UUID next) {

    public WebhookDeliveryPage {
        items = List.copyOf(items);
    }
}
