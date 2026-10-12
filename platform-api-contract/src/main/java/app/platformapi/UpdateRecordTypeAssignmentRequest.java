package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Makes a record type assignment the default of its profile, or takes the mark off (Sprint 15A, ADR-0098).
 *
 * @param defaultForProfile whether the record type is the one a new record of the profile gets when it names none;
 *        making it so takes the mark off the profile's previous default of the object
 * @param version the version the caller last read
 */
public record UpdateRecordTypeAssignmentRequest(@NotNull Boolean defaultForProfile, @NotNull Long version) {
}
