package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One thing a spreadsheet file held that the sheet does not, or holds in another form (UI3B, ADR-0195): a count and the
 * words for it, never a description of the content.
 *
 * @param code a lower case word naming the kind (for example {@code images}, {@code merges_left_out}, {@code dates})
 * @param count how many, at least one
 * @param leftOut true when it did not come in, false when it came in in another form (a formula read as its last
 *        value, a date written year-month-day)
 * @param words the plain sentence for the person
 */
public record SheetImportNoteView(@NotNull String code, @NotNull Integer count, @NotNull Boolean leftOut,
        @NotNull String words) {
}
