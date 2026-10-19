package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * The health of automation: what waits, what failed and what is overdue (Sprint 25, ADR-0163). Counts and the place to
 * look, nothing else: no name of an organization or a person, no value of a record. The screen draws the sections as
 * they come and holds no list of them.
 *
 * @param generatedAt when the counts were read
 * @param sections the parts of automation, in the order to show them
 */
public record AutomationHealthView(@NotNull Instant generatedAt, @NotNull List<AutomationHealthSectionView> sections) {

    /** Copies the list. */
    public AutomationHealthView {
        sections = List.copyOf(sections);
    }
}
