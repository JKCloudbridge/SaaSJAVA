package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One key of the order of a record query (Sprint 16). Records without a value in the field come last, in both
 * directions; ties are broken by the identifier.
 *
 * @param field the API name of a field the caller may read (a text, number, day, day and time, or checkbox; a formula
 *        or a link cannot be used)
 * @param descending whether the largest comes first; left out means smallest first
 */
public record QuerySortKey(@NotBlank @Size(max = 80) String field, Boolean descending) {
}
