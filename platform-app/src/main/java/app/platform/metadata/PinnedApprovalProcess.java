package app.platform.metadata;

/**
 * One version of an approval process, made ready, with the standing of the process it belongs to now (Sprint 24,
 * ADR-0145). A request runs the version it started under, whatever has been published since; whether new requests may
 * start under the process is a question about the process today.
 *
 * @param process the version, ready to judge records
 * @param active whether new requests may start under the process now
 * @param removed whether the process has been removed
 */
public record PinnedApprovalProcess(CompiledApprovalProcess process, boolean active, boolean removed) {
}
