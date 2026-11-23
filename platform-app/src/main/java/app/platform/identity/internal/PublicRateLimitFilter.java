package app.platform.identity.internal;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ApiHeaders;
import app.platformapi.ApiPaths;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Counts every request of the public API against the limits and tells the caller where it stands (Sprint 32,
 * ADR-0280 to ADR-0282). It sits in the API chain after the caller is known and after what the token may reach has
 * been decided, so a request that is not an operation of the public API, one that is not allowed to this token and the
 * description document are never counted; a request that is counted is counted whether it succeeds, is refused for
 * another cause or repeats a keyed write, because it reached the server.
 *
 * <p>Every answer of a counted operation carries {@code RateLimit-Policy} and {@code RateLimit}; a refusal is the
 * ordinary {@code 429 RATE_LIMITED} of the error model with {@code Retry-After} in whole seconds, the same headers and
 * one fixed sentence that never says which limit was full. This is a different cause from the requests in progress of
 * ADR-0108, which has its own words and no headers.
 *
 * <p><strong>Fails open on its own faults.</strong> If counting throws (the counters are resilient already, so this is
 * a bug and not an outage) the request goes on and the fault is counted in a metric: a limiter that refuses everyone is
 * a worse failure than one that lets a request through. Not a bean: it belongs to the API filter chain.
 */
final class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(PublicRateLimitFilter.class);
    private static final String POLICY = ApiHeaders.RATE_LIMIT_POLICY;
    private static final String RATE = ApiHeaders.RATE_LIMIT;
    private static final String BULK_OPERATION = "bulkRecords";
    private static final String DESCRIPTION_OPERATION = "getPublicApiDescription";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RateLimits limits;
    private final RateLimitProperties properties;
    private final TenantContexts contexts;
    private final HandlerExceptionResolver errors;
    private final MeterRegistry meters;
    private final int maxBulkBodyBytes;
    private volatile boolean reportedFault;

    PublicRateLimitFilter(RateLimits limits, RateLimitProperties properties, TenantContexts contexts,
            HandlerExceptionResolver errors, MeterRegistry meters, int maxBulkBodyBytes) {
        this.limits = limits;
        this.properties = properties;
        this.contexts = contexts;
        this.errors = errors;
        this.meters = meters;
        this.maxBulkBodyBytes = maxBulkBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !ApiPaths.isPublicApiPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<PublicApiAccess.Operation> operation =
                PublicApiAccess.operationOf(request.getMethod(), request.getRequestURI());
        Optional<RateLimits.Caller> caller = callerOf(SecurityContextHolder.getContext().getAuthentication());
        if (operation.isEmpty() || DESCRIPTION_OPERATION.equals(operation.get().name()) || caller.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest forward = request;
        long cost = 1;
        if (BULK_OPERATION.equals(operation.get().name())) {
            ReplayableRequest replay = new ReplayableRequest(request, maxBulkBodyBytes);
            forward = replay;
            cost = RateLimitMath.cost(true, replay.records(), properties.bulkMaxCost());
        }
        RateLimits.Decision decision;
        try {
            decision = limits.check(caller.get(), cost);
        } catch (RuntimeException fault) {
            meters.counter("platform.limits.errors").increment();
            if (!reportedFault) {
                reportedFault = true;
                // The type only: the request goes on, and a message of a library may carry connection details.
                LOG.warn("The limits on the public API could not count a request and let it through: {}",
                        fault.getClass().getName());
            }
            chain.doFilter(forward, response);
            return;
        }
        response.setHeader(POLICY, RateLimitMath.policyHeader(decision.items()));
        response.setHeader(RATE, RateLimitMath.rateHeader(decision.items()));
        if (decision.allowed()) {
            chain.doFilter(forward, response);
            return;
        }
        if (errors.resolveException(request, response, null, ApiException.rateLimited(decision.retryAfterSeconds()))
                == null) {
            response.sendError(429);
        }
    }

    /** The caller whose rate is counted: the registration of an application, else the member of a person's token. */
    private Optional<RateLimits.Caller> callerOf(Authentication authentication) {
        Optional<TenantContext> context = contexts.current();
        if (context.isEmpty() || !(authentication instanceof BearerTokenAuthentication token)) {
            return Optional.empty();
        }
        UUID tenant = context.get().tenantId().value();
        Object registration = token.getTokenAttributes().get(TokenAuthentication.CLIENT_ROW_ATTRIBUTE);
        try {
            if (registration instanceof String row) {
                return Optional.of(new RateLimits.Caller(tenant, true, UUID.fromString(row)));
            }
            return Optional.of(new RateLimits.Caller(tenant, false, UUID.fromString(token.getName())));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The request with its body kept, so that the number of records of a bulk call can be read here and the controller
     * still reads the whole body afterwards. At most {@code limit} bytes are held; whatever is beyond is passed on
     * from the original stream, so the size rules of the edge filter and the controller are unchanged.
     */
    private static final class ReplayableRequest extends HttpServletRequestWrapper {

        private final byte[] held;
        private final InputStream rest;
        private final int records;

        ReplayableRequest(HttpServletRequest request, int limit) throws IOException {
            super(request);
            InputStream original = request.getInputStream();
            this.held = original.readNBytes(limit + 1);
            this.rest = original;
            // A body longer than the largest the edge allows cannot be read here: it costs the most a call may.
            this.records = held.length > limit ? Integer.MAX_VALUE : countRecords(held);
        }

        int records() {
            return records;
        }

        private static int countRecords(byte[] body) {
            try {
                JsonNode list = JSON.readTree(body).path("records");
                return list.isArray() ? list.size() : 1;
            } catch (RuntimeException unreadable) {
                // The controller refuses a body it cannot read; here it costs one.
                return 1;
            }
        }

        @Override
        public ServletInputStream getInputStream() {
            InputStream joined = held.length > 0
                    ? new SequenceInputStream(new ByteArrayInputStream(held), rest) : rest;
            return new ServletInputStream() {
                private boolean ended;

                @Override
                public boolean isFinished() {
                    return ended;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("The body is read synchronously");
                }

                @Override
                public int read() throws IOException {
                    int next = joined.read();
                    ended = next < 0;
                    return next;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = joined.read(buffer, offset, length);
                    ended = count < 0;
                    return count;
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
