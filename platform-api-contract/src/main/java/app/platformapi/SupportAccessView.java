package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * A request for support access and what became of it.
 *
 * @param id the request
 * @param requester the name of the platform person who asked (never the address)
 * @param reason why they asked
 * @param status {@code REQUESTED}, {@code APPROVED}, {@code DENIED}, {@code CANCELLED}, {@code REVOKED} or
 *        {@code EXPIRED} (an approved window that has ended, or a request nobody answered in time)
 * @param requestedMinutes how long they asked for
 * @param requestedAt when they asked
 * @param accessExpiresAt when the approved window ends, if approved
 * @param active whether the access can be used right now
 */
public record SupportAccessView(@NotNull UUID id, @NotNull String requester, @NotNull String reason,
        @NotNull String status, int requestedMinutes, @NotNull Instant requestedAt, Instant accessExpiresAt,
        boolean active) {
}
