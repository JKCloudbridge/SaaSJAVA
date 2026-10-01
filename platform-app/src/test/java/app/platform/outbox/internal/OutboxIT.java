package app.platform.outbox.internal;

import static app.platform.outbox.internal.OutboxTestSupport.effects;
import static app.platform.outbox.internal.OutboxTestSupport.expireLease;
import static app.platform.outbox.internal.OutboxTestSupport.makeDue;
import static app.platform.outbox.internal.OutboxTestSupport.of;
import static app.platform.outbox.internal.OutboxTestSupport.publish;
import static app.platform.outbox.internal.OutboxTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.observability.ErrorTracker;
import app.platform.outbox.internal.OutboxStore.ClaimedEvent;
import app.platform.outbox.internal.OutboxTestSupport.ProbeHandler;
import app.platform.outbox.internal.OutboxTestSupport.RecordingReporter;
import app.platform.outbox.internal.OutboxTestSupport.Row;
import app.platform.sharedkernel.events.EventPublisher;
import app.platform.sharedkernel.events.NewEvent;
import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platform.testsupport.tenancy.TenantProbeTables;
import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox on the real database (decision D3, ADR-0016): an event exists only if its change committed, is delivered
 * after the commit, is handled effectively once however often it is delivered and however many instances poll,
 * is retried with a growing delay, becomes a dead letter after the limit, survives a crash of the handling instance,
 * and is handled with the tenant context of the tenant it belongs to.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.max-attempts=4",
    "platform.outbox.backoff-initial=400ms",
    "platform.outbox.backoff-max=1s"})
class OutboxIT {

    private static final int MAX_ATTEMPTS = 4;

    @TestConfiguration
    static class Handlers {

        @Bean
        ProbeHandler probeOne(TenantContexts contexts, JdbcClient jdbc) {
            return new ProbeHandler("test.probe-one", Set.of("probe.one", "probe.multi", "probe.crash"),
                    contexts, jdbc);
        }

        @Bean
        ProbeHandler probeTwo(TenantContexts contexts, JdbcClient jdbc) {
            return new ProbeHandler("test.probe-two", Set.of("probe.multi"), contexts, jdbc);
        }

        @Bean
        RecordingReporter recordingReporter() {
            return new RecordingReporter();
        }
    }

    @Autowired
    private ProbeHandler probeOne;
    @Autowired
    private ProbeHandler probeTwo;
    @Autowired
    private RecordingReporter reporter;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxStore store;
    @Autowired
    private EventMarkers markers;
    @Autowired
    private HandlerRegistry registry;
    @Autowired
    private TenantContexts contexts;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private OutboxProperties properties;
    @Autowired
    private ErrorTracker tracker;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private EventPublisher publisher;

    private TestTenant tenantA;
    private TestTenant tenantB;

    @BeforeAll
    static void createEffectsTable() throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection()) {
            TenantProbeTables.standard(owner, OutboxTestSupport.EFFECTS_TABLE);
        }
    }

    @AfterAll
    static void dropEffectsTable() throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection()) {
            TenantProbeTables.drop(owner, OutboxTestSupport.EFFECTS_TABLE);
        }
    }

    @BeforeEach
    void freshTenantsAndHandlers() {
        OutboxTestSupport.settleEverythingPending();
        tenantA = TenantFixtures.createActiveTenant();
        tenantB = TenantFixtures.createActiveTenant();
        probeOne.reset();
        probeTwo.reset();
        reporter.reports.clear();
    }

    // ---- publishing ----

    @Test
    void anEventCanOnlyBePublishedInsideATransactionAndInsideATenantContext() {
        NewEvent event = new NewEvent("probe.one", "{}");

        assertThatThrownBy(() -> contexts.run(of(tenantA), () -> publisher.publish(event)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> publisher.publish(event)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("tenant context");
    }

    @Test
    void anEventWrittenInATransactionThatRollsBackNeverExistsAndIsNeverDelivered() {
        String marker = marker();

        assertThatThrownBy(() -> contexts.run(of(tenantA), () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    publisher.publish(new NewEvent("probe.one", "{\"marker\":\"" + marker + "\"}"));
                    throw new IllegalStateException("the change failed after the event was written");
                }))).isInstanceOf(IllegalStateException.class);
        drain(() -> false, 15);

        assertThat(row(marker)).isEmpty();
        assertThat(probeOne.invocations()).isZero();
    }

    @Test
    void aCommittedEventIsNotHandledBeforeTheRelayPolls() {
        String marker = marker();

        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);

        assertThat(row(marker)).hasValueSatisfying(row -> assertThat(row.status()).isEqualTo("PENDING"));
        assertThat(probeOne.invocations()).isZero();
    }

    // ---- delivery ----

    @Test
    void aCommittedEventIsDeliveredAfterTheCommitAndMarkedDelivered() {
        String marker = marker();
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);

        drain(() -> row(marker).orElseThrow().status().equals("DELIVERED"), 100);

        Row delivered = row(marker).orElseThrow();
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(delivered.attempts()).isEqualTo(1);
        assertThat(delivered.deliveredAt()).isNotNull();
        assertThat(delivered.errorType()).isNull();
        assertThat(probeOne.invocationsFor(delivered.id())).isEqualTo(1);
        assertThat(effects("test.probe-one", delivered.id())).isEqualTo(1);
    }

    @Test
    void theHandlerRunsWithTheTenantContextOfTheEventAndTheDatabaseOnlyShowsThatTenant() {
        String markerA = marker();
        String markerB = marker();
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        publish(contexts, transactions, publisher, tenantA, new TenantContext(tenantA.id(), user, membership),
                "probe.one", markerA);
        publish(contexts, transactions, publisher, tenantB, of(tenantB), "probe.one", markerB);

        drain(() -> row(markerA).orElseThrow().status().equals("DELIVERED")
                && row(markerB).orElseThrow().status().equals("DELIVERED"), 100);

        Row a = row(markerA).orElseThrow();
        Row b = row(markerB).orElseThrow();
        var seenA = probeOne.seen.stream().filter(s -> s.event().eventId().equals(a.id())).findFirst().orElseThrow();
        var seenB = probeOne.seen.stream().filter(s -> s.event().eventId().equals(b.id())).findFirst().orElseThrow();
        assertThat(seenA.event().tenantId()).isEqualTo(tenantA.id());
        assertThat(seenA.context()).contains(new TenantContext(tenantA.id(), user, membership));
        assertThat(seenA.event().user()).contains(user);
        assertThat(seenA.event().membership()).contains(membership);
        assertThat(seenA.databaseTenant()).isEqualTo(tenantA.id().toString());
        assertThat(seenA.tenantsVisibleInOutbox())
                .as("row level security in the handler").containsOnly(tenantA.id().value());
        assertThat(seenB.context()).contains(of(tenantB));
        assertThat(seenB.event().user()).isEmpty();
        assertThat(seenB.databaseTenant()).isEqualTo(tenantB.id().toString());
        assertThat(seenB.tenantsVisibleInOutbox()).containsOnly(tenantB.id().value());
        assertThat(contexts.current()).as("nothing is left on the polling thread").isEmpty();
    }

    @Test
    void anEventNobodyListensToIsDelivered() {
        String marker = marker();
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.nobody", marker);

        drain(() -> row(marker).orElseThrow().status().equals("DELIVERED"), 100);

        assertThat(row(marker).orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    void aDeliveredEventIsNotHandledAgain() {
        String marker = marker();
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);
        drain(() -> row(marker).orElseThrow().status().equals("DELIVERED"), 100);
        long handled = probeOne.invocations();

        drain(() -> false, 5);

        assertThat(probeOne.invocations()).isEqualTo(handled);
    }

    // ---- retry with backoff, dead letters ----

    @Test
    void aFailingEventIsRetriedAfterADelayThatGrowsAndThenSucceedsWithItsChangesAppliedOnce() {
        String marker = marker();
        AtomicInteger calls = new AtomicInteger();
        probeOne.afterEffect = event -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary failure");
            }
        };
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);

        drain(() -> calls.get() >= 1, 20);

        Row afterFirst = row(marker).orElseThrow();
        assertThat(afterFirst.status()).isEqualTo("PENDING");
        assertThat(afterFirst.attempts()).isEqualTo(1);
        assertThat(afterFirst.errorType()).isEqualTo("IllegalStateException");
        assertThat(afterFirst.dueNow()).as("not due again at once: the retry waits").isFalse();
        assertThat(effects("test.probe-one", afterFirst.id())).as("the failed attempt's changes were rolled back")
                .isZero();
        long before = calls.get();
        drain(() -> false, 3);
        assertThat(calls.get()).as("polling meanwhile does not hand it over early").isEqualTo(before);

        drain(() -> row(marker).orElseThrow().status().equals("DELIVERED"), 400);

        Row done = row(marker).orElseThrow();
        assertThat(done.attempts()).isEqualTo(3);
        assertThat(done.errorType()).isNull();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(effects("test.probe-one", done.id())).as("applied exactly once").isEqualTo(1);
        assertThat(probeOne.seen.stream().map(s -> s.event().attempt())).containsExactly(3);
    }

    @Test
    void anEventThatKeepsFailingBecomesADeadLetterAfterTheLimitAndStaysThere() {
        String marker = marker();
        probeOne.afterEffect = event -> {
            throw new IllegalStateException("SECRET user-a@example.test");
        };
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);

        try (LogCapture logs = LogCapture.of(OutboxRelay.class.getName())) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                makeDue(row(marker).orElseThrow().id());
                int expected = attempt;
                drain(() -> row(marker).orElseThrow().attempts() >= expected, 40);
            }

            Row dead = row(marker).orElseThrow();
            assertThat(dead.status()).isEqualTo("DEAD");
            assertThat(dead.attempts()).isEqualTo(MAX_ATTEMPTS);
            assertThat(dead.errorType()).isEqualTo("IllegalStateException").doesNotContain("SECRET");
            assertThat(effects("test.probe-one", dead.id())).as("no failed attempt left a change").isZero();
            assertThat(reporter.reports).hasSize(1);
            assertThat(reporter.reports.get(0).tenantId()).contains(tenantA.id().toString());
            assertThat(reporter.reports.get(0).path()).isEqualTo("probe.one");
            assertThat(logs.events()).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("SECRET").doesNotContain("user-a@example.test"));

            long handled = probeOne.invocations();
            makeDue(dead.id());
            drain(() -> false, 5);
            assertThat(probeOne.invocations()).as("a dead letter is not retried").isEqualTo(handled);
            assertThat(row(marker).orElseThrow().status()).isEqualTo("DEAD");
        }
        assertThat(meters.get("platform.outbox.events").tag("result", "dead").counter().count()).isPositive();
    }

    @Test
    void eachRetryWaitsLongerThanTheOneBefore() {
        String marker = marker();
        probeOne.afterEffect = event -> {
            throw new IllegalStateException("again");
        };
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);

        List<Duration> delays = new java.util.ArrayList<>();
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            int expected = attempt;
            drain(() -> row(marker).orElseThrow().attempts() >= expected, 40);
            delays.add(Duration.ofMillis(OutboxTestSupport.count(
                    "select (extract(epoch from (next_attempt_at - updated_at)) * 1000)::bigint "
                            + "from outbox_event where payload->>'marker' = ?", marker)));
            makeDue(row(marker).orElseThrow().id());
        }

        assertThat(delays).hasSize(MAX_ATTEMPTS - 1);
        // 400 ms doubling with 20 percent jitter, capped at 1 s: about 400, 800, 1000.
        assertThat(delays.get(0)).isBetween(Duration.ofMillis(300), Duration.ofMillis(500));
        assertThat(delays.get(1)).isBetween(Duration.ofMillis(600), Duration.ofMillis(1000));
        assertThat(delays.get(2)).isBetween(Duration.ofMillis(700), Duration.ofMillis(1000));
    }

    // ---- several handlers ----

    @Test
    void aHandlerThatAlreadySucceededIsNotRunAgainWhenAnotherHandlerOfTheSameEventFails() {
        String marker = marker();
        AtomicInteger twoCalls = new AtomicInteger();
        probeTwo.afterEffect = event -> {
            if (twoCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("one handler fails once");
            }
        };
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.multi", marker);

        drain(() -> twoCalls.get() >= 1, 20);
        makeDue(row(marker).orElseThrow().id());
        drain(() -> row(marker).orElseThrow().status().equals("DELIVERED"), 100);

        Row done = row(marker).orElseThrow();
        assertThat(done.attempts()).isEqualTo(2);
        assertThat(probeOne.invocationsFor(done.id())).as("the handler that succeeded the first time").isEqualTo(1);
        assertThat(probeTwo.invocationsFor(done.id())).isEqualTo(1);
        assertThat(effects("test.probe-one", done.id())).isEqualTo(1);
        assertThat(effects("test.probe-two", done.id())).isEqualTo(1);
    }

    // ---- crashes ----

    @Test
    void anEventClaimedByAnInstanceThatDiesIsClaimedAgainWhenTheLeaseEnds() throws Exception {
        String marker = marker();
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);
        List<ClaimedEvent> claimed = claim(Duration.ofMillis(500));
        assertThat(claimed).anyMatch(event -> event.id().equals(row(marker).orElseThrow().id()));

        // The claiming instance never reports back.
        drain(() -> false, 3);
        assertThat(probeOne.invocations()).as("leased events are invisible to other instances").isZero();
        assertThat(row(marker).orElseThrow().leased()).isTrue();

        Thread.sleep(600);
        drain(() -> row(marker).orElseThrow().status().equals("DELIVERED"), 100);

        Row done = row(marker).orElseThrow();
        assertThat(done.attempts()).as("the crashed claim counted as an attempt").isEqualTo(2);
        assertThat(probeOne.invocationsFor(done.id())).isEqualTo(1);
        assertThat(probeOne.seen.get(0).event().attempt()).isEqualTo(2);
        assertThat(effects("test.probe-one", done.id())).isEqualTo(1);
    }

    @Test
    void anInstanceThatDiesAfterTheHandlerCommittedButBeforeReportingDoesNotApplyTheEventTwice() {
        String marker = marker();
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);
        Row published = row(marker).orElseThrow();
        ClaimedEvent claim = claim(Duration.ofMinutes(5)).stream().filter(c -> c.id().equals(published.id()))
                .findFirst().orElseThrow();

        // The handler's transaction commits: the effect and the idempotency marker. Then the instance dies, before
        // it can mark the event delivered.
        contexts.run(new TenantContext(tenantA.id(), null, null), () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    assertThat(markers.firstTime("test.probe-one", claim.id())).isTrue();
                    probeOne.handle(new app.platform.sharedkernel.events.EventEnvelope(claim.id(), tenantA.id(), null,
                            null, claim.type(), claim.payload(), claim.occurredAt(), claim.attempt()));
                }));
        assertThat(effects("test.probe-one", claim.id())).isEqualTo(1);
        assertThat(row(marker).orElseThrow().status()).isEqualTo("PENDING");
        long handledBefore = probeOne.invocations();

        expireLease(claim.id());
        drain(() -> row(marker).orElseThrow().status().equals("DELIVERED"), 100);

        assertThat(probeOne.invocations()).as("the marker made the second delivery a no-op").isEqualTo(handledBefore);
        assertThat(effects("test.probe-one", claim.id())).isEqualTo(1);
        assertThat(row(marker).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void anEventThatKeepsKillingTheInstanceIsDeadLetteredWithoutBeingHandledAgain() throws Exception {
        String marker = marker();
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.crash", marker);
        UUID id = row(marker).orElseThrow().id();

        // Every claim dies before it reports: the attempt is counted at the claim, not at the outcome.
        for (int crash = 1; crash <= MAX_ATTEMPTS; crash++) {
            assertThat(claim(Duration.ofMillis(100))).anyMatch(event -> event.id().equals(id));
            Thread.sleep(150);
        }
        assertThat(row(marker).orElseThrow().attempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(probeOne.invocations()).isZero();

        drain(() -> row(marker).orElseThrow().status().equals("DEAD"), 50);

        Row dead = row(marker).orElseThrow();
        assertThat(dead.status()).isEqualTo("DEAD");
        assertThat(dead.errorType()).isEqualTo("AttemptsExhausted");
        assertThat(probeOne.invocations()).as("it was never handed to the handler again").isZero();
    }

    @Test
    void anInstanceThatLostItsLeaseCanNotOverwriteTheOutcomeOfTheOneThatTookOver() {
        String marker = marker();
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.nobody", marker);
        UUID id = row(marker).orElseThrow().id();
        ClaimedEvent slow = claim(Duration.ofMinutes(5)).stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow();
        expireLease(id);
        drain(() -> row(marker).orElseThrow().status().equals("DELIVERED"), 100);

        boolean overwrote = contexts.callAsSystem(SystemScope.OUTBOX_RELAY, () -> new TransactionTemplate(transactions)
                .execute(status -> store.scheduleRetry(slow, Duration.ofSeconds(1), "TooSlow")));

        assertThat(overwrote).isFalse();
        Row now = row(marker).orElseThrow();
        assertThat(now.status()).isEqualTo("DELIVERED");
        assertThat(now.errorType()).isNull();
    }

    // ---- several instances polling at once ----

    @Test
    void severalInstancesPollingTogetherHandleEveryEventExactlyOnce() throws Exception {
        int perTenant = 60;
        List<TestTenant> tenants = List.of(tenantA, tenantB, TenantFixtures.createActiveTenant());
        List<String> testMarkers = new java.util.concurrent.CopyOnWriteArrayList<>();
        ExecutorService publishers = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> published = new java.util.ArrayList<>();
            for (TestTenant tenant : tenants) {
                for (int i = 0; i < perTenant; i++) {
                    String marker = marker();
                    testMarkers.add(marker);
                    published.add(publishers.submit(() -> publish(contexts, transactions, publisher, tenant,
                            of(tenant), "probe.one", marker)));
                }
            }
            for (Future<?> future : published) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            publishers.shutdownNow();
        }
        ConcurrentHashMap<UUID, AtomicInteger> perEvent = new ConcurrentHashMap<>();
        probeOne.afterEffect = event -> perEvent.computeIfAbsent(event.eventId(), id -> new AtomicInteger())
                .incrementAndGet();

        int instances = 5;
        List<OutboxRelay> relays = new java.util.ArrayList<>(List.of(relay));
        for (int i = 1; i < instances; i++) {
            relays.add(new OutboxRelay(store, markers, registry, contexts, transactions, properties, tracker,
                    meters));
        }
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pollers = Executors.newFixedThreadPool(instances);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            List<Future<?>> loops = new java.util.ArrayList<>();
            for (OutboxRelay instance : relays) {
                loops.add(pollers.submit(() -> {
                    go.await();
                    while (System.nanoTime() < deadline && !allDelivered(testMarkers)) {
                        instance.pollOnce();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> loop : loops) {
                loop.get(100, TimeUnit.SECONDS);
            }
        } finally {
            pollers.shutdownNow();
        }

        assertThat(allDelivered(testMarkers)).isTrue();
        for (String marker : testMarkers) {
            Row done = row(marker).orElseThrow();
            assertThat(done.attempts()).as(marker).isEqualTo(1);
            assertThat(perEvent.get(done.id())).as("handled once: " + marker).isNotNull().hasValue(1);
            assertThat(effects("test.probe-one", done.id())).as("applied once: " + marker).isEqualTo(1);
        }
        assertThat(perEvent).hasSize(tenants.size() * perTenant);
        assertThat(probeOne.seen).allSatisfy(seen -> assertThat(seen.databaseTenant())
                .isEqualTo(seen.event().tenantId().toString()));
    }

    // ---- retention ----

    @Test
    void expiredDeliveredEventsAndMarkersAreRemovedAndRecentOnesAreKept() {
        String old = marker();
        String recent = marker();
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", old);
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", recent);
        drain(() -> row(old).orElseThrow().status().equals("DELIVERED")
                && row(recent).orElseThrow().status().equals("DELIVERED"), 100);
        UUID oldId = row(old).orElseThrow().id();
        OutboxTestSupport.owner("update outbox_event set delivered_at = now() - interval '8 days', "
                + "version = version + 1, updated_by = ? where id = ?", new UUID(0L, 0L), oldId);
        OutboxTestSupport.ownerWithoutTriggers(
                "update processed_event set created_at = now() - interval '31 days' where event_id = ?", oldId);

        relay.purge();

        assertThat(row(old)).as("a delivered event older than the retention").isEmpty();
        assertThat(OutboxTestSupport.count("select count(*) from processed_event where event_id = ?", oldId)).isZero();
        assertThat(row(recent)).isPresent();
        assertThat(OutboxTestSupport.count("select count(*) from processed_event where event_id = ?",
                row(recent).orElseThrow().id())).isEqualTo(1);
    }

    @Test
    void aDeadLetterIsNeverPurged() {
        String marker = marker();
        probeOne.afterEffect = event -> {
            throw new IllegalStateException("fails");
        };
        publish(contexts, transactions, publisher, tenantA, of(tenantA), "probe.one", marker);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            makeDue(row(marker).orElseThrow().id());
            int expected = attempt;
            drain(() -> row(marker).orElseThrow().attempts() >= expected, 40);
        }
        OutboxTestSupport.ownerWithoutTriggers(
                "update outbox_event set created_at = now() - interval '60 days' where payload->>'marker' = ?", marker);

        relay.purge();

        assertThat(row(marker).orElseThrow().status()).isEqualTo("DEAD");
    }

    // ---- helpers ----

    private List<ClaimedEvent> claim(Duration lease) {
        return contexts.callAsSystem(SystemScope.OUTBOX_RELAY, () -> new TransactionTemplate(transactions)
                .execute(status -> store.claim(100, lease)));
    }

    private static boolean allDelivered(List<String> markers) {
        return OutboxTestSupport.count("select count(*) from outbox_event where payload->>'marker' = any(?::text[]) "
                + "and status <> 'DELIVERED'", (Object) markers.toArray(new String[0])) == 0;
    }

    /** Polls until the condition holds or the number of polls is used up; a short pause between polls. */
    private void drain(BooleanSupplier until, int polls) {
        for (int i = 0; i < polls && !until.getAsBoolean(); i++) {
            relay.pollOnce();
            if (until.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static String marker() {
        return UUID.randomUUID().toString();
    }
}
