package app.platform.web.error;

import app.platform.observability.ErrorReport;
import app.platform.sharedkernel.logging.LogContext;
import app.platformapi.ApiError;
import app.platformapi.ApiErrorResponse;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Builds the single error response of the API (ADR-0011) and counts every error that is sent. */
@Component
public class ErrorResponses {

    private final MeterRegistry meters;

    public ErrorResponses(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Builds an error response.
     *
     * @param code the stable code; decides the HTTP status
     * @param message a message written for the client, never an exception message
     * @param fields invalid fields for validation errors, otherwise empty
     */
    ResponseEntity<Object> of(ErrorCode code, String message, Map<String, List<String>> fields) {
        meters.counter("platform.api.errors", "code", code.name()).increment();
        ApiError error = new ApiError(
                code,
                message,
                fields,
                LogContext.requestId().orElse(ErrorReport.UNKNOWN_REQUEST),
                LogContext.traceId().orElse(null));
        // Always JSON, whatever the client asked for: an error must be readable even after failed content negotiation.
        return ResponseEntity.status(code.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(error));
    }

    /** An error response with the code's generic message. */
    ResponseEntity<Object> of(ErrorCode code) {
        return of(code, code.defaultMessage(), Map.of());
    }
}
