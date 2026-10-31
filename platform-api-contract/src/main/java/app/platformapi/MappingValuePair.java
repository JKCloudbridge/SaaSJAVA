package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One pair of a value map (Sprint 28, ADR-0218).
 *
 * @param platform the value on the platform's side
 * @param external the value on the external side
 */
public record MappingValuePair(@NotNull @Size(max = 100) String platform, @NotNull @Size(max = 100) String external) {

    @Override
    public String toString() {
        return "MappingValuePair[redacted]";
    }
}
