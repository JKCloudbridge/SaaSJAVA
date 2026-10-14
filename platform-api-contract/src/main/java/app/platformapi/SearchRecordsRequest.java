package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Searches the searchable fields of one object (Sprint 17). The text is typed by a person, so it never prints.
 *
 * @param text the words to look for: a record must have every word, accents and case do not matter, and the last word
 *        counts as the beginning of a word (the person is still typing); at most the first five words are used
 * @param limit the page size; left out means the default, and it is limited
 * @param cursor the cursor of the previous page, which is good only for the same text
 */
public record SearchRecordsRequest(@NotNull @Size(min = 1, max = 200) String text, Integer limit,
        @Size(max = 200) String cursor) {

    @Override
    public String toString() {
        return "SearchRecordsRequest[redacted]";
    }
}
