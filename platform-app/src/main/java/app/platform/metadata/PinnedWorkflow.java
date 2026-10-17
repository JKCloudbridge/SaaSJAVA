package app.platform.metadata;

/**
 * One version of a workflow, made ready, with the standing of the workflow it belongs to now (Sprint 22, ADR-0136). An
 * execution runs the version it was created under, whatever has been published since; whether it may still run is a
 * question about the workflow today.
 *
 * @param workflow the version, ready to judge records
 * @param active whether the workflow starts new executions now (a switched-off workflow also stops queued ones)
 * @param removed whether the workflow has been removed
 */
public record PinnedWorkflow(CompiledWorkflow workflow, boolean active, boolean removed) {
}
