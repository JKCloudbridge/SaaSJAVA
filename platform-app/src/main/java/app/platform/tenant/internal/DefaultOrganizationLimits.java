package app.platform.tenant.internal;

import app.platform.tenant.OrganizationLimits;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The per-organization limits (Sprint 16, ADR-0108): the filter that bounds the requests in progress, and the time
 * limit the database applies to the statements of an organization's transactions.
 *
 * <p>One {@link Semaphore} per organization holds its turns. A request takes one at once if there is one; otherwise it
 * waits for up to {@code max-wait}, but only a few requests of an organization may wait ({@code max-waiting}) and the
 * next is refused immediately, so a flood by one organization can occupy at most {@code max-concurrent} plus
 * {@code max-waiting} request threads and cannot starve the others of threads. A refused request gets 429 with
 * {@code Retry-After} and did nothing. Requests with no organization member behind them (the platform host, a public
 * endpoint) are not limited, and the probes are on another port.
 */
@Component
class DefaultOrganizationLimits implements OrganizationLimits {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOrganizationLimits.class);
    private static final long LOG_EVERY_MILLIS = 10_000;

    private final OrganizationLimitProperties settings;
    private final TenantContexts contexts;
    private final HandlerExceptionResolver errors;
    private final ConcurrentMap<UUID, Turns> turns = new ConcurrentHashMap<>();
    private final Counter refused;
    private final Timer waited;
    private final AtomicLong lastLogged = new AtomicLong();

    DefaultOrganizationLimits(OrganizationLimitProperties settings, TenantContexts contexts,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors, MeterRegistry meters) {
        this.settings = settings;
        this.contexts = contexts;
        this.errors = errors;
        this.refused = Counter.builder("platform.limits.refused").description("Requests refused: an organization "
                + "had as many in progress as it may").register(meters);
        this.waited = Timer.builder("platform.limits.wait").description("Time requests waited for a turn")
                .register(meters);
    }

    @Override
    public Filter requestFilter() {
        return new LimitFilter();
    }

    private Turns turnsOf(UUID tenant) {
        return turns.computeIfAbsent(tenant, id -> {
            OrganizationLimitProperties.Custom custom = settings.overrides().get(id.toString());
            int concurrent = custom != null && custom.maxConcurrent() != null ? custom.maxConcurrent()
                    : settings.maxConcurrent();
            return new Turns(concurrent);
        });
    }

    /** The turns of one organization on this instance. */
    private static final class Turns {

        private final Semaphore permits;
        private final AtomicInteger waiting = new AtomicInteger();

        Turns(int concurrent) {
            this.permits = new Semaphore(concurrent, true);
        }
    }

    private final class LimitFilter extends OncePerRequestFilter {

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !settings.enabled() || !request.getRequestURI().startsWith(ApiPaths.V1 + "/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Optional<TenantContext> member = contexts.current().filter(context -> context.membershipId() != null);
            if (member.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            Turns own = turnsOf(member.get().tenantId().value());
            if (!take(own)) {
                refused.increment();
                logLimited();

                if (errors.resolveException(request, response, null, ApiException
                        .organizationBusy(1)) == null) {
                    response.sendError(429);
                }
                return;
            }
            try {
                chain.doFilter(request, response);
            } finally {
                own.permits.release();
            }
        }

        /** A turn at once, or after a short wait if few are waiting; false when the request must be refused. */
        private boolean take(Turns own) {
            if (own.permits.tryAcquire()) {
                return true;
            }
            if (own.waiting.incrementAndGet() > settings.maxWaiting()) {
                own.waiting.decrementAndGet();
                return false;
            }
            long started = System.nanoTime();
            try {
                return own.permits.tryAcquire(settings.maxWait().toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                own.waiting.decrementAndGet();
                waited.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }
        }

        /** Says that an organization was limited, at most every ten seconds; the organization is in the log context. */
        private void logLimited() {
            long now = System.currentTimeMillis();
            long before = lastLogged.get();
            if (now - before >= LOG_EVERY_MILLIS && lastLogged.compareAndSet(before, now)) {
                LOG.warn("An organization reached its limit of requests in progress; requests over it were refused");
            }
        }
    }
}
