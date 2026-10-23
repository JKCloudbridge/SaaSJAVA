package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One fill-in with the structure it was made against and the cells the person typed (Functional track FF1, ADR-0192).
 * Both are the organization's own text and never print.
 *
 * @param fill the facts about the fill-in
 * @param grid the sheet version the fill-in is pinned to (the schema {@code SheetGrid} of this document): its columns,
 *        its fixed text and which cells are input cells
 * @param cells what was typed: {@code formatVersion} and {@code rows}, each row with its {@code id} and its
 *        {@code cells} keyed by the identifier of the column
 * @param canEdit whether the screen may offer to type into the cells; every request that follows is decided again
 * @param correction whether typing now would be a correction after submit (the screen says so and the change is
 *        counted and audited)
 * @param limits what a fill-in may hold, so that the screen can say so before the person tries
 */
public record SheetFillView(@NotNull SheetFillSummaryView fill, @NotNull Map<String, Object> grid,
        @NotNull Map<String, Object> cells, @NotNull Boolean canEdit, @NotNull Boolean correction,
        @NotNull SheetFillLimitsView limits) {

    /** Copies the maps, so that they cannot be changed from outside. */
    public SheetFillView {
        grid = grid == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(grid));
        cells = cells == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(cells));
    }

    @Override
    public String toString() {
        return "SheetFillView[redacted]";
    }
}
