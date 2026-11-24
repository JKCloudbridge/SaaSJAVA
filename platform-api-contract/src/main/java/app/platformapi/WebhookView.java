package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A webhook subscription as an administrator sees it (Sprint 32, ADR-0274): a standing instruction to tell an address
 * of
 * the organization's own software, with a small signed message, when records change. The address is the
 * organization's own data and appears only here, on its own screen. It never carries a secret.
 *
 * @param id the identifier of the subscription (used in the addresses of this API)
 * @param name the name chosen when it was made
 * @param address the https address deliveries are sent to
 * @param events the events it asks for, by key
 * @param objects the API names of the objects it is limited to, or null for every object its member may read
 * @param state {@code DISABLED}, {@code ACTIVE} or {@code FAILING}
 * @param running whether deliveries are sent now: false when it is disabled or when its member can no longer act
 * @param stoppedReason why it is not running: {@code disabled}, {@code member_not_active}, or null while it runs
 * @param disabledReason why it is disabled: {@code switched_off}, {@code auto_disabled}, {@code new}, or null
 * @param actsAs the display name of the member whose rights it uses, or null when that person left
 * @param actsAsYou whether the member it acts as is the person who asks
 * @param verified whether the address answered a verification ping as it stands now
 * @param secretNumber the number of the signing secret in use
 * @param secretMadeAt when that secret was made
 * @param previousSecretUntil while a replaced secret still signs too, when it stops; otherwise null
 * @param circuit {@code CLOSED}, {@code OPEN} or {@code HALF_OPEN}: whether the platform is holding deliveries back
 * @param lastSuccessAt when a delivery was last accepted, or null
 * @param lastFailureAt when an attempt last failed, or null
 * @param failingSince when the unbroken run of failures began, or null
 * @param createdAt when it was made
 * @param version the version read; a change carries it back
 * @param queue what the queue holds
 */
public record WebhookView(@NotNull UUID id, @NotNull String name, @NotNull String address,
        @NotNull List<String> events, List<String> objects, @NotNull String state, boolean running,
        String stoppedReason, String disabledReason, String actsAs, boolean actsAsYou, boolean verified,
        int secretNumber, @NotNull Instant secretMadeAt, Instant previousSecretUntil, @NotNull String circuit,
        Instant lastSuccessAt, Instant lastFailureAt, Instant failingSince, @NotNull Instant createdAt, long version,
        @NotNull WebhookQueueView queue) {

    public WebhookView {
        events = List.copyOf(events);
        objects = objects == null ? null : List.copyOf(objects);
    }

    /** Prints nothing: the name and the address are the organization's own words and must not reach a log. */
    @Override
    public String toString() {
        return "WebhookView[redacted]";
    }
}
