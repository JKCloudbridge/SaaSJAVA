package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Saves the draft of a mapping (Sprint 28, ADR-0217). The first save for an object also makes the mapping and names
 * its entity; later saves must carry the same entity and the version of the draft that was read.
 *
 * @param entity the kind of entity on the external side: lower case letters, digits and dashes
 * @param definition the definition
 * @param version the version of the draft read, or 0 when there is none
 */
public record SaveMappingRequest(@NotBlank @Size(max = 40) String entity, @NotNull @Valid MappingSpec definition,
        @NotNull @PositiveOrZero Long version) {

    @Override
    public String toString() {
        return "SaveMappingRequest[redacted]";
    }
}
