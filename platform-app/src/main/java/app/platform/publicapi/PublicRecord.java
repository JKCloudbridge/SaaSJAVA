package app.platform.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

/**
 * One record as the public API shows it (Sprint 30, ADR-0245). The system properties are the fixed block at the top;
 * the values of the object's fields are in {@code fields}, keyed by field API name.
 *
 * @param id the identifier: opaque, time-ordered, never a count
 * @param object the API name of the object
 * @param recordType the API name of the record type; left out when the record has none
 * @param version the version of the record; it is also the ETag of the record, and the value to send in
 *        {@code If-Match} when changing it
 * @param ownerId the user who owns the record
 * @param createdAt when it was created, in UTC
 * @param updatedAt when it was last changed, in UTC
 * @param deletedAt when it was moved to the recycle bin; left out for a live record
 * @param fields the values by field API name; a field without a value is left out, and so is a field the caller may
 *        not read (it is absent, never null and never masked)
 * @param linked in the answer of a query: what was brought of the records the links point at, by the API name of the
 *        link field; left out elsewhere
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One record. The properties above `fields` are the system properties; the values of the "
        + "object's own fields are in `fields`.")
public record PublicRecord(
        @NotNull @Schema(description = "The identifier of the record: opaque and never a count.",
                example = "0194f2a8-6c1e-7a3b-9d52-3f1c8e2b7a40") String id,
        @NotNull @Schema(description = "The API name of the object.", example = "Employee__c") String object,
        @Schema(description = "The API name of the record type. Left out when the record has none.") String recordType,
        @NotNull @Schema(description = "The version of the record. It is also the ETag of the record, and the value "
                + "to send in If-Match when changing it.", example = "3") Long version,
        @NotNull @Schema(description = "The user who owns the record.",
                example = "0194f2a8-1111-7a3b-9d52-3f1c8e2b7a40") String ownerId,
        @NotNull @Schema(description = "When the record was created, in UTC.",
                example = "2026-10-31T09:30:00Z") Instant createdAt,
        @NotNull @Schema(description = "When the record was last changed, in UTC.",
                example = "2026-10-31T09:45:12.250Z") Instant updatedAt,
        @Schema(description = "When the record was moved to the recycle bin. Left out for a live record.")
        Instant deletedAt,
        @NotNull @Schema(description = "The values by field API name. A field without a value, and a field the "
                + "caller may not read, are left out (never null, never masked). A number is a JSON number with "
                + "exactly the digits stored; a day is `2026-10-31`; a day and time is UTC ISO-8601; a link is the "
                + "identifier of the record it points to.",
                example = "{\"Name\": \"A. Example\", \"Salary__c\": 1250.50}") Map<String, Object> fields,
        @Schema(description = "Only in the answer of a query that asked to follow links: for each link field, the "
                + "identifier it holds and the fields asked for of the record it points to. A link to a record the "
                + "caller cannot read, or that is deleted, has the identifier alone.")
        Map<String, PublicLinkedRecord> linked) {

    /** Copies the values, so that they cannot be changed from outside. */
    public PublicRecord {
        fields = Map.copyOf(fields);
        linked = linked == null || linked.isEmpty() ? null : Map.copyOf(linked);
    }

    /** Prints nothing of the values: they are what people typed. */
    @Override
    public String toString() {
        return "PublicRecord[redacted]";
    }
}
