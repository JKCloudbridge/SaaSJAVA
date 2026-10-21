package app.platform.solution.internal;

import app.platform.licensing.FeatureView;
import app.platform.licensing.Plans;
import app.platform.metadata.PlatformObjects;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Wiring of the solution module: the bundles are read and checked once, when the application starts, so that a mistake in
 * a file stops it from starting instead of being found by an organization (ADR-0181). What can be checked without the
 * database is checked when the bean is made; that the feature exists is checked right after, by {@link FeatureCheck}.
 */
@Configuration
class SolutionModuleConfiguration {

    @Bean
    Solutions solutions(PlatformObjects platform) {
        List<SolutionBundle> bundles = SolutionLoader.load();
        SolutionValidator.validate(bundles, platform, null);
        return new Solutions(bundles);
    }

    /** Stops the start when a solution names a plan feature the catalogue does not have. */
    @Component
    static class FeatureCheck implements ApplicationRunner {

        private static final Logger LOG = LoggerFactory.getLogger(FeatureCheck.class);

        private final Solutions solutions;
        private final Plans plans;
        private final PlatformObjects platform;

        FeatureCheck(Solutions solutions, Plans plans, PlatformObjects platform) {
            this.solutions = solutions;
            this.plans = plans;
            this.platform = platform;
        }

        @Override
        public void run(ApplicationArguments arguments) {
            Set<String> features = plans.features().stream().map(FeatureView::key).collect(Collectors.toSet());
            SolutionValidator.validate(solutions.bundles(), platform, features);
            LOG.info("{} shipped solutions loaded", solutions.bundles().size());
        }
    }
}
