package app.platform.integration.internal;

import app.platform.integration.adapter.FileFlowConnector;
import app.platform.integration.internal.CircuitBreaker.Forced;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.DeliveryStore.Delivery;
import app.platform.security.Ability;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platformapi.ApiException;
import app.platformapi.AttemptView;
import app.platformapi.CircuitView;
import app.platformapi.CircuitView.CircuitAction;
import app.platformapi.DeliveryView;
import app.platformapi.ErrorCode;
import app.platformapi.FileFlowView;
import app.platformapi.FlowLimitsRequest;
import app.platformapi.FlowLimitsView;
import app.platformapi.IntegrationHealthView;
import app.platformapi.ReplayView;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an administrator sees and does about the flows of a connection (Sprint 29, ADR-0229, ADR-0231, ADR-0234,
 * ADR-0235): the attempts of a delivery, sending a dead letter again, the state of the circuit and forcing it, the
 * limits on sending, and the health of one or all connections.
 *
 * <p>Every method asks the abilities in one place ({@link IntegrationAccess}) and finds the connection in the
 * caller's own organization, so a name of another organization is "not found". Nothing returned or audited holds a
 * record value, a payload, a header, an address or an identifier of the other system.
 */
@Service
@Transactional
class ReliabilityService {

    static final String FORCE_OPEN = "force-open";
    static final String FORCE_CLOSED = "force-closed";
    static final String AUTOMATIC = "automatic";

    private static final int ATTEMPTS_MAX = 200;

    private final ConnectionStore connections;
    private final DeliveryStore deliveries;
    private final AttemptStore attempts;
    private final FlowGate gate;
    private final DeliveryService deliveryViews;
    private final IntegrationAccess access;
    private final IntegrationProperties.Reliability settings;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ConnectorRegistry registry;
    private final PollStore polls;
    private final FileLedgerStore files;

    ReliabilityService(ConnectionStore connections, DeliveryStore deliveries, AttemptStore attempts, FlowGate gate,
            DeliveryService deliveryViews, IntegrationAccess access, IntegrationProperties properties,
            AuditRecorder audit, MeterRegistry meters, Clock clock, ConnectorRegistry registry, PollStore polls,
            FileLedgerStore files) {
        this.registry = registry;
        this.polls = polls;
        this.files = files;
        this.connections = connections;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.gate = gate;
        this.deliveryViews = deliveryViews;
        this.access = access;
        this.settings = properties.reliability();
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
    }

    // ---- the log ----

    @Transactional(readOnly = true)
    List<AttemptView> attempts(String name, UUID deliveryId) {
        access.require(Ability.INTEGRATIONS_VIEW, Ability.MAPPINGS_VIEW);
        Connection connection = connection(name);
        Delivery delivery = deliveries.find(connection.id(), deliveryId)
                .orElseThrow(() -> ApiException.notFound("This delivery does not exist."));
        return attempts.of(delivery.id(), ATTEMPTS_MAX).stream().map(attempt -> new AttemptView(attempt.generation(),
                attempt.attemptNo(), attempt.startedAt(), attempt.durationMs(), attempt.outcome(), attempt.reason(),
                FlowReason.wordsFor(attempt.reason()), attempt.statusClass(), attempt.httpStatus())).toList();
    }

    // ---- replay ----

    DeliveryView replay(String name, UUID deliveryId) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_REPLAY);
        Connection connection = connection(name);
        Delivery delivery = deliveries.find(connection.id(), deliveryId)
                .orElseThrow(() -> ApiException.notFound("This delivery does not exist."));
        // Two clicks make one: only a delivery that is still set aside moves, and the second finds it moved.
        if (!"OUT".equals(delivery.direction()) || !deliveries.replay(delivery.id())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only a delivery that was set aside can be sent again.");
        }
        meters.counter("platform.integration.replayed", "mode", "single").increment();
        audit.record(AuditRecord.of("integration.delivery.replayed", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(caller.tenantId()).with("integration", connection.id().toString())
                .with("delivery", delivery.id().toString()));
        return deliveryViews.view(deliveries.find(connection.id(), deliveryId).orElseThrow(), true);
    }

    ReplayView replayAll(String name, Integer limit) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_REPLAY);
        Connection connection = connection(name);
        int bound = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        int replayed = 0;
        for (UUID id : deliveries.deadLetterIds(connection.id(), bound)) {
            if (deliveries.replay(id)) {
                replayed++;
            }
        }
        if (replayed > 0) {
            meters.counter("platform.integration.replayed", "mode", "bulk").increment(replayed);
            audit.record(AuditRecord.of("integration.delivery.replayed_bulk", AuditOutcome.SUCCESS)
                    .forUser(caller.userId()).inTenant(caller.tenantId())
                    .with("integration", connection.id().toString()).with("count", Integer.toString(replayed)));
        }
        return new ReplayView(replayed, deliveries.counts(connection.id()).deadLettered());
    }

    // ---- the circuit and the limits ----

    @Transactional(readOnly = true)
    CircuitView circuit(String name) {
        access.require(Ability.INTEGRATIONS_VIEW);
        return circuitView(gate.summary(connection(name).id(), clock.instant()));
    }

    CircuitView act(String name, String action) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_OPERATE);
        Connection connection = connection(name);
        Forced forced = switch (action) {
            case FORCE_OPEN -> Forced.OPEN;
            case FORCE_CLOSED -> Forced.CLOSED;
            case AUTOMATIC -> null;
            default -> throw ApiException.validation(Map.of("action",
                    List.of("Use one of the actions the screen offers.")));
        };
        FlowGate.Summary summary = gate.force(connection.id(), forced, caller.userId(), clock.instant());
        audit.record(AuditRecord.of("integration.circuit.forced", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(caller.tenantId()).with("integration", connection.id().toString())
                .with("to", forced == null ? "automatic" : forced.name().toLowerCase(java.util.Locale.ROOT)));
        return circuitView(summary);
    }

    FlowLimitsView setLimits(String name, FlowLimitsRequest request) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_OPERATE);
        Connection connection = connection(name);
        Map<String, List<String>> problems = new java.util.LinkedHashMap<>();
        if (request.ratePerMinute() != null && request.ratePerMinute() > settings.rate().maxPerMinute()) {
            problems.put("ratePerMinute", List.of("The platform allows at most " + settings.rate().maxPerMinute()
                    + " sends a minute for a connection."));
        }
        if (request.maxConcurrent() != null && request.maxConcurrent() > settings.rate().maxConcurrent()) {
            problems.put("maxConcurrent", List.of("The platform allows at most " + settings.rate().maxConcurrent()
                    + " sends at once for a connection."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        gate.limits(connection.id(), request.ratePerMinute(), request.maxConcurrent());
        audit.record(AuditRecord.of("integration.limits.changed", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(caller.tenantId()).with("integration", connection.id().toString()));
        return limitsView(gate.summary(connection.id(), clock.instant()));
    }

    // ---- health ----

    @Transactional(readOnly = true)
    IntegrationHealthView health(String name) {
        access.require(Ability.INTEGRATIONS_VIEW);
        return health(connection(name));
    }

    @Transactional(readOnly = true)
    List<IntegrationHealthView> healthAll() {
        access.require(Ability.INTEGRATIONS_VIEW);
        return connections.list().stream().map(this::health).toList();
    }

    private IntegrationHealthView health(Connection connection) {
        FlowGate.Summary summary = gate.summary(connection.id(), clock.instant());
        DeliveryStore.Counts counts = deliveries.counts(connection.id());
        return new IntegrationHealthView(connection.name(), connection.definition().label(),
                connection.state().name(), circuitView(summary), limitsView(summary), counts.pending(),
                counts.sending(), counts.waitingRemote(), counts.deadLettered(), counts.done(), counts.oldestOpen(),
                counts.lastDone(), counts.lastDeadLettered(), fileFlow(connection));
    }

    /** How the files of a connection that picks them up stand; null for every other (Sprint 29A, ADR-0242). */
    private FileFlowView fileFlow(Connection connection) {
        if (!(registry.find(connection.connectorType()).orElse(null) instanceof FileFlowConnector connector)
                || !connector.layout(connection.definition().settings()).pulls()) {
            return null;
        }
        PollStore.Poll poll = polls.find(connection.id()).orElse(null);
        FileLedgerStore.Counts counts = files.counts(connection.id());
        return new FileFlowView(poll == null ? null : poll.lastPollAt(), poll == null ? null : poll.lastOutcome(),
                poll == null ? null : poll.lastReason(), poll == null ? null : FlowReason.wordsFor(poll.lastReason()),
                poll == null ? clock.instant() : poll.nextPollAt(), (long) (poll == null ? 0 : poll.filesWaiting()),
                counts.inProgress(), counts.done(), counts.setAside());
    }

    private CircuitView circuitView(FlowGate.Summary summary) {
        boolean operate = access.can(Ability.INTEGRATIONS_OPERATE);
        List<CircuitAction> actions = new ArrayList<>();
        if (operate) {
            if (summary.forced() != Forced.OPEN) {
                actions.add(new CircuitAction(FORCE_OPEN, "Pause sending", "Nothing is sent to this service until "
                        + "you turn sending back to automatic. Changes keep waiting in the queue and none is lost."));
            }
            if (summary.forced() != Forced.CLOSED) {
                actions.add(new CircuitAction(FORCE_CLOSED, "Keep sending", "The platform keeps sending even when "
                        + "the service fails. A change that fails is still tried again after a wait."));
            }
            if (summary.forced() != null) {
                actions.add(new CircuitAction(AUTOMATIC, "Back to automatic", "The platform decides again whether "
                        + "to send. The failures counted so far are forgotten."));
            }
        }
        return new CircuitView(summary.circuit().name(), words(summary), summary.forced() == null ? null
                : summary.forced().name(), summary.consecutiveFailures(), summary.openUntil(), summary.openedAt(),
                summary.lastSuccessAt(), summary.lastFailureAt(), summary.lastFailureReason(),
                FlowReason.wordsFor(summary.lastFailureReason()), actions);
    }

    private static String words(FlowGate.Summary summary) {
        if (summary.forced() == Forced.OPEN) {
            return "An administrator paused sending. Nothing is sent until it is turned back to automatic.";
        }
        if (summary.forced() == Forced.CLOSED) {
            return "An administrator keeps sending on, whatever the service does.";
        }
        return switch (summary.circuit()) {
            case CLOSED -> "Sending normally.";
            case OPEN -> "Sending is paused because the service failed repeatedly. It is tried again after the pause.";
            case HALF_OPEN -> "Sending is being tested with a single change.";
        };
    }

    private FlowLimitsView limitsView(FlowGate.Summary summary) {
        return new FlowLimitsView(summary.ratePerMinute(), summary.maxConcurrent(),
                summary.ratePerMinuteIsDefault(), summary.maxConcurrentIsDefault(), settings.rate().maxPerMinute(),
                settings.rate().maxConcurrent());
    }

    private Connection connection(String name) {
        return connections.find(name).orElseThrow(() -> ApiException.notFound("This connection does not exist."));
    }

    /** The time the caller's view was made; here so a test can pin it. */
    Instant now() {
        return clock.instant();
    }
}
