package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What to do to a circuit (Sprint 29, ADR-0229). The keys are the ones {@link CircuitView#actions()} offers; nothing
 * else is accepted.
 *
 * @param action the key of the action
 */
public record CircuitRequest(@NotBlank @Size(max = 40) String action) {
}
