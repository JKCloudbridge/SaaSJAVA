package app.platform.web.error;

import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into the platform's single error model (ADR-0011): a stable code, a generic message, the
 * invalid fields when relevant, and the request and trace IDs.
 *
 * <p>Nothing from an exception reaches the client except what a module deliberately put into an
 * {@link ApiException}. Framework and library messages (they name classes, fields, parse positions and sometimes
 * the rejected value) are replaced by the generic text of the error code. Unexpected failures are reported
 * through the error tracking hook and answered with {@link ErrorCode#INTERNAL_ERROR}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final int MAX_FIELDS = 100;
    private static final String INVALID_FORMAT = "Has an invalid format.";
    private static final String INVALID = "Is invalid.";

    private final ErrorResponses responses;
    private final ErrorTracker tracker;

    public GlobalExceptionHandler(ErrorResponses responses, ErrorTracker tracker) {
        this.responses = responses;
        this.tracker = tracker;
    }

    // ---- failures raised on purpose by modules ----

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException exception, WebRequest request) {
        if (exception.code().httpStatus() >= 500) {
            track(exception, request);
        }
        return responses.of(exception.code(), exception.getMessage(), exception.fields());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLocking() {
        return responses.of(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Object> handleDuplicateKey() {
        return responses.of(ErrorCode.CONFLICT);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<Object> handleDatabaseUnavailable(Exception exception, WebRequest request) {
        track(exception, request);
        return responses.of(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            addField(fields, field, violation.getMessage());
        }
        return responses.of(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), fields);
    }

    /** Everything not handled above is a bug or an outage: report it, tell the client nothing about it. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        track(exception, request);
        return responses.of(ErrorCode.INTERNAL_ERROR);
    }

    // ---- failures raised by the web framework ----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            addField(fields, error.getField(), messageOf(error));
        }
        for (ObjectError error : exception.getBindingResult().getGlobalErrors()) {
            addField(fields, error.getObjectName(), messageOrDefault(error.getDefaultMessage()));
        }
        return responses.of(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), fields);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            String name = parameter == null ? "request" : parameter;
            result.getResolvableErrors().forEach(error -> {
                String field = error instanceof FieldError fieldError ? fieldError.getField() : name;
                addField(fields, field, messageOrDefault(error.getDefaultMessage()));
            });
        });
        return responses.of(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), fields);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        // The parser's message names classes and positions and can quote the input: never forwarded.
        return responses.of(ErrorCode.MALFORMED_REQUEST, "The request body is missing or is not valid.", Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String property = exception.getPropertyName();
        Map<String, List<String>> fields = new LinkedHashMap<>();
        addField(fields, property == null ? "request" : property, INVALID_FORMAT);
        return responses.of(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), fields);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        addField(fields, exception.getParameterName(), "Is required.");
        return responses.of(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), fields);
    }

    /** Every other framework exception (unknown path, wrong method, unsupported type, too large, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = ErrorCode.forHttpStatus(statusCode.value());
        if (statusCode.is5xxServerError() || exception instanceof ErrorResponse && code == ErrorCode.INTERNAL_ERROR) {
            track(exception, request);
        }
        ResponseEntity<Object> response = responses.of(code);
        // Keep protocol headers the framework asks for (Allow on 405, Accept on 415, Retry-After).
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(response.getHeaders());
        headers.forEach((name, values) -> {
            if (!merged.containsHeader(name)) {
                merged.addAll(name, values);
            }
        });
        return new ResponseEntity<>(response.getBody(), merged, response.getStatusCode());
    }

    // ---- helpers ----

    private void track(Exception exception, WebRequest request) {
        String method = "";
        String path = "";
        if (request instanceof ServletWebRequest servlet) {
            method = servlet.getRequest().getMethod();
            path = servlet.getRequest().getRequestURI();
        }
        tracker.track(ErrorReport.current(exception, method, path));
    }

    private static void addField(Map<String, List<String>> fields, String field, String problem) {
        if (fields.size() >= MAX_FIELDS && !fields.containsKey(field)) {
            return;
        }
        fields.computeIfAbsent(field, key -> new ArrayList<>()).add(problem);
    }

    /** The constraint's own message, or a generic one when the failure is a binding failure (type conversion). */
    private static String messageOf(FieldError error) {
        return error.isBindingFailure() ? INVALID_FORMAT : messageOrDefault(error.getDefaultMessage());
    }

    private static String messageOrDefault(String message) {
        return message == null || message.isBlank() ? INVALID : message;
    }
}
