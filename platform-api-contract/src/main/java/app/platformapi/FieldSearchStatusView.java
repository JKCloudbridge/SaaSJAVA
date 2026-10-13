package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Where the words of one searchable field stand (Sprint 17).
 *
 * @param field the API name of the field
 * @param state {@code WAITING} (flagged, the background pass has not started on it), {@code FILLING} (the words of the
 *        records that existed before the flag are being written) or {@code COMPLETE} (searched)
 * @param recordsDone how many records the fill has looked at
 */
public record FieldSearchStatusView(@NotNull String field, @NotNull String state, long recordsDone) {
}
