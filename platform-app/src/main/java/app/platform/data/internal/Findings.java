package app.platform.data.internal;

import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The problems one stage of the write pipeline found, by place (a field's API name, or {@code fields} for the record as
 * a whole). A stage reports every problem it finds in one answer, as the metadata endpoints do, and the pipeline stops
 * after the first stage that found any: a later stage would judge values an earlier stage has already said are wrong.
 * A message says what is expected; it never repeats a value (ADR-0089).
 */
final class Findings {

    private final Map<String, List<String>> problems = new LinkedHashMap<>();

    /** Notes a problem at a place; the same words twice at one place count once. */
    void add(String place, String text) {
        List<String> texts = problems.computeIfAbsent(place, name -> new ArrayList<>());
        if (!texts.contains(text)) {
            texts.add(text);
        }
    }

    boolean isEmpty() {
        return problems.isEmpty();
    }

    /** The places that have a problem (tests). */
    Set<String> places() {
        return Set.copyOf(problems.keySet());
    }

    /** The problems at a place (tests). */
    List<String> at(String place) {
        return List.copyOf(problems.getOrDefault(place, List.of()));
    }

    /**
     * Ends the write when this stage found anything.
     *
     * @throws ApiException {@code VALIDATION_ERROR} with every problem and its place
     */
    void throwIfAny() {
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
    }
}
