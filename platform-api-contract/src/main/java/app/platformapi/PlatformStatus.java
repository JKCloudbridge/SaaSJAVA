package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Answer of the platform status endpoint. Receiving it means the API is up and its database answered; if the
 * database were unreachable the endpoint returns the error model with {@link ErrorCode#SERVICE_UNAVAILABLE}
 * instead. Carries nothing about the deployment (no versions of components, no host names).
 *
 * @param service name of the service
 * @param apiVersion the API version that answered, for example {@code v1}
 * @param serverTime the time on the API server
 * @param databaseTime the time reported by the database, which proves a query ran
 */
public record PlatformStatus(
        @NotNull String service,
        @NotNull String apiVersion,
        @NotNull Instant serverTime,
        @NotNull Instant databaseTime) {
}
