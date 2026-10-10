package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One thing a release (or a change set that would become one) adds, changes or removes (Sprint 11).
 *
 * @param action {@code ADDED}, {@code CHANGED} or {@code REMOVED}
 * @param kind {@code OBJECT}, {@code FIELD}, {@code RECORD_TYPE}, {@code LAYOUT}, {@code RECORD_PAGE},
 *        {@code APPLICATION} or {@code LAYOUT_ASSIGNMENT}
 * @param objectApiName the object it is about, empty for an application
 * @param itemApiName the field, record type, layout, record page, application or assignment key, or null for the
 *        object itself
 */
public record ReleaseItemView(@NotNull String action, @NotNull String kind, @NotNull String objectApiName,
        String itemApiName) {
}
