package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One intended change of a change set (Sprint 11).
 *
 * @param id the identifier, for removing it
 * @param position the order the changes are applied in (1 is first)
 * @param kind what the change does, for example {@code CREATE_FIELD}
 * @param objectApiName the object it is about
 * @param itemApiName the field or record type it is about, or null
 */
public record ChangeView(@NotNull String id, @NotNull Integer position, @NotNull String kind,
        @NotNull String objectApiName, String itemApiName) {
}
