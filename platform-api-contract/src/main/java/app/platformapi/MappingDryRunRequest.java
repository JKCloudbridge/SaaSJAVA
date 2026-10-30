package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Tries a definition on a sample the person types (Sprint 28, ADR-0217). Nothing is stored, sent, logged or audited.
 *
 * @param entity the kind of entity on the external side
 * @param definition the definition to try, usually the draft as it stands in the screen
 * @param direction {@code OUTBOUND} (the values are those of a record, by field API name) or {@code INBOUND} (the
 *        values are those of the external entity, by external field name)
 * @param values the sample values written as text
 */
public record MappingDryRunRequest(@NotBlank @Size(max = 40) String entity, @NotNull @Valid MappingSpec definition,
        @NotBlank @Size(max = 10) String direction,
        @NotNull @Size(max = 100) Map<String, @Size(max = 1000) String> values) {

    public MappingDryRunRequest {
        values = values == null ? null : Map.copyOf(values);
    }

    @Override
    public String toString() {
        return "MappingDryRunRequest[redacted]";
    }
}
