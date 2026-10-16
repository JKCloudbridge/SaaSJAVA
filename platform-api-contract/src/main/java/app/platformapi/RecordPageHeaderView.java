package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The header card of a resolved record page (Sprint 20): its title field and a few other fields shown below it. Both
 * are already filtered to what the caller may read; a field not readable never appears here.
 *
 * @param titleField the field that names the record, or null when the object has none the caller may read
 * @param highlightFields a few other fields, in the order to show them, at most six
 */
public record RecordPageHeaderView(String titleField, @NotNull List<String> highlightFields) {

    public RecordPageHeaderView {
        highlightFields = List.copyOf(highlightFields);
    }
}
