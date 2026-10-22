package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One version of a sheet with its grid (Functional track FA3, ADR-0186). The grid is the organization's own text and
 * never prints.
 *
 * @param version the facts about the version
 * @param grid the grid (the schema {@code SheetGrid} of this document)
 */
public record SheetVersionView(@NotNull SheetVersionSummaryView version, @NotNull Map<String, Object> grid) {

    /** Copies the grid, so that it cannot be changed from outside. */
    public SheetVersionView {
        grid = grid == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(grid));
    }

    @Override
    public String toString() {
        return "SheetVersionView[redacted]";
    }
}
