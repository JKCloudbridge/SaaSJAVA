/**
 * Approval module: the approval engine (Sprint 24, ADR-0145 to ADR-0153). A configured process with entry criteria and
 * ordered steps decides a record; the people asked are worked out when a step becomes active; a request is pinned to
 * the
 * version of the process it started under; a pending request locks its record; delegation adds a delegate beside a
 * delegator; the end of a request sets fields of the record and announces itself so that workflows can follow.
 *
 * <p>What lives here: the requests and their steps, tasks and history, the engine that submits, decides, recalls,
 * reassigns and cancels, the resolution of approvers, delegations, the implementation of the shared-kernel contracts
 * {@code RecordLocks} (the record lock) and {@code ApprovalUsage} (a process with a pending request is not removed),
 * and
 * the endpoints. What does <em>not</em> live here: the definitions of processes and their lifecycle (draft, check,
 * publish, rollback) are metadata and belong to the {@code metadata} module, which this module reads through
 * {@code Metadata} and {@code MetadataResolver}. The write that sets fields of a record when a request ends goes
 * through
 * {@code data.BackgroundWrites}, the one reviewed place where the platform writes records (ADR-0139, ADR-0149).
 *
 * <p>The public API of this package is {@link app.platform.approval.Approvals}, through which the workflow module
 * starts
 * an approval as the platform (ADR-0150). {@code approval} never depends on {@code workflow}: what follows an outcome
 * is
 * a workflow that starts from the {@code approval.*} events (ADR-0149).
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is internal.
 * Allowed outgoing dependencies are declared below and verified by the architecture tests. The module needs nothing
 * from
 * {@code expression}: the compiled entry criteria arrive through the runtime contract of {@code metadata}.
 */
@ApplicationModule(
        displayName = "Approval",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security",
            "data",
            "identity",
            "licensing"
        })
package app.platform.approval;

import org.springframework.modulith.ApplicationModule;
