package app.platform.workflow.internal;

import app.platform.metadata.CompiledWorkflow;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.WorkflowDefinition;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the event of a change to a record into executions (ADR-0133, ADR-0137): for every active workflow of the
 * organization whose trigger matches (the object, the kind of change and, for a field-change trigger, the names of the
 * changed fields) one execution is queued, once per workflow version and event.
 *
 * <p><strong>Cheap by construction.</strong> Every record write announces an event, so this handler runs for every
 * write of every organization. It asks the runtime contract of the metadata module for the active workflows of the
 * object; that is served from the snapshot of the organization (one read of the metadata version, no more), and an
 * object with no workflow ends the handling there. Nothing is added to the request that wrote the record: this runs
 * after its commit, in the outbox relay.
 *
 * <p><strong>Once.</strong> The outbox runs the handler once per event in a transaction that also holds its idempotency
 * marker (ADR-0016); the unique key of the execution is the second line, so a handler renamed or a marker lost still
 * makes no second execution. A payload this handler cannot read is counted and skipped, never retried: the same payload
 * would fail the same way.
 *
 * <p>The event carries the names of the changed fields and never their values; the record is read when the execution
 * runs (ADR-0137).
 */
@Component
class WorkflowEventHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowEventHandler.class);
    private static final Set<String> TYPES = Set.of("record.created", "record.updated", "record.deleted");

    private final MetadataResolver resolver;
    private final ExecutionStore executions;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Timer delay;
    private final Spans spans;
    private final JsonMapper json = JsonMapper.builder().build();

    WorkflowEventHandler(MetadataResolver resolver, ExecutionStore executions, MeterRegistry meters, Clock clock,
            Spans spans) {
        this.spans = spans;
        this.resolver = resolver;
        this.executions = executions;
        this.meters = meters;
        this.clock = clock;
        this.delay = Timer.builder("platform.workflow.event.delay").publishPercentileHistogram().register(meters);
    }

    @Override
    public String consumerName() {
        return "workflow.record-events";
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public void handle(EventEnvelope event) {
        spans.run("workflow.event", Map.of("workflow.event.id", event.eventId().toString(), "workflow.event.type",
                event.type()), () -> process(event));
    }

    private void process(EventEnvelope event) {
        long started = System.nanoTime();
        String result = decide(event);
        meters.counter("platform.workflow.events", "result", result).increment();
        Timer.builder("platform.workflow.event.handling").tag("result", result).publishPercentileHistogram()
                .register(meters).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    /** Handles the event and says how it ended: malformed, no_workflow, no_match or queued. */
    private String decide(EventEnvelope event) {
        JsonNode payload;
        String object;
        UUID record;
        try {
            payload = json.readTree(event.payload());
            object = payload.path("object").asString("");
            record = UUID.fromString(payload.path("recordId").asString(""));
        } catch (RuntimeException unreadable) {
            LOG.warn("A record event the workflows cannot read was skipped: type {}", event.type());
            return "malformed";
        }
        if (object.isEmpty()) {
            return "malformed";
        }
        List<CompiledWorkflow> candidates = resolver.workflows(object);
        if (candidates.isEmpty()) {
            return "no_workflow";
        }
        Long version = payload.path("version").isNumber() ? payload.path("version").longValue() : null;
        Set<String> fields = new HashSet<>();
        payload.path("fields").forEach(name -> fields.add(name.asString("")));
        int queued = 0;
        for (CompiledWorkflow compiled : candidates) {
            WorkflowDefinition definition = compiled.definition();
            if (!matches(definition.trigger(), event.type(), fields)) {
                continue;
            }
            boolean made = executions.insert(new ExecutionStore.NewExecution(definition.id(),
                    definition.versionId(), definition.apiName(), definition.definitionVersion(), object, record,
                    version, definition.trigger().kind().name(), event.eventId(), null,
                    "event:" + event.eventId() + ":" + definition.versionId()));
            if (made) {
                queued++;
                meters.counter("platform.workflow.executions", "result", "queued").increment();
            }
        }
        delay.record(Duration.between(event.occurredAt(), clock.instant()));
        return queued > 0 ? "queued" : "no_match";
    }

    /** Whether the trigger starts on this kind of event with these changed fields. */
    static boolean matches(WorkflowDefinition.Trigger trigger, String eventType, Set<String> changedFields) {
        return switch (eventType) {
            case "record.created" -> trigger.kind() == WorkflowDefinition.Kind.RECORD_CREATED;
            case "record.updated" -> trigger.kind() == WorkflowDefinition.Kind.RECORD_UPDATED
                    || trigger.kind() == WorkflowDefinition.Kind.FIELD_CHANGED
                            && trigger.fields().stream().anyMatch(changedFields::contains);
            case "record.deleted" -> trigger.kind() == WorkflowDefinition.Kind.RECORD_DELETED;
            default -> false;
        };
    }
}
