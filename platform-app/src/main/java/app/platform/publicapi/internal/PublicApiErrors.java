package app.platform.publicapi.internal;

import app.platform.observability.ErrorReport;
import app.platform.sharedkernel.logging.LogContext;
import app.platformapi.ApiError;
import app.platformapi.ApiErrorResponse;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Says what is wrong with a body that could not be read, with the place (Sprint 30, ADR-0245). The internal handler
 * answers every unreadable body with one sentence; a customer's software needs more: a property that the contract does
 * not have is refused with its place, never ignored, and a property of the wrong type is told by its place too.
 *
 * <p>The words are fixed. The place is made of the names the caller wrote, and only a name shaped like an API name is
 * echoed; any other name is answered as {@code request}, so a body can never put its own text into an answer. A body
 * that grew past the limit while it was being read is {@code PAYLOAD_TOO_LARGE}.
 */
@RestControllerAdvice(basePackages = "app.platform.publicapi")
@Order(Ordered.HIGHEST_PRECEDENCE)
class PublicApiErrors {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,79}$");
    private static final String UNKNOWN_PROPERTY = "Is not a property of this request.";
    private static final String WRONG_TYPE = "Has the wrong type or an invalid value.";
    private static final String UNREADABLE = "The request body is missing or is not valid.";

    private final MeterRegistry meters;

    PublicApiErrors(MeterRegistry meters) {
        this.meters = meters;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        // A body that grew past the limit is found first: the parser wraps the failure of the stream in an error of
        // its own that has a place, and that place is not the fault.
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof PublicApiEdgeFilter.BodyTooLarge) {
                return answer(ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.defaultMessage(), Map.of());
            }
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
                String sentence = cause instanceof UnrecognizedPropertyException ? UNKNOWN_PROPERTY : WRONG_TYPE;
                return answer(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                        Map.of(placeOf(jackson), List.of(sentence)));
            }
        }
        return answer(ErrorCode.MALFORMED_REQUEST, UNREADABLE, Map.of());
    }

    /** The place of the fault, for example {@code records[2].fields} or {@code request}. */
    static String placeOf(JacksonException exception) {
        StringBuilder place = new StringBuilder();
        for (JacksonException.Reference reference : exception.getPath()) {
            String name = reference.getPropertyName();
            if (name != null) {
                if (!NAME.matcher(name).matches()) {
                    return "request";
                }
                if (place.length() > 0) {
                    place.append('.');
                }
                place.append(name);
            } else if (reference.getIndex() >= 0) {
                place.append('[').append(reference.getIndex()).append(']');
            }
        }
        return place.length() == 0 ? "request" : place.toString();
    }

    private ResponseEntity<ApiErrorResponse> answer(ErrorCode code, String message, Map<String, List<String>> fields) {
        meters.counter("platform.api.errors", "code", code.name()).increment();
        ApiError error = new ApiError(code, message, fields,
                LogContext.requestId().orElse(ErrorReport.UNKNOWN_REQUEST), LogContext.traceId().orElse(null));
        return ResponseEntity.status(code.httpStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(error));
    }
}
