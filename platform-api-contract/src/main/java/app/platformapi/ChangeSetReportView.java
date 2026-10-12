package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The result of checking a change set, or a rollback, without keeping anything (Sprint 11): the same checks and the
 * same rules as publishing, so a set that is valid here publishes unless someone changes the same things first.
 *
 * @param valid whether it can be published
 * @param problems every reason it cannot, each naming what is wrong and what depends on it
 * @param items what would be added, changed or removed
 * @param objects for a preview, the affected objects as they would be after publishing (empty for a plain check)
 */
public record ChangeSetReportView(@NotNull Boolean valid, @NotNull List<ProblemView> problems,
        @NotNull List<ReleaseItemView> items, @NotNull List<ObjectView> objects) {

    /** Copies the lists. */
    public ChangeSetReportView {
        problems = problems == null ? List.of() : List.copyOf(problems);
        items = items == null ? List.of() : List.copyOf(items);
        objects = objects == null ? List.of() : List.copyOf(objects);
    }
}
