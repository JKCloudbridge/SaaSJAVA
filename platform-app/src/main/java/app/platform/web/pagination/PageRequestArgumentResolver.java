package app.platform.web.pagination;

import app.platformapi.ApiException;
import app.platformapi.Cursors;
import app.platformapi.PageRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Binds {@link PageRequest} from {@code ?limit=&cursor=} and validates it, so no controller can forget to: a limit
 * out of range, a limit that is not a number or a cursor that is not one of ours is answered with a validation
 * error naming the parameter. Values are never silently clamped or ignored.
 */
public final class PageRequestArgumentResolver implements HandlerMethodArgumentResolver {

    private final Validator validator;

    public PageRequestArgumentResolver(Validator validator) {
        this.validator = validator;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageRequest.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        Integer limit = parseLimit(request.getParameter("limit"), problems);
        String cursor = request.getParameter("cursor");

        PageRequest page = new PageRequest(limit, cursor);
        if (problems.isEmpty()) {
            Set<ConstraintViolation<PageRequest>> violations = validator.validate(page);
            for (ConstraintViolation<PageRequest> violation : violations) {
                problems.computeIfAbsent(violation.getPropertyPath().toString(), key -> new ArrayList<>())
                        .add(violation.getMessage());
            }
        }
        if (problems.isEmpty() && page.cursor() != null && Cursors.decode(page.cursor()).isEmpty()) {
            problems.put("cursor", List.of("Is not a valid cursor."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return page;
    }

    private static Integer parseLimit(String text, Map<String, List<String>> problems) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            problems.put("limit", List.of("Must be a whole number."));
            return null;
        }
    }
}
