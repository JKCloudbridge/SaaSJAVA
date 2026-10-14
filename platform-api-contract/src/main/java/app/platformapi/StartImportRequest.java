package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Starts a CSV import into an object (Sprint 17). The file is sent in a second request. The import is always a
 * background job: it creates, or finds by an external identifier and updates, one record for each row of the file,
 * through the same steps as any write, as the member who started it. The text is typed by a person, so it never prints.
 *
 * @param operation {@code CREATE} (every row makes a record) or {@code UPSERT} (a row finds its record by the value of
 *        the external identifier field and updates it, or makes it when there is none)
 * @param externalIdField for {@code UPSERT}: the API name of the field, flagged as an external identifier, that finds
 *        the record; it must be one of the mapped columns
 * @param dryRun when true, every row is checked by the real write and nothing is saved; the report says what a real
 *        run would do (at most the dry-run limit of rows)
 * @param delimiter {@code COMMA} (the default), {@code SEMICOLON} or {@code TAB}; the file is UTF-8
 * @param mapping the name of a column of the file to the field it fills: {@code field}, or for a link
 *        {@code field:externalIdFieldOfTheLinkedObject} when the cell holds the other record's external identifier
 *        instead of its identifier. Left out, the names of the columns are the API names of the fields
 */
public record StartImportRequest(@NotNull @Size(max = 10) String operation,
        @Size(max = 80) String externalIdField, Boolean dryRun, @Size(max = 10) String delimiter,
        @Size(max = 200) Map<@Size(max = 80) String, @Size(max = 140) String> mapping) {

    /** Copies the mapping. */
    public StartImportRequest {
        mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
    }

    @Override
    public String toString() {
        return "StartImportRequest[redacted]";
    }
}
