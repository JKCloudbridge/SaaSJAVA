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
 * Refuses a record request whose body is larger than any record can be, before the body is read (ADR-0089). The values
 * of a record are bounded after they are parsed ({@link RecordValues}); this bounds what the server is willing to parse
 * at all. The limit is four times the record limit, which leaves room for the names, the quotes and the escapes of the
 * JSON text and is still small.
 *
 * <p>It looks at the length the client announces. A client that announces none (chunked) is bounded by the front
 * proxy of the deployment, which must set a request size limit too (a deployment requirement, written in the runbook).
 */
@Component
class RecordBodyLimit implements HandlerInterceptor, WebMvcConfigurer {

    private final long maxBodyBytes;

    RecordBodyLimit(RecordProperties properties) {
        this.maxBodyBytes = 4L * properties.limits().maxRecordBytes();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(ApiPaths.OBJECTS + "/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getContentLengthLong() > maxBodyBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        return true;
    }
}
