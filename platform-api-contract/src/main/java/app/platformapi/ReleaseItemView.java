package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One thing a release (or a change set that would become one) adds, changes or removes (Sprint 11).
 *
 * @param action {@code ADDED}, {@code CHANGED} or {@code REMOVED}
 * @param kind {@code OBJECT}, {@code FIELD} or {@code RECORD_TYPE}
 * @param objectApiName the object it is about
 * @param itemApiName the field or record type, or null for the object itself
 */
public record ReleaseItemView(@NotNull String action, @NotNull String kind, @NotNull String objectApiName,
        String itemApiName) {
}
