package app.platform.approval.internal;

import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.events.EventPublisher;
import app.platform.sharedkernel.events.NewEvent;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an approval leaves behind besides its rows (ADR-0145, ADR-0147): the audit record, the {@code approval.*} event
 * of
 * the outbox, and the metrics. All of it is names, identifiers, kinds, versions and stable reasons.
 * <strong>Never</strong>
 * a comment, a value of the record, the text of a criteria, a label or an exception message: the records and the events
 * are retained and leave the platform's control (ADR-0089).
 */
@Component
class ApprovalAudit {

    private final AuditRecorder audit;
    private final EventPublisher events;
    private final TenantContexts contexts;
    private final MeterRegistry meters;
    private final JsonMapper json = JsonMapper.builder().build();

    ApprovalAudit(AuditRecorder audit, EventPublisher events, TenantContexts contexts, MeterRegistry meters) {
        this.audit = audit;
        this.events = events;
        this.contexts = contexts;
        this.meters = meters;
    }

    // ---- the life of a request ----

    void submitted(UUID actor, ApprovalStore.InstanceRow instance, String how) {
        record("submitted", actor, instance, Map.of("how", how));
        publish("approval.submitted", instance, null);
        meters.counter("platform.approval.instances", "result", "submitted").increment();
    }

    /** A request ended: {@code approved}, {@code rejected}, {@code recalled} or {@code cancelled}. */
    void ended(String outcome, UUID actor, ApprovalStore.InstanceRow instance) {
        record(outcome, actor, instance, Map.of());
        publish("approval." + outcome, instance, outcome.toUpperCase(java.util.Locale.ROOT));
        meters.counter("platform.approval.instances", "result", outcome).increment();
    }

    /** One decision of a person on a task: {@code approve} or {@code reject}. */
    void decision(String kind, UUID actor, ApprovalStore.InstanceRow instance, int step, boolean onBehalf) {
        record("decision." + kind, actor, instance, Map.of("step", Integer.toString(step),
                "on_behalf", Boolean.toString(onBehalf)));
        meters.counter("platform.approval.decisions", "kind", kind).increment();
    }

    void stepFinished(ApprovalStore.StepRow step, java.time.Instant now) {
        if (step.activatedAt() != null) {
            Timer.builder("platform.approval.step.duration").register(meters)
                    .record(Duration.between(step.activatedAt(), now));
        }
    }

    void reassigned(UUID actor, ApprovalStore.InstanceRow instance, int step, boolean byAdministrator) {
        record(byAdministrator ? "assigned" : "reassigned", actor, instance,
                Map.of("step", Integer.toString(step)));
        meters.counter("platform.approval.decisions", "kind", byAdministrator ? "assign" : "reassign").increment();
    }

    void attention(ApprovalStore.InstanceRow instance, int step, String reason, boolean raised) {
        record(raised ? "attention.raised" : "attention.cleared", null, instance,
                Map.of("step", Integer.toString(step), "reason", reason == null ? "none" : reason));
    }

    void outcomeFailed(ApprovalStore.InstanceRow instance, String reason) {
        record("outcome.failed", null, instance, Map.of("reason", reason));
    }

    void outcomeReapplied(UUID actor, ApprovalStore.InstanceRow instance) {
        record("outcome.reapplied", actor, instance, Map.of());
    }

    void lockOverridden(UUID actor, String object, UUID recordId, UUID request) {
        TenantContext context = contexts.current().orElse(null);
        audit.record(AuditRecord.of("approval.lock.overridden", AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(context == null ? null : context.tenantId()).onObject(object, recordId.toString())
                .with("request", request.toString()));
        meters.counter("platform.approval.lock.overrides").increment();
    }

    // ---- delegations ----

    void delegation(String kind, UUID actor, UUID delegator, UUID delegate, String object, String process,
            String startsOn, String endsOn) {
        TenantContext context = contexts.current().orElse(null);
        AuditRecord record = AuditRecord.of("approval.delegation." + kind, AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(context == null ? null : context.tenantId())
                .with("delegator", delegator.toString()).with("delegate", delegate.toString())
                .with("starts_on", startsOn).with("ends_on", endsOn);
        if (process != null) {
            record = record.with("process", object + "." + process);
        }
        audit.record(record);
    }

    // ---- helpers ----

    private void record(String kind, UUID actor, ApprovalStore.InstanceRow instance, Map<String, String> extra) {
        TenantContext context = contexts.current().orElse(null);
        AuditRecord record = AuditRecord.of("approval." + kind, AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(context == null ? null : context.tenantId())
                .onObject(instance.objectApiName(), instance.recordId().toString())
                .with("process", instance.processApiName()).with("request", instance.id().toString())
                .with("process_version", Integer.toString(instance.processVersionNumber()))
                .with("version", Long.toString(instance.version()));
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            record = record.with(entry.getKey(), entry.getValue());
        }
        audit.record(record);
    }

    /** The event: identifiers and the outcome only; the chain of the workflow run that started the request. */
    private void publish(String type, ApprovalStore.InstanceRow instance, String outcome) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("object", instance.objectApiName());
        facts.put("recordId", instance.recordId().toString());
        facts.put("process", instance.processApiName());
        facts.put("request", instance.id().toString());
        facts.put("version", instance.version());
        if (outcome != null) {
            facts.put("outcome", outcome);
        }
        if (instance.chainRoot() != null) {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("root", instance.chainRoot().toString());
            chain.put("depth", instance.chainDepth());
            chain.put("workflows", List.copyOf(instance.chainWorkflows().stream().map(UUID::toString).toList()));
            facts.put("chain", chain);
        }
        events.publish(new NewEvent(type, json.writeValueAsString(facts)));
    }
}
