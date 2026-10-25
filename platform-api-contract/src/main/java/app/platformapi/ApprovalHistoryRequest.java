package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Asks for the approval history of a few records of one object (FA6E, ADR-0199): every request of each record, newest
 * first, so a screen can draw the whole story of a record (for example a template and the change requests made on it)
 * with two calls.
 *
 * @param recordIds the records, at most 20; one the caller cannot read is left out as if it did not exist
 * @param perRecord how many requests to return for each record, newest first; 5 when left out, at most 10
 */
public record ApprovalHistoryRequest(@NotNull @Size(min = 1, max = 20) List<@NotNull UUID> recordIds,
        Integer perRecord) {
}
