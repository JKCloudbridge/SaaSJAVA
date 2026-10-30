package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The answer of the public receiver (Sprint 28, ADR-0221): one stable word and nothing about the organization, the
 * connection or the record.
 *
 * @param result {@code accepted}, {@code unchanged} or {@code skipped}
 */
public record InboundResultView(@NotNull String result) {
}
