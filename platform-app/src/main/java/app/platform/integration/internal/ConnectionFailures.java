package app.platform.integration.internal;

import app.platform.integration.internal.ConnectionMachine.Event;
import app.platform.integration.internal.ConnectionMachine.State;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stops using a file transfer connection when the other side showed it cannot be trusted or will not accept the
 * credential (Sprint 29A, ADR-0237, ADR-0239). The connection moves from on to <em>on, but failing</em>, which is the
 * move the connection's life already has for "a use of the connection failed" and which no sender or poller passes: its
 * queue waits and nothing is lost, and only a test that passes, or switching it off, brings it back. The reason is
 * shown as the reason of the last test.
 *
 * <p>Only a wrong key and a refused credential do this. Every other failure is retried, counted by the breaker and, in
 * the end, set aside, because waiting can mend it; these two cannot be mended by waiting, and trying again with a
 * credential the server refused only invites it to lock the account.
 */
@Component
class ConnectionFailures {

    private final ConnectionStore connections;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;

    ConnectionFailures(ConnectionStore connections, AuditRecorder audit, TenantContexts contexts, MeterRegistry meters,
            PlatformTransactionManager transactions) {
        this.connections = connections;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Whether this reason is one that stops a connection. */
    static boolean stops(TestReason reason) {
        return reason == TestReason.HOST_KEY_MISMATCH || reason == TestReason.AUTHENTICATION_REJECTED;
    }

    /** Moves the connection to failing if it is on and working. In the organization's own tenant context. */
    void stopUsing(UUID connectionId, TestReason reason) {
        transaction.executeWithoutResult(status -> {
            Connection fresh = connections.findById(connectionId).orElse(null);
            if (fresh == null || fresh.state() != State.ENABLED) {
                return;
            }
            State next = ConnectionMachine.next(fresh.state(), Event.REPORT_FAILURE).orElse(fresh.state());
            UUID actor = ActorId.SYSTEM.value();
            if (!connections.recordTest(fresh.id(), fresh.definition().fingerprint(), next,
                    fresh.testedFingerprint(), false, reason.key(), actor)) {
                return;
            }
            audit.record(AuditRecord.of("integration.stopped", AuditOutcome.FAILURE).forUser(actor)
                    .inTenant(contexts.require().tenantId()).with("integration", fresh.id().toString())
                    .with("connector", fresh.connectorType()).with("reason", reason.key())
                    .with("state", next.name()));
            meters.counter("platform.integration.stopped", "reason", reason.key()).increment();
        });
    }
}
