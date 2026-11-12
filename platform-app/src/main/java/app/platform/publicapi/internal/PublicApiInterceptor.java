package app.platform.publicapi.internal;

import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Two duties at the door of every operation of the public API (Sprint 30, ADR-0244):
 *
 * <ul>
 *   <li><b>The host.</b> The public API is answered on an organization host only. The platform host, which has no
 *       organization, answers every operation but the description exactly like an unknown organization: the same
 *       {@code NOT_FOUND}, in the same words.</li>
 *   <li><b>The measure.</b> {@code platform.publicapi.requests{operation,result}} and
 *       {@code platform.publicapi.request.duration{operation}}, with the operation name of the contract and the class
 *       of the status. Nothing else is a tag: never a path, a value, an identifier, a token or an organization.</li>
 * </ul>
 */
@Component
class PublicApiInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final String STARTED = PublicApiInterceptor.class.getName() + ".started";
    private static final String OPERATION = PublicApiInterceptor.class.getName() + ".operation";
    private static final String UNKNOWN = "unknown";

    /** The words the tenant module gives an address that names no organization (kept the same on purpose). */
    static final String NO_ORGANIZATION = "No organization exists at this address.";

    private final TenantContexts contexts;
    private final MeterRegistry meters;

    PublicApiInterceptor(TenantContexts contexts, MeterRegistry meters) {
        this.contexts = contexts;
        this.meters = meters;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(ApiPaths.PUBLIC_V1 + "/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String operation = operationOf(handler);
        request.setAttribute(OPERATION, operation);
        request.setAttribute(STARTED, Timer.start(meters));
        boolean description = ApiPaths.PUBLIC_OPENAPI.equals(request.getRequestURI());
        if (!description && contexts.current().isEmpty()) {
            // The platform host: no organization, so nothing here exists. The words are those of an unknown host.
            throw new ApiException(ErrorCode.NOT_FOUND, NO_ORGANIZATION);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception exception) {
        Object started = request.getAttribute(STARTED);
        String operation = String.valueOf(request.getAttribute(OPERATION));
        if (started instanceof Timer.Sample sample) {
            sample.stop(Timer.builder("platform.publicapi.request.duration").tag("operation", operation)
                    .register(meters));
        }
        meters.counter("platform.publicapi.requests", "operation", operation, "result",
                resultOf(response.getStatus())).increment();
    }

    private static String operationOf(Object handler) {
        if (handler instanceof HandlerMethod method) {
            Operation annotation = method.getMethodAnnotation(Operation.class);
            if (annotation != null && !annotation.operationId().isEmpty()) {
                return annotation.operationId();
            }
        }
        return UNKNOWN;
    }

    /** The class of a status: a handful of values, so the tag stays small. */
    static String resultOf(int status) {
        if (status >= 500) {
            return "server_error";
        }
        if (status >= 400) {
            return "client_error";
        }
        return "success";
    }
}
