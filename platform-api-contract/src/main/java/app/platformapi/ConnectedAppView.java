package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A connected application of the organization as an administrator sees it (Sprint 31, ADR-0267): a piece of other
 * software that asks the platform for short-lived tokens with its own client identity and secret, and acts as one
 * member of the organization. It never carries a secret.
 *
 * @param id the identifier of the registration (used in the addresses of this API)
 * @param clientId the public client identifier the software sends with its secret; not a secret
 * @param name the name chosen when it was registered
 * @param description what it is for, in the administrator's words; may be empty
 * @param state {@code ACTIVE} or {@code DISABLED}: the administrator's switch
 * @param running whether the application can obtain and use tokens right now; false when it is disabled or when the
 *        member it acts as can no longer act (see {@code stoppedReason})
 * @param stoppedReason why it is not running: {@code disabled}, {@code member_not_active}, or null while it runs
 * @param actsAs the display name of the member it acts as, or null when that person left
 * @param actsAsYou whether the member it acts as is the person who asks
 * @param scopes the scopes it holds, by key
 * @param objects the API names of the objects it is limited to, or null when it may use every object its member may
 *        reach
 * @param tokenMinutes the life of the tokens it is given
 * @param activeSecrets how many of its secrets work now
 * @param createdAt when it was registered
 * @param lastUsedAt when a secret of it was last used (at most one minute behind), or null if never
 * @param version the version read; a change carries it back
 */
public record ConnectedAppView(@NotNull UUID id, @NotNull String clientId, @NotNull String name,
        @NotNull String description, @NotNull String state, boolean running, String stoppedReason, String actsAs,
        boolean actsAsYou, @NotNull List<String> scopes, List<String> objects, int tokenMinutes, int activeSecrets,
        @NotNull Instant createdAt, Instant lastUsedAt, long version) {

    public ConnectedAppView {
        scopes = List.copyOf(scopes);
        objects = objects == null ? null : List.copyOf(objects);
    }
}
