package app.platform.sharedkernel.solution;

import java.util.Optional;

/**
 * Words the platform administrator reads when a plan feature is taken away (Functional track FA1, ADR-0179, ADR-0181).
 * A solution that ships behind a feature implements it, so the sentence that says what removing the feature does lives
 * with the thing it describes and reaches the console through the API; the browser holds none of it.
 */
public interface FeatureNotes {

    /**
     * What happens to an organization that loses the feature, in plain words, or empty when the feature has no note.
     * The text is fixed by the platform; it never contains a value an organization typed.
     */
    Optional<String> removalNote(String feature);
}
