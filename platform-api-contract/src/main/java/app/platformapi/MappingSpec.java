package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The definition of a mapping (Sprint 28, ADR-0216, ADR-0218): how the fields of one platform object correspond to the
 * fields of one kind of entity of an external system. A closed description with no executable text of its own: a
 * transformation is an expression of the platform's expression language, checked and run by the engine.
 *
 * <p>It is text of the organization: it never reaches a log, an audit record, an event or a metric.
 *
 * @param conflict who wins when both sides changed a field that goes both ways: {@code PLATFORM_UNLESS_NEWER} (the
 *        platform, unless the outside change is newer), {@code EXTERNAL_WINS} or {@code PLATFORM_WINS}
 * @param changedAtField the external field that says when the entity last changed, a date and time; needed by
 *        {@code PLATFORM_UNLESS_NEWER} to know whether the outside change is newer
 * @param inboundCreate whether a change for an entity the platform has no record for may make one
 * @param inboundUpdate whether a change for an entity the platform has a record for may change it
 * @param fields the field maps, at most 100
 */
public record MappingSpec(@NotBlank @Size(max = 30) String conflict, @Size(max = 64) String changedAtField,
        @NotNull Boolean inboundCreate, @NotNull Boolean inboundUpdate,
        @NotNull @Size(max = 100) @Valid List<MappingFieldSpec> fields) {

    public MappingSpec {
        fields = fields == null ? null : List.copyOf(fields);
    }

    @Override
    public String toString() {
        return "MappingSpec[redacted]";
    }
}
