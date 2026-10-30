package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One field map of a mapping (Sprint 28, ADR-0218).
 *
 * @param external the name of the field on the external side: letters, digits and underscores, starting with a letter
 * @param platform the API name of the field of the object
 * @param direction {@code OUTBOUND}, {@code INBOUND} or {@code BOTH}
 * @param externalType what the external side carries: {@code TEXT}, {@code NUMBER}, {@code BOOLEAN}, {@code DATE},
 *        {@code DATETIME} or {@code TIME} (dates and times travel as ISO text)
 * @param required whether a value must exist (after the default) for the field to be sent or accepted
 * @param defaultValue the value used when there is none, written as a field default is written, or null
 * @param conversion {@code NONE}, {@code VALUE_MAP}, {@code NUMBER_SCALE} or {@code TRIM}
 * @param valueMap for {@code VALUE_MAP}: the platform value and the external value of each pair
 * @param scale for {@code NUMBER_SCALE}: the factor the platform number is multiplied by on its way out
 * @param outExpression an expression over the object's fields that gives the value to send, or null
 * @param inExpression an expression over the external fields that gives the value to store, or null
 */
public record MappingFieldSpec(@NotBlank @Size(max = 64) String external, @NotBlank @Size(max = 100) String platform,
        @NotBlank @Size(max = 10) String direction, @NotBlank @Size(max = 10) String externalType,
        @NotNull Boolean required, @Size(max = 500) String defaultValue, @NotBlank @Size(max = 20) String conversion,
        @Size(max = 200) @Valid List<MappingValuePair> valueMap, @Size(max = 40) String scale,
        @Size(max = 4000) String outExpression, @Size(max = 4000) String inExpression) {

    public MappingFieldSpec {
        valueMap = valueMap == null ? List.of() : List.copyOf(valueMap);
    }

    @Override
    public String toString() {
        return "MappingFieldSpec[redacted]";
    }
}
