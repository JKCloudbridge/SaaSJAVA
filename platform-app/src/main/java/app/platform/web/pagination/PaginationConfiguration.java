package app.platform.web.pagination;

import jakarta.validation.Validator;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the paging parameter binding for every controller. */
@Configuration
class PaginationConfiguration implements WebMvcConfigurer {

    private final Validator validator;

    PaginationConfiguration(Validator validator) {
        this.validator = validator;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageRequestArgumentResolver(validator));
    }
}
