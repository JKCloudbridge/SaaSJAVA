package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Creates a record (Sprint 30, ADR-0245). A property that is not listed here is refused, with its place, never
 * ignored.
 *
 * @param fields the values by field API name; a key with a null value is the same as leaving it out
 * @param recordType the API name of the record type of the new record; left out, the default record type of the
 *        object is used if it has one
 */
@Schema(description = "The values of a new record. Every key of `fields` must be a field of the object that the "
        + "caller may edit and that people can write (not a system, calculated or retired field). A number is "
        + "written as a JSON number, never as text.")
public record PublicCreateRequest(
        @NotNull @Schema(description = "The values by field API name. A number must be a JSON number: a number "
                + "written as text is refused. Keep every digit: write a decimal with the digits you mean "
                + "(`1250.50`), and do not let a program that rounds to 15 digits write it.",
                example = "{\"Name\": \"A. Example\", \"Salary__c\": 1250.50}") Map<String, Object> fields,
        @Size(max = 80) @Schema(description = "The API name of the record type. Left out, the default one is used.")
        String recordType) {

    /** Copies the values, so that they cannot be changed from outside; a null value is no value. */
    public PublicCreateRequest {
        fields = fields == null ? Map.of() : Map.copyOf(withoutNulls(fields));
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>(source);
        copy.values().removeIf(Objects::isNull);
        return copy;
    }

    /** Prints nothing of the values. */
    @Override
    public String toString() {
        return "PublicCreateRequest[redacted]";
    }
}
