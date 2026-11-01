package app.platform.publicapi.internal;

import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * What every request to the public API meets first (Sprint 30, ADR-0244, ADR-0247), before the security chain:
 *
 * <ol>
 *   <li><b>The kill switch.</b> With {@code platform.publicapi.enabled=false} every address under the prefix is
 *       answered {@code NOT_FOUND}, the description included, in the error model, whoever asks.</li>
 *   <li><b>The version.</b> {@code API-Version} on every answer, and for a deprecated operation {@code Deprecation},
 *       {@code Sunset} and {@code Link} ({@link ApiVersioning}). Set here so that an answer of the security chain, a
 *       refusal or an error carries them too.</li>
 *   <li><b>The size of the body.</b> A body over the limit is refused with {@code PAYLOAD_TOO_LARGE} before any of it
 *       is read when its length is announced, and as soon as the count passes the limit when it is not (a body sent in
 *       pieces), so the server never holds more than the limit whatever a caller announces.</li>
 * </ol>
 *
 * <p>Runs just inside the tenant filter, so a refusal carries the request ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class PublicApiEdgeFilter extends OncePerRequestFilter {

    private static final Set<String> WITH_BODY = Set.of("POST", "PUT", "PATCH");
    private static final String BULK_SUFFIX = "/records/bulk";

    private final PublicApiProperties properties;
    private final ApiVersioning versioning;
    private final HandlerExceptionResolver errors;

    PublicApiEdgeFilter(PublicApiProperties properties, ApiVersioning versioning,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.properties = properties;
        this.versioning = versioning;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiPaths.isPublicApiPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            refuse(request, response, new ApiException(ErrorCode.NOT_FOUND));
            return;
        }
        versioning.headersFor(request.getMethod(), request.getRequestURI()).forEach(response::setHeader);
        if (!WITH_BODY.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        long limit = request.getRequestURI().endsWith(BULK_SUFFIX) ? properties.limits().maxBulkBodyBytes()
                : properties.limits().maxBodyBytes();
        if (request.getContentLengthLong() > limit) {
            refuse(request, response, new ApiException(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        }
        chain.doFilter(new CountedBody(request, limit), response);
    }

    /** Answers through the platform's one error handler, so the body is the same model as everywhere. */
    private void refuse(HttpServletRequest request, HttpServletResponse response, ApiException error)
            throws IOException {
        if (errors.resolveException(request, response, null, error) == null) {
            response.sendError(error.code().httpStatus());
        }
    }

    /** Thrown from the body stream when the caller has sent more than the limit; recognised by the error advice. */
    static final class BodyTooLarge extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLarge() {
            super("body too large");
        }
    }

    /** A request whose body stops being readable once it passes the limit. */
    private static final class CountedBody extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        CountedBody(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Counting(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class Counting extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        Counting(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0 && ++count > limit) {
                throw new BodyTooLarge();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count += read;
                if (count > limit) {
                    throw new BodyTooLarge();
                }
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
