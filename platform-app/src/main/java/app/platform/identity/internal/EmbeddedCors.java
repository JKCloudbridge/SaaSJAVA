package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The one cross-origin rule of the API (Sprint 18, ADR-0127). The script of a custom page runs in a sandboxed frame
 * whose
 * origin is opaque, so a browser calls the API from the origin {@code null}, and answers its calls only if the API
 * allows that origin. This is the whole rule: the origin {@code null}, the methods and headers a data call uses, and
 * <b>no credentials</b> (the browser neither sends cookies nor hands the page an answer to a call that carried them).
 * A script therefore reaches the API only with the bearer token it was given.
 *
 * <p>Every other request gets no configuration at all. That matters: with a configuration for everyone, the framework
 * would treat an ordinary same-origin request from the application's own page, which reaches the API under another host
 * name behind the development proxy, as a cross-origin request and refuse it.
 */
final class EmbeddedCors implements CorsConfigurationSource {

    /** The origin a sandboxed frame without {@code allow-same-origin} sends. */
    static final String SANDBOXED_ORIGIN = "null";

    private final CorsConfiguration configuration = build();

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        // The public API has no cross-origin rule at all (Sprint 30, ADR-0249): it is for servers, and a page of the
        // platform reaches the internal API, which is the one its sandboxed scripts are allowed to call.
        if (ApiPaths.isPublicApiPath(request.getRequestURI())) {
            return null;
        }
        return SANDBOXED_ORIGIN.equals(request.getHeader("Origin")) ? configuration : null;
    }

    private static CorsConfiguration build() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(SANDBOXED_ORIGIN));
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID", "X-File-Name"));
        cors.setExposedHeaders(List.of("X-Request-ID", "X-Trace-Id", "Retry-After", "Content-Disposition"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        return cors;
    }
}
