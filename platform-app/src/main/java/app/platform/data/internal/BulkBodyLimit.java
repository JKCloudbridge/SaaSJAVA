package app.platform.data.internal;

import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Refuses a bulk request whose body is larger than the limit before the body is read (Sprint 17, ADR-0122). The size
 * of one record's values is checked by the pipeline for each record; this bounds what the server will parse for the
 * whole call. Like {@link RecordBodyLimit} it looks at the announced length; a client that announces none is bounded
 * by the front proxy of the deployment.
 */
@Component
class BulkBodyLimit implements HandlerInterceptor, WebMvcConfigurer {

    private final long maxBodyBytes;

    BulkBodyLimit(BulkProperties properties) {
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(ApiPaths.OBJECTS + "/*/records/bulk");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getContentLengthLong() > maxBodyBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        return true;
    }
}
