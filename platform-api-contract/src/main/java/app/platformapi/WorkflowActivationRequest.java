package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Switches a published workflow on or off without touching its definition (Sprint 22, ADR-0136). No new version of the
 * definition is made.
 *
 * @param active whether the workflow starts new executions
 * @param version the version of the workflow the caller last read
 */
public record WorkflowActivationRequest(@NotNull Boolean active, @NotNull Long version) {
}
