package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One block of a form (Sprint 19, ADR-0129). The text is typed by a person, so it never prints.
 *
 * @param label the heading of the block
 * @param columns 1 or 2: how many columns the fields flow in
 * @param fields the API names of the fields in it, in the order shown; each one is a field described in the same answer
 */
public record FormSectionView(@NotNull String label, @NotNull Integer columns, @NotNull List<String> fields) {

    /** Copies the list. */
    public FormSectionView {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    @Override
    public String toString() {
        return "FormSectionView[redacted]";
    }
}
