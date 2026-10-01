package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * What the security filter chains do with a refusal: hand it to the platform's one exception handler, so a 401 or a 403
 * has the same body, request ID and trace ID as every other error (ADR-0011) and the exception's own text is never
 * shown. Without this the framework would answer with an empty body or a login page.
 */
final class ApiSecurityHandlers {

    private ApiSecurityHandlers() {
    }

    /** Anything that needs a sign-in and has none: 401 in the error model. */
    static final class EntryPoint implements AuthenticationEntryPoint {

        private final HandlerExceptionResolver errors;

        EntryPoint(HandlerExceptionResolver errors) {
            this.errors = errors;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                AuthenticationException exception) throws IOException {
            if (errors.resolveException(request, response, null, exception) == null) {
                response.sendError(HttpStatus.UNAUTHORIZED.value());
            }
        }
    }

    /** A signed-in caller who may not do this: 403 in the error model. */
    static final class Denied implements AccessDeniedHandler {

        private final HandlerExceptionResolver errors;

        Denied(HandlerExceptionResolver errors) {
            this.errors = errors;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                AccessDeniedException exception) throws IOException {
            if (errors.resolveException(request, response, null, exception) == null) {
                response.sendError(HttpStatus.FORBIDDEN.value());
            }
        }
    }

    /**
     * The authorization endpoint without a sign-in sends the browser to the sign-in page; every other authorization
     * server endpoint answers 401 in the error model. The redirect is relative on purpose: the page lives on the same
     * host the person is on, which the API cannot always name (behind the development proxy it sees another address).
     */
    static final class AuthorizeEntryPoint implements AuthenticationEntryPoint {

        private final EntryPoint fallback;

        AuthorizeEntryPoint(EntryPoint fallback) {
            this.fallback = fallback;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                AuthenticationException exception) throws IOException {
            if (ApiPaths.OAUTH2_AUTHORIZE.equals(request.getRequestURI()) && "GET".equals(request.getMethod())) {
                response.setStatus(HttpStatus.FOUND.value());
                response.setHeader(HttpHeaders.LOCATION, "/sign-in");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                return;
            }
            fallback.commence(request, response, exception);
        }
    }
}
