package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Switches a published approval process on or off without touching its definition (Sprint 24, ADR-0145). No new version
 * of
 * the definition is made. A request already pending under a process that is switched off finishes (ADR-0152).
 *
 * @param active whether new requests may start under the process
 * @param version the version of the process the caller last read
 */
public record ApprovalProcessActivationRequest(@NotNull Boolean active, @NotNull Long version) {
}
