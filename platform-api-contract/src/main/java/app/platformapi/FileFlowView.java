package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * How the files of one connection stand (Sprint 29A, ADR-0242): when the inbox was last looked at and what that found,
 * how many files wait, are being applied, were applied and were set aside. Counts, times and stable reasons only; never
 * the name of a file, a path or a row.
 *
 * @param lastLookAt when the inbox was last looked at, or null before the first look
 * @param lastResult {@code LOOKED}, {@code HELD} (the look was not allowed: the connection is off, sending is paused or
 *        there is no live mapping) or {@code FAILED}; null before the first look
 * @param lastReason the stable reason of a held or failed look, or null
 * @param lastReasonWords the platform's sentence for the reason, or null
 * @param nextLookAt when the inbox is looked at next
 * @param filesWaiting files seen in the inbox at the last look that were not taken yet
 * @param filesInProgress files taken from the inbox that are not finished (a restart finishes them)
 * @param filesDone files applied completely (within the retention)
 * @param filesSetAside files moved to the error directory for a person to look at (within the retention)
 */
public record FileFlowView(Instant lastLookAt, String lastResult, String lastReason, String lastReasonWords,
        @NotNull Instant nextLookAt, @NotNull Long filesWaiting, @NotNull Long filesInProgress,
        @NotNull Long filesDone, @NotNull Long filesSetAside) {
}
