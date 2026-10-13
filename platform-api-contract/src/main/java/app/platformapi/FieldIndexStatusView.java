package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Where the index of one field stands (Sprint 16).
 *
 * @param field the API name of the field
 * @param state {@code WAITING} (flagged, no slot free yet or a change still being applied), {@code FILLING} (the
 *        copy of the records that exist is being written), {@code FILLED} (complete: queries use it) or
 *        {@code CLEARING} (the field is no longer indexed and its copies are being removed)
 * @param recordsDone how many records the fill or the clearing has looked at so far
 * @param flagged whether the field is flagged as indexed now
 */
public record FieldIndexStatusView(@NotNull String field, @NotNull String state, @NotNull Long recordsDone,
        @NotNull Boolean flagged) {
}
