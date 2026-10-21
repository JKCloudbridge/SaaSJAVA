package app.platform.platformadmin.internal;

import app.platform.sharedkernel.solution.FeatureNotes;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Asks every shipped solution whether it has words for the platform administrator about a feature (ADR-0181): what
 * happens to an organization that loses it. The console shows them before the feature is taken from a plan or switched
 * off for an organization; they come from the API, so the browser holds none of them. A feature nobody speaks for has
 * no note.
 */
@Component
class FeatureNoteDirectory {

    private final ObjectProvider<FeatureNotes> sources;

    FeatureNoteDirectory(ObjectProvider<FeatureNotes> sources) {
        this.sources = sources;
    }

    /** The note for the feature, or empty. */
    Optional<String> removalNote(String feature) {
        List<FeatureNotes> all = sources.orderedStream().toList();
        return all.stream().map(source -> source.removalNote(feature)).flatMap(Optional::stream).findFirst();
    }
}
