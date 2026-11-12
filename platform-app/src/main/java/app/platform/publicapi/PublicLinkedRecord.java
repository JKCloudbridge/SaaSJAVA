package app.platform.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * What a query brings of the record a link points at (Sprint 30, ADR-0245).
 *
 * @param id the identifier the link holds
 * @param fields the fields asked for that the caller may read; empty when the link points at nothing, at a deleted
 *        record, at an object the caller may not read or at an object the platform itself owns (all alike)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The record a link points at, as far as the caller may see it.")
public record PublicLinkedRecord(
        @NotNull @Schema(description = "The identifier the link holds.") String id,
        @NotNull @Schema(description = "The fields asked for of the linked record that the caller may read. Empty "
                + "when there is nothing the caller may see, whatever the reason.") Map<String, Object> fields) {

    /** Copies the values. */
    public PublicLinkedRecord {
        fields = Map.copyOf(fields);
    }

    /** Prints the identifier only. */
    @Override
    public String toString() {
        return "PublicLinkedRecord[" + id + "]";
    }
}
