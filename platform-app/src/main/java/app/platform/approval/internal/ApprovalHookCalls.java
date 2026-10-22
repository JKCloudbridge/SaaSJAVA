package app.platform.approval.internal;

import app.platform.approval.internal.ApprovalStore.InstanceRow;
import app.platform.approval.internal.ApprovalStore.StepRow;
import app.platform.data.BackgroundWrites;
import app.platform.metadata.Metadata;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHooks;
import app.platform.sharedkernel.approval.ApprovalHooks.DecisionContext;
import app.platform.sharedkernel.approval.ApprovalHooks.EndContext;
import app.platform.sharedkernel.approval.ApprovalHooks.SubmitContext;
import app.platform.sharedkernel.approval.ApprovalOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where the engine calls the hooks of business modules (FA4A, ADR-0187). One place, so that every call is made the same
 * way:
 *
 * <ul>
 * <li><strong>Found by object.</strong> No hook for the object means one map lookup and nothing else.</li>
 * <li><strong>In a savepoint.</strong> A refusal or a failure undoes whatever the hook wrote in this call, even when
 * the caller (a workflow step) goes on with its own transaction; the engine's callers then roll the rest back.</li>
 * <li><strong>A refusal is audited, and the audit survives.</strong> The record of the refusal is written in a
 * transaction of its own, because the transaction that carried the refused change disappears with it.</li>
 * <li><strong>A failure tells nothing.</strong> Any other exception becomes {@link ApprovalHookFailure}, which has no
 * message and no cause; the log has the object, the call and the exception's type name.</li>
 * <li><strong>Measured, not interrupted.</strong> The time of each call is a metric; a call over
 * {@code platform.approval.hooks.slow-call} is counted and logged by identifiers.</li>
 * </ul>
 *
 * <p>A hook is asked to submit only for an object the organization has: a shipped object whose plan feature is missing
 * is left out of {@link Metadata}, so a cluster's hook never runs for an organization without the cluster. The other
 * two calls are made whenever the object has a hook, because a request that exists must be finished by the hook that
 * saw it begin, even if the organization lost the feature meanwhile (ADR-0152).
 */
@Component
class ApprovalHookCalls {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalHookCalls.class);

    private static final String SUBMIT = "submit";
    private static final String BEFORE_APPROVE = "before_approve";
    private static final String ENDED = "ended";

    private final ApprovalHookRegistry registry;
    private final BackgroundWrites writes;
    private final ApprovalAudit audit;
    private final Metadata metadata;
    private final MeterRegistry meters;
    private final Duration slow;
    private final TransactionTemplate savepoint;
    private final TransactionTemplate own;

    ApprovalHookCalls(ApprovalHookRegistry registry, BackgroundWrites writes, ApprovalAudit audit, Metadata metadata,
            MeterRegistry meters, ApprovalProperties properties, PlatformTransactionManager transactions) {
        this.registry = registry;
        this.writes = writes;
        this.audit = audit;
        this.metadata = metadata;
        this.meters = meters;
        this.slow = properties.hooks().slowCall();
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * A request is about to be created.
     *
     * @throws ApprovalHookRefusal when the hook refuses
     */
    void submitted(SubmitContext context) {
        Optional<ApprovalHooks> hook = registry.of(context.object());
        if (hook.isEmpty() || metadata.object(context.object()).isEmpty()) {
            return;
        }
        call(SUBMIT, context.object(), context.recordId(), context.requestId(), context.process(),
                context.submitter(), true, () -> hook.get().onSubmit(context));
    }

    /**
     * A person is about to approve their task of the active step.
     *
     * @throws ApprovalHookRefusal when the hook refuses
     */
    void beforeApprove(InstanceRow instance, StepRow step, UUID approver) {
        Optional<ApprovalHooks> hook = registry.of(instance.objectApiName());
        if (hook.isEmpty()) {
            return;
        }
        BackgroundWrites.Source source = BackgroundWrites.Source.approvalHook(instance.processApiName(),
                instance.id(), chainOf(instance));
        RequestHookWriter writer = new RequestHookWriter(writes, savepoint, source, instance.objectApiName(),
                instance.recordId(), hook.get().writes());
        DecisionContext context = new DecisionContext(instance.id(), instance.objectApiName(), instance.recordId(),
                instance.processApiName(), instance.processVersionNumber(), instance.version(), step.position(),
                step.name(), approver);
        try {
            call(BEFORE_APPROVE, instance.objectApiName(), instance.recordId(), instance.id(),
                    instance.processApiName(), approver, true, () -> hook.get().beforeApprove(context, writer));
        } finally {
            writer.close();
        }
    }

    /** The request ended: the hook may keep what it holds outside the record in step. It cannot refuse. */
    void ended(InstanceRow instance, ApprovalOutcome outcome, UUID endedBy) {
        Optional<ApprovalHooks> hook = registry.of(instance.objectApiName());
        if (hook.isEmpty()) {
            return;
        }
        UUID person = endedBy == null || endedBy.equals(ActorId.SYSTEM.value()) ? null : endedBy;
        EndContext context = new EndContext(instance.id(), instance.objectApiName(), instance.recordId(),
                instance.processApiName(), instance.processVersionNumber(), instance.version(), outcome, person);
        call(ENDED, instance.objectApiName(), instance.recordId(), instance.id(),
                instance.processApiName(), person, false, () -> hook.get().onEnded(context));
    }

    private void call(String call, String object, UUID recordId, UUID request, String process,
            UUID actor, boolean mayRefuse, Runnable body) {
        long started = System.nanoTime();
        String result = "ok";
        try {
            savepoint.executeWithoutResult(status -> body.run());
        } catch (ApprovalHookRefusal refusal) {
            if (!mayRefuse) {
                result = "failed";
                LOG.warn("An approval hook refused a call that cannot be refused: object={} call={}", object, call);
                throw new ApprovalHookFailure();
            }
            result = "refused";
            // The transaction that carries the refused change is about to roll back, and the record of the refusal must
            // not go with it.
            String reason = refusal.reason();
            own.executeWithoutResult(status -> audit.hookRefused(call, actor, object, recordId, request, process,
                    reason));
            throw refusal;
        } catch (RuntimeException failure) {
            result = "failed";
            // The type only: the message of an exception can quote data (ADR-0012).
            LOG.warn("An approval hook failed: object={} call={} type={}", object, call,
                    failure.getClass().getName());
            throw new ApprovalHookFailure();
        } finally {
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            Timer.builder("platform.approval.hook").tag("call", call).tag("result", result).register(meters)
                    .record(took);
            if (took.compareTo(slow) > 0) {
                meters.counter("platform.approval.hook.slow", "call", call).increment();
                LOG.warn("An approval hook was slow: object={} call={} millis={}", object, call, took.toMillis());
            }
        }
    }

    private static BackgroundWrites.Chain chainOf(InstanceRow instance) {
        return instance.chainRoot() == null ? null
                : new BackgroundWrites.Chain(instance.chainRoot(), instance.chainDepth(), instance.chainWorkflows());
    }
}
