package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The grid a new sheet starts from, without a database: its size, its identifiers and that the validator takes it. */
class BlankGridsTest {

    private final SheetGridValidator validator =
            new SheetGridValidator(SheetProperties.Limits.standard(), new SheetSchemas());

    @Test
    void aSheetThatNamesItsRowsStartsWithTenColumnsAndTenRows() {
        SheetGrid grid = BlankGrids.of(2, RowsMode.FIXED);

        assertThat(grid.columns()).hasSize(10);
        assertThat(grid.columns()).extracting(Column::label)
                .containsExactly("Column 1", "Column 2", "Column 3", "Column 4", "Column 5", "Column 6", "Column 7",
                        "Column 8", "Column 9", "Column 10");
        assertThat(grid.rows()).hasSize(10);
        assertThat(grid.headerRows()).isEmpty();
        assertThat(grid.cells()).isEmpty();
    }

    @Test
    void aSheetWhoseFillerAddsTheRowsStartsWithTenColumnsAndNamesNoRow() {
        SheetGrid grid = BlankGrids.of(1, RowsMode.REPEATABLE);

        assertThat(grid.columns()).hasSize(10);
        assertThat(grid.rows()).isEmpty();
    }

    @Test
    void everyIdentifierIsGeneratedAndDifferent() {
        SheetGrid grid = BlankGrids.of(2, RowsMode.FIXED);
        Set<String> seen = new HashSet<>();
        grid.columns().forEach(column -> assertThat(seen.add(column.id())).isTrue());
        for (Line row : grid.rows()) {
            assertThat(seen.add(row.id())).isTrue();
        }
        assertThat(BlankGrids.of(2, RowsMode.FIXED).columns().get(0).id()).isNotEqualTo(grid.columns().get(0).id());
    }

    @Test
    void theValidatorTakesBothShapes() {
        assertThat(validator.check(BlankGrids.of(2, RowsMode.FIXED)).accepted()).isTrue();
        assertThat(validator.check(BlankGrids.of(2, RowsMode.FIXED_AND_REPEATABLE)).accepted()).isTrue();
        assertThat(validator.check(BlankGrids.of(1, RowsMode.REPEATABLE)).accepted()).isTrue();
        assertThat(List.of(BlankGrids.STARTING_SIZE)).containsExactly(10);
    }
}
