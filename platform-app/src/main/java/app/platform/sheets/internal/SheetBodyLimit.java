package app.platform.sheets.internal;

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
 * Refuses a request to the sheet endpoints whose body is larger than that endpoint can take, before the body is read
 * (FA3, ADR-0186). The save of a grid may carry up to the size limit of a grid; every other request carries a few dozen
 * bytes and is held to a small bound.
 *
 * <p>It looks at the length the client announces. A client that announces none (a chunked body) passes here and is
 * bounded by {@link SheetController}, which counts the bytes it reads and stops at the limit; the front proxy of the
 * deployment must set a request size limit too (a deployment requirement, written in the runbook).
 */
@Component
class SheetBodyLimit implements HandlerInterceptor, WebMvcConfigurer {

    private final SheetProperties properties;
    private final FillProperties fills;

    SheetBodyLimit(SheetProperties properties, FillProperties fills) {
        this.properties = properties;
        this.fills = fills;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(ApiPaths.SHEETS + "/**", ApiPaths.SHEET_FILLS + "/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long announced = request.getContentLengthLong();
        boolean put = "PUT".equals(request.getMethod());
        boolean grid = put && request.getRequestURI().endsWith("/grid");
        boolean cells = put && request.getRequestURI().endsWith("/cells");
        if (grid && announced > properties.limits().maxBytes()) {
            throw SheetController.tooLarge(properties.limits().maxBytes());
        }
        if (cells && announced > fills.maxBytes()) {
            throw SheetFillController.tooLarge(fills.maxBytes());
        }
        if (!grid && !cells && announced > properties.smallBodyBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        return true;
    }
}
