package app.platform.web.error;

import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.logging.LogContext;
import app.platformapi.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Answers errors that happen outside the controllers (for example a failure in a servlet filter or a request the
 * container itself rejects) with the same error model, replacing the container's default error page.
 *
 * <p>Runs on the container's error dispatch, after the correlation filter has finished, so the identifiers are
 * taken from the request attributes the filter left behind.
 */
@Controller
class PlatformErrorController implements ErrorController {

    private final ErrorResponses responses;
    private final ErrorTracker tracker;

    PlatformErrorController(ErrorResponses responses, ErrorTracker tracker) {
        this.responses = responses;
        this.tracker = tracker;
    }

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<Object> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        ErrorCode code = status instanceof Integer value ? ErrorCode.forHttpStatus(value) : ErrorCode.NOT_FOUND;
        Object cause = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        Object requestId = request.getAttribute(LogContext.REQUEST_ID_ATTRIBUTE);
        Object traceId = request.getAttribute(LogContext.TRACE_ID_ATTRIBUTE);
        LogContext.Scope requestScope = scopeFor(LogContext.REQUEST_ID, requestId);
        LogContext.Scope traceScope = scopeFor(LogContext.TRACE_ID, traceId);
        try {
            if (cause instanceof Throwable throwable && code == ErrorCode.INTERNAL_ERROR) {
                tracker.track(ErrorReport.current(
                        throwable, request.getMethod(), path instanceof String text ? text : ""));
            }
            return responses.of(code);
        } finally {
            traceScope.close();
            requestScope.close();
        }
    }

    private static LogContext.Scope scopeFor(String key, Object value) {
        return value instanceof String text ? LogContext.with(key, text) : () -> { };
    }
}
