package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What the signed-in member may do with the records of an object, for drawing a screen (Sprint 19, ADR-0129). It
 * says nothing about one record: whether they may change <em>that</em> record is answered by the API when they try,
 * because record-level security is decided per record. Presentation only; every request is decided again.
 *
 * @param create whether they may create records
 * @param update whether they may change records
 * @param delete whether they may delete records
 */
public record FormActionsView(@NotNull Boolean create, @NotNull Boolean update, @NotNull Boolean delete) {
}
