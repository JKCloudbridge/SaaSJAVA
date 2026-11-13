package app.platform.sheets.internal;

import app.platform.sheets.SheetExports;
import app.platform.sheets.internal.formula.FormulaLimits;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sheet as a file (FA7, ADR-0201). The version comes from {@link SheetVersions#readable}, the one rule of who may
 * read a version; the grid is read into the typed model and written by {@link XlsxWriter}. A writer is made for each
 * call because it keeps the styles of the workbook it is writing.
 */
@Service
@Transactional(readOnly = true)
class DefaultSheetExports implements SheetExports {

    /** What the information of a file written from a sheet in format version 2 says (plain words, no markup). */
    static final String LOOK_NOT_YET = "Made from a sheet in the new format. Its colours, fonts, borders and number "
            + "formats are not in this file yet.";

    /** Said in the information of a file when the sheet has formulas: until SH3 an answer is written, not a formula. */
    static final String FORMULAS_AS_ANSWERS = " Formulas are written as their calculated answers, not as formulas.";

    private final SheetVersions versions;
    private final FormulaProperties formulas;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultSheetExports(SheetVersions versions, FormulaProperties formulas) {
        this.versions = versions;
        this.formulas = formulas;
    }

    @Override
    public byte[] xlsx(String ownerObject, UUID ownerId, int versionNo, String footerRight) {
        SheetVersionStore.WithGrid found = versions.readable(ownerObject, ownerId, versionNo);
        JsonNode stored = json.readTree(found.grid());
        SheetGrid grid = SheetGridReader.read(stored);
        // A sheet in format version 2 is written as far as format version 1 goes: its text, widths, heights, merges and
        // the footer. Its look is drawn by a later release (SH3), and the file says so. A formula is written as the
        // answer the server calculates for it, as text, never as a formula (SH2); the file says so too.
        String note = null;
        if (SheetFormats.isFormatTwo(stored)) {
            note = LOOK_NOT_YET;
            SheetDocument.Tab tab = SheetDocumentReader.read(stored).sheets().get(0);
            if (!tab.formulas().isEmpty()) {
                grid = withAnswers(grid, tab, formulas.limits());
                note += FORMULAS_AS_ANSWERS;
            }
        }
        return new XlsxWriter().write(grid, footerRight, false, note);
    }

    /** The grid with the answer of each formula cell as its text. */
    static SheetGrid withAnswers(SheetGrid grid, SheetDocument.Tab tab, FormulaLimits limits) {
        SheetCalculator.Outcome outcome = SheetCalculator.calculate(tab, SheetCalculator.Inputs.none(), limits);
        Map<String, SheetGrid.Cell> cells = new LinkedHashMap<>(grid.cells());
        outcome.cells().forEach((key, answer) -> {
            SheetGrid.Cell held = cells.get(key);
            cells.put(key, new SheetGrid.Cell(answer.value(), held == null ? null : held.style(), null));
        });
        return new SheetGrid(grid.dimension(), grid.rowsMode(), grid.columns(), grid.headerRows(), grid.rows(), cells,
                grid.merges());
    }
}
