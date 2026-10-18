package app.platformapi;

import jakarta.validation.constraints.Min;

/**
 * Replays one dead-lettered execution (Sprint 23, ADR-0141). The new execution resumes at the step that failed and
 * runs against the record as it is now; the condition is not judged again.
 *
 * @param fromStep a step to resume at instead of the one that failed: the failed step or a later one (a step that can
 *        never succeed can be passed over; a step that completed is never run again), or left out
 */
public record WorkflowReplayRequest(@Min(1) Integer fromStep) {
}
