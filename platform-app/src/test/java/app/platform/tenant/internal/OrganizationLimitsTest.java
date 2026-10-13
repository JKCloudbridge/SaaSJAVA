package app.platform.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.TenantId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

/**
 * The limit on the requests of one organization that are in progress (Sprint 16, ADR-0108), with no database: the turns
 * are taken and given back, a request over the limit waits briefly or is refused at once, only a few may wait, and
 * another organization is not touched.
 */
class OrganizationLimitsTest {

    private static final TenantId ORGANIZATION = new TenantId(UUID.randomUUID());
    private static final TenantId OTHER = new TenantId(UUID.randomUUID());

    private final TenantContexts contexts = new TenantContexts();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final AtomicInteger refusals = new AtomicInteger();

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    private static OrganizationLimitProperties settings(int concurrent, int waiting, Duration wait,
            Map<String, OrganizationLimitProperties.Custom> overrides) {
        return new OrganizationLimitProperties(true, concurrent, waiting, wait, Duration.ofSeconds(5),
                Duration.ofSeconds(30), overrides);
    }

    private Filter filter(OrganizationLimitProperties settings) {
        return new DefaultOrganizationLimits(settings, contexts, (request, response, handler, error) -> {
            refusals.incrementAndGet();
            response.setStatus(429);
            return new ModelAndView();
        }, meters).requestFilter();
    }

    /** A request of the organization that holds its turn until released; returns the response status when done. */
    private Future<Integer> request(Filter filter, TenantId tenant, CountDownLatch inside, CountDownLatch release) {
        return threads.submit(() -> {
            MockHttpServletResponse response = new MockHttpServletResponse();
            try (TenantContexts.Scope _ = contexts.open(new TenantContext(tenant, UUID.randomUUID(),
                    UUID.randomUUID()))) {
                filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/objects/Thing__c/records"), response,
                        new MockFilterChain() {
                            @Override
                            public void doFilter(jakarta.servlet.ServletRequest request,
                                    jakarta.servlet.ServletResponse answer) {
                                inside.countDown();
                                try {
                                    release.await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        });
            }
            return response.getStatus();
        });
    }

    private int immediate(Filter filter, TenantId tenant) throws Exception {
        return request(filter, tenant, new CountDownLatch(1), new CountDownLatch(0)).get(5, TimeUnit.SECONDS);
    }

    @Test
    void aRequestOverTheLimitIsRefusedAtOnceWhenNoOneMayWait() throws Exception {
        Filter filter = filter(settings(2, 0, Duration.ofSeconds(2), Map.of()));
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> first = request(filter, ORGANIZATION, inside, release);
        Future<Integer> second = request(filter, ORGANIZATION, inside, release);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        int third = immediate(filter, ORGANIZATION);
        long took = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(third).isEqualTo(429);
        assertThat(took).as("refused without waiting").isLessThan(1000);
        assertThat(refusals.get()).isEqualTo(1);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(meters.get("platform.limits.refused").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aTurnIsGivenBackSoTheNextRequestPasses() throws Exception {
        Filter filter = filter(settings(1, 0, Duration.ofSeconds(2), Map.of()));

        assertThat(immediate(filter, ORGANIZATION)).isEqualTo(200);
        assertThat(immediate(filter, ORGANIZATION)).isEqualTo(200);
        assertThat(immediate(filter, ORGANIZATION)).isEqualTo(200);
        assertThat(refusals.get()).isZero();
    }

    @Test
    void aRequestThatMayWaitGetsTheTurnWhenOneIsFree() throws Exception {
        Filter filter = filter(settings(1, 1, Duration.ofSeconds(5), Map.of()));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> first = request(filter, ORGANIZATION, inside, release);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Integer> waiter = request(filter, ORGANIZATION, new CountDownLatch(1), new CountDownLatch(0));
        Thread.sleep(200);
        assertThat(waiter.isDone()).as("it waits").isFalse();
        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(refusals.get()).isZero();
    }

    @Test
    void aRequestThatWaitsTooLongIsRefused() throws Exception {
        Filter filter = filter(settings(1, 1, Duration.ofMillis(300), Map.of()));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        request(filter, ORGANIZATION, inside, release);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(immediate(filter, ORGANIZATION)).isEqualTo(429);
        release.countDown();
    }

    @Test
    void onlyAFewMayWaitAndTheRestAreRefusedAtOnce() throws Exception {
        Filter filter = filter(settings(1, 1, Duration.ofSeconds(5), Map.of()));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        request(filter, ORGANIZATION, inside, release);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Integer> waiter = request(filter, ORGANIZATION, new CountDownLatch(1), new CountDownLatch(0));
        Thread.sleep(200);

        long started = System.nanoTime();
        int refused = immediate(filter, ORGANIZATION);

        assertThat(refused).isEqualTo(429);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).as("no wait for the second")
                .isLessThan(2000);
        release.countDown();
        assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo(200);
    }

    @Test
    void anotherOrganizationIsNotAffectedWhileOneIsFull() throws Exception {
        Filter filter = filter(settings(1, 0, Duration.ofSeconds(2), Map.of()));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        request(filter, ORGANIZATION, inside, release);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(immediate(filter, ORGANIZATION)).isEqualTo(429);
        assertThat(immediate(filter, OTHER)).isEqualTo(200);
        release.countDown();
    }

    @Test
    void anOrganizationCanHaveNumbersOfItsOwn() throws Exception {
        Filter filter = filter(settings(1, 0, Duration.ofSeconds(2), Map.of(ORGANIZATION.value().toString(),
                new OrganizationLimitProperties.Custom(3, null, null))));
        CountDownLatch inside = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) {
            request(filter, ORGANIZATION, inside, release);
        }
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(immediate(filter, ORGANIZATION)).isEqualTo(429);
        release.countDown();
    }

    @Test
    void aRequestWithNoMemberBehindItIsNotLimited() throws Exception {
        Filter filter = filter(settings(1, 0, Duration.ofSeconds(2), Map.of()));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        request(filter, ORGANIZATION, inside, release);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        // The platform host and the public endpoints: a context without a member, or none at all.
        MockHttpServletResponse withoutContext = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/platform/status"), withoutContext,
                new MockFilterChain());
        MockHttpServletResponse withoutMember = new MockHttpServletResponse();
        try (TenantContexts.Scope _ = contexts.open(TenantContext.of(ORGANIZATION))) {
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/session"), withoutMember,
                    new MockFilterChain());
        }

        assertThat(withoutContext.getStatus()).isEqualTo(200);
        assertThat(withoutMember.getStatus()).isEqualTo(200);
        release.countDown();
    }

    @Test
    void whatIsNotTheApiIsNotLimited() throws Exception {
        Filter filter = filter(settings(1, 0, Duration.ofSeconds(2), Map.of()));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        request(filter, ORGANIZATION, inside, release);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse page = new MockHttpServletResponse();
        try (TenantContexts.Scope _ = contexts.open(new TenantContext(ORGANIZATION, UUID.randomUUID(),
                UUID.randomUUID()))) {
            filter.doFilter(new MockHttpServletRequest("GET", "/index.html"), page, new MockFilterChain());
        }

        assertThat(page.getStatus()).isEqualTo(200);
        release.countDown();
    }

    // ---- the numbers ----

    @Test
    void theStatementLimitIsShortForReadingLongerForChangingAndPerOrganization() {
        UUID special = UUID.randomUUID();
        StatementTimeouts timeouts = new StatementTimeouts(new OrganizationLimitProperties(true, 6, 6,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), Map.of(special.toString(),
                        new OrganizationLimitProperties.Custom(null, Duration.ofSeconds(10), null))));

        assertThat(timeouts.of(UUID.randomUUID(), true)).isEqualTo(Duration.ofSeconds(5));
        assertThat(timeouts.of(UUID.randomUUID(), false)).isEqualTo(Duration.ofSeconds(30));
        assertThat(timeouts.of(special, true)).isEqualTo(Duration.ofSeconds(10));
        assertThat(timeouts.of(special, false)).as("only the number named changes").isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void numbersOutsideTheirRangeAreRefusedAtStartup() {
        assertThatThrownBy(() -> settings(0, 0, Duration.ofSeconds(1), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrganizationLimitProperties(true, 6, 6, Duration.ofSeconds(2),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Map.of())).as("changing below reading")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(6, 6, Duration.ofMinutes(5), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
