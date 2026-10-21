package app.platform.solution.internal;

import app.platform.sharedkernel.solution.FeatureNotes;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The words each solution has for the platform administrator about its plan feature (ADR-0179, ADR-0181). */
@Component
class SolutionFeatureNotes implements FeatureNotes {

    private final Solutions solutions;

    SolutionFeatureNotes(Solutions solutions) {
        this.solutions = solutions;
    }

    @Override
    public Optional<String> removalNote(String feature) {
        return solutions.ofFeature(feature).map(SolutionBundle::removalNote).filter(note -> !note.isEmpty());
    }
}
