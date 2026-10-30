package app.platform.integration.internal;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.ProbeRequest;
import app.platform.integration.internal.ConnectionMachine.Event;
import app.platform.integration.internal.ConnectionMachine.State;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.EgressClient.Header;
import app.platform.integration.internal.EgressClient.Outcome;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.security.Ability;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.IntegrationTestView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tests a connection (ADR-0210): one bounded call to the external system, made outside every database transaction, in
 * the organization's own context, and answered with a stable reason.
 *
 * <p>Three steps. First a short transaction decides who may ask, loads the definition and takes the claim that rate
 * limits the connection (one test per connection per interval, by the database clock, so two instances cannot both
 * test it). Then the call, with no transaction and no connection held, under one hard deadline. Last a short
 * transaction stores the result against the fingerprint that was tested: if the definition changed meanwhile, the
 * result is dropped and the answer says so, so a test can never vouch for a definition it did not test.
 *
 * <p>This is the one deliberate call made inside an administrator's request: it is explicit, rare, rate limited and
 * bounded, and nothing a person's save, approval or other record work does ever waits for an external system. The
 * queue that moves such calls off the request arrives with Sprint 29.
 */
@Service
class ConnectionTesting {

    private final PlatformTransactionManager transactions;
    private final ConnectionStore store;
    private final ConnectorRegistry registry;
    private final IntegrationAccess access;
    private final IntegrationViews views;
    private final EgressClient egress;
    private final EndpointRules.Policy policy;
    private final IntegrationProperties properties;
    private final CredentialHeaders credentials;
    private final AuditRecorder audit;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final Semaphore slots;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    ConnectionTesting(PlatformTransactionManager transactions, ConnectionStore store, ConnectorRegistry registry,
            IntegrationAccess access, IntegrationViews views, EgressClient egress, EndpointRules.Policy policy,
            IntegrationProperties properties, CredentialHeaders credentials, AuditRecorder audit,
            ErrorTracker tracker, MeterRegistry meters) {
        this.transactions = transactions;
        this.store = store;
        this.registry = registry;
        this.access = access;
        this.views = views;
        this.egress = egress;
        this.policy = policy;
        this.properties = properties;
        this.credentials = credentials;
        this.audit = audit;
        this.tracker = tracker;
        this.meters = meters;
        this.slots = new Semaphore(properties.test().maxConcurrent());
    }

    @PreDestroy
    void close() {
        workers.shutdownNow();
    }

    /** What the first step hands to the call: facts only, and the connection as it was tested. */
    private record Claimed(TenantContext caller, Connection connection) {
    }

    IntegrationTestView test(String name) {
        if (!slots.tryAcquire()) {
            throw ApiException.rateLimited(2);
        }
        try {
            Claimed claimed = new TransactionTemplate(transactions).execute(status -> claim(name));
            long started = System.nanoTime();
            TestReason reason = call(claimed);
            Timer.builder("platform.integration.test.duration").tag("result", reason.passed() ? "passed" : "failed")
                    .register(meters).record(Duration.ofNanos(System.nanoTime() - started));
            return new TransactionTemplate(transactions).execute(status -> finish(claimed, reason));
        } finally {
            slots.release();
        }
    }

    private Claimed claim(String name) {
        TenantContext caller = access.require(Ability.INTEGRATIONS_VIEW, Ability.INTEGRATIONS_TEST);
        Connection connection = store.find(name)
                .orElseThrow(() -> ApiException.notFound("This connection does not exist."));
        if (registry.find(connection.connectorType()).isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "This kind of connection is no longer available.");
        }
        Duration interval = properties.test().minInterval();
        if (!store.claimTest(connection.id(), interval, caller.userId())) {
            throw ApiException.rateLimited(Math.max(1, interval.toSeconds()));
        }
        return new Claimed(caller, connection);
    }

    /** The call itself, off the request thread so that one deadline covers even a name lookup that never returns. */
    private TestReason call(Claimed claimed) {
        TenantId tenant = claimed.caller().tenantId();
        Future<TestReason> work = workers.submit(() -> probe(claimed.connection(), tenant));
        Duration limit = properties.test().timeout();
        try {
            return work.get(limit.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException tooSlow) {
            work.cancel(true);
            return TestReason.TIMED_OUT;
        } catch (InterruptedException interrupted) {
            work.cancel(true);
            Thread.currentThread().interrupt();
            return TestReason.UNEXPECTED;
        } catch (ExecutionException failed) {
            tracker.track(ErrorReport.current(new IntegrationFailure(failed.getCause()), "POST",
                    "/api/v1/integrations/test"));
            return TestReason.UNEXPECTED;
        }
    }

    /** A failure of the framework or of an adapter while testing. Its message is fixed; the cause is not repeated. */
    static final class IntegrationFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        IntegrationFailure(Throwable cause) {
            super("A connection test failed unexpectedly: " + (cause == null ? "unknown" : cause.getClass().getName()));
        }
    }

    private TestReason probe(Connection connection, TenantId tenant) {
        Connector connector = registry.find(connection.connectorType()).orElseThrow();
        var definition = connection.definition();
        ProbeRequest probe = connector.probe(definition.settings());
        if (!EndpointRules.validPath(probe.path())) {
            return TestReason.UNEXPECTED;
        }
        EndpointRules.Result address = EndpointRules.parse(definition.baseUrl(), policy);
        if (!address.ok()) {
            return TestReason.ADDRESS_NOT_ALLOWED;
        }
        AuthKind auth = AuthKind.fromKey(definition.authKind()).orElse(AuthKind.NONE);
        CredentialHeaders.Resolved credential = credentials.resolve(tenant, definition);
        if (credential.failure() != null) {
            return credential.failure();
        }
        Header header = credential.header();
        Duration timeout = probe.timeout().compareTo(properties.test().timeout()) < 0 ? probe.timeout()
                : properties.test().timeout();
        Outcome outcome = egress.send(new EgressClient.Request(address.endpoint(),
                probe.method() == ProbeRequest.Method.HEAD, probe.path(), header, timeout));
        if (outcome.failure() != null) {
            return outcome.failure();
        }
        int status = outcome.status();
        if (status >= 300 && status < 400) {
            return TestReason.REDIRECT_REFUSED;
        }
        if (connector.accepts(definition.settings(), status)) {
            return TestReason.OK;
        }
        return auth != AuthKind.NONE && (status == 401 || status == 403) ? TestReason.AUTHENTICATION_REJECTED
                : TestReason.UNEXPECTED_STATUS;
    }

    private IntegrationTestView finish(Claimed claimed, TestReason reason) {
        Connection tested = claimed.connection();
        Connection fresh = store.findById(tested.id())
                .orElseThrow(() -> ApiException.notFound("This connection does not exist."));
        TestReason recorded = reason;
        UUID actor = claimed.caller().userId();
        Event event = reason.passed() ? Event.TEST_PASSED : Event.TEST_FAILED;
        State next = ConnectionMachine.next(fresh.state(), event).orElse(fresh.state());
        String testedFingerprint = reason.passed() ? tested.definition().fingerprint()
                : (next == State.FAILING ? fresh.testedFingerprint() : null);
        boolean stored = store.recordTest(tested.id(), tested.definition().fingerprint(), next, testedFingerprint,
                reason.passed(), reason.key(), actor);
        if (!stored) {
            recorded = TestReason.CHANGED_DURING_TEST;
        }
        Connection after = store.findById(tested.id()).orElse(fresh);
        audit.record(AuditRecord.of("integration.tested", stored && reason.passed() ? AuditOutcome.SUCCESS
                : AuditOutcome.FAILURE).forUser(actor).inTenant(claimed.caller().tenantId())
                .with("integration", tested.id().toString()).with("connector", tested.connectorType())
                .with("result", recorded.passed() ? "passed" : "failed").with("reason", recorded.key())
                .with("state", after.state().name()));
        meters.counter("platform.integration.test", "result", recorded.passed() ? "passed" : "failed", "reason",
                recorded.key()).increment();
        return new IntegrationTestView(recorded.passed() ? "PASSED" : "FAILED", recorded.key(), recorded.words(),
                views.view(after));
    }
}
