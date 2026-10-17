package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * What a replay of one execution did (Sprint 23, ADR-0141).
 *
 * @param execution the execution that was to be replayed
 * @param outcome {@code REPLAYED} (a new execution was queued), {@code ALREADY_REPLAYED} (it was replayed before; the
 *        existing replay is given), {@code NOT_REPLAYABLE} (only a dead-lettered run is replayed) or {@code NOT_FOUND}
 * @param replay the execution that replays it, for {@code REPLAYED} and {@code ALREADY_REPLAYED}
 * @param reason for {@code NOT_REPLAYABLE}: a stable key such as {@code not_dead_lettered}
 */
public record WorkflowReplayResultView(@NotNull UUID execution, @NotNull String outcome, UUID replay, String reason) {
}
