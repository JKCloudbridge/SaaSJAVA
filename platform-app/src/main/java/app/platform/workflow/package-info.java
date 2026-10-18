/**
 * Workflow module: the runtime of automation defined as data (Sprint 22, ADR-0133 to ADR-0137). When something happens
 * to a record (or at a time), the platform judges the organization's workflows and runs the actions of the ones that
 * apply, asynchronously after the commit, once per cause, with a record of every run.
 *
 * <p>Since Sprint 23 the actions change the world: an update or a create of a record goes through
 * {@code BackgroundWrites} of the {@code data} module (the whole write pipeline as the platform, ADR-0139), a mail is
 * queued through the shared-kernel {@code MailQueue} (ADR-0138), a failed attempt is retried with a backoff or
 * dead-lettered and can be replayed by a person (ADR-0141 to ADR-0143), a workflow that starts itself is stopped by a
 * chain carried in the events (ADR-0140), and the events the relay gave up on are listed and put back through the
 * shared-kernel
 * {@code ParkedEvents}. No new module edge was needed.
 *
 * <p>What lives here: the handler that turns a record event into executions, the dispatcher that claims queued
 * executions and due schedules of every organization (the one place that enters the system scope
 * {@code workflow_dispatch}, ADR-0135) and runs each in the organization's own tenant context, the schedules of timed
 * workflows, the closed seam of actions, the history of executions and its endpoints. What does <em>not</em> live
 * here: the definitions of workflows, their versions and their lifecycle (draft, check, publish, rollback) are metadata
 * and belong to the {@code metadata} module, which this module reads through {@code Metadata} and
 * {@code MetadataResolver}.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests. The module needs
 * nothing from {@code expression}: the compiled conditions arrive through the runtime contract of {@code metadata}, as
 * the compiled rules do for {@code data} (ADR-0136). {@code notification} and {@code integration} were listed in the
 * plan
 * and are added by the sprints that need them, not before. Since Sprint 24 the action {@code START_APPROVAL} submits a
 * record through the public interface {@code Approvals} of the {@code approval} module (new edge
 * {@code workflow -> approval}, ADR-0150); the module {@code approval} never depends on this one, so there is no cycle:
 * what follows the end of an approval is a workflow that starts from the {@code approval.*} events (ADR-0149).
 */
@ApplicationModule(
        displayName = "Workflow",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "metadata",
            "security",
            "data",
            "identity",
            "licensing",
            "observability",
            "approval"
        })
package app.platform.workflow;

import org.springframework.modulith.ApplicationModule;
