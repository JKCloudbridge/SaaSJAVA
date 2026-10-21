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
 * @param notes what the set leaves behind that is not a reason to refuse it, for example that records hold a pair a
 *        dependent picklist no longer allows (counts and field names, never a value); empty when there is nothing
 */
public record ChangeSetReportView(@NotNull Boolean valid, @NotNull List<ProblemView> problems,
        @NotNull List<ReleaseItemView> items, @NotNull List<ObjectView> objects, @NotNull List<String> notes) {

    /** Copies the lists. */
    public ChangeSetReportView {
        problems = problems == null ? List.of() : List.copyOf(problems);
        items = items == null ? List.of() : List.copyOf(items);
        objects = objects == null ? List.of() : List.copyOf(objects);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** A report with no notes. */
    public ChangeSetReportView(Boolean valid, List<ProblemView> problems, List<ReleaseItemView> items,
            List<ObjectView> objects) {
        this(valid, problems, items, objects, List.of());
    }
}
