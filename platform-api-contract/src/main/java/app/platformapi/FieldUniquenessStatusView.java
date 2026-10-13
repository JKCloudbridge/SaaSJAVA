package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Where the enforcement of the uniqueness of one field stands (Sprint 16A), or of the pair of parents of a junction.
 *
 * @param field the API name of the unique field; for a junction the first of its two parents
 * @param partner the second parent of a junction, or absent for a unique field
 * @param state {@code WAITING} (the copy of the field is not complete yet, so nothing is enforced), {@code ENFORCING}
 *        (the records that existed before are being checked in; new values are refused already), {@code CONFLICT}
 *        (two existing records hold the same value, so the check cannot finish; it resumes when the data is fixed),
 *        {@code ENFORCED} (the database refuses a duplicate) or {@code NOT_ENFORCED} (the field is flagged unique but
 *        its setup cannot be checked, for example a number wider than 15 digits; edit the field)
 * @param recordsDone how many records the check has covered so far
 * @param collidingRecords in {@code CONFLICT}: how many records share a value with another record
 */
public record FieldUniquenessStatusView(@NotNull String field, String partner, @NotNull String state,
        @NotNull Long recordsDone, @NotNull Long collidingRecords) {
}
