package app.platform.metadata.internal;

import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Collects the problems of one request, so that a person who got three things wrong is told all three at once instead
 * of fixing them one by one. Each check runs and, if it refuses, its problems are kept and the next check still runs;
 * {@link #throwIfAny()} then refuses the request with everything found.
 */
final class Problems {

    private final Map<String, List<String>> found = new LinkedHashMap<>();

    /** The result of the check, or null (after keeping its problems) when the check refused. */
    <T> T check(Supplier<T> check) {
        try {
            return check.get();
        } catch (ApiException refused) {
            refused.fields().forEach((field, texts) -> {
                List<String> kept = found.computeIfAbsent(field, key -> new ArrayList<>());
                texts.stream().filter(text -> !kept.contains(text)).forEach(kept::add);
            });
            if (refused.fields().isEmpty()) {
                throw refused;
            }
            return null;
        }
    }

    /** Whether any check has refused so far. */
    boolean any() {
        return !found.isEmpty();
    }

    /** Refuses the request with every problem found, if there is one. */
    void throwIfAny() {
        if (!found.isEmpty()) {
            throw ApiException.validation(found);
        }
    }
}
