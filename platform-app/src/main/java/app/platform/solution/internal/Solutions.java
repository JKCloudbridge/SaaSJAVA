package app.platform.solution.internal;

import java.util.List;
import java.util.Optional;

/**
 * The shipped solutions of this release, loaded and checked once at start and then only read (ADR-0181). There is no way
 * to change them while the application runs, which is what keeps an organization from editing or removing any part.
 */
final class Solutions {

    private final List<SolutionBundle> bundles;

    Solutions(List<SolutionBundle> bundles) {
        this.bundles = List.copyOf(bundles);
    }

    List<SolutionBundle> bundles() {
        return bundles;
    }

    /** The solution with the key. */
    Optional<SolutionBundle> bundle(String key) {
        return bundles.stream().filter(bundle -> bundle.key().equals(key)).findFirst();
    }

    /** The solution that speaks for the plan feature. */
    Optional<SolutionBundle> ofFeature(String feature) {
        return bundles.stream().filter(bundle -> bundle.feature().equals(feature)).findFirst();
    }
}
