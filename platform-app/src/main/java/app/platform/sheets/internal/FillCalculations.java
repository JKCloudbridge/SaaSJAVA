package app.platform.sheets.internal;

import app.platformapi.SheetCalculatedAnswerView;
import app.platformapi.SheetCalculatedCellView;
import app.platformapi.SheetCalculatedColumnView;
import app.platformapi.SheetCalculationView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The answers of the formulas of a fill-in (SH5, ADR-0252 decision 6): the pinned sheet and the values of the input
 * cells go into {@link SheetCalculator}, which is pure. Nothing calculated is ever stored in the cells document; every
 * read and every save asks again, so a lowered limit or a corrected formula is seen at the next question and the
 * server's answer is the one that counts (the browser calculates only a preview while a person types).
 *
 * <p>Nothing here is logged: a value and an answer are text of the organization.
 */
@Component
class FillCalculations {

    private final FormulaProperties formulas;

    FillCalculations(FormulaProperties formulas) {
        this.formulas = formulas;
    }

    /** Whether the sheet has any formula at all; a sheet with none is never calculated. */
    static boolean hasFormulas(FillStructure structure) {
        return structure.isFormatThree()
                && (!structure.tab().formulas().isEmpty() || !structure.tab().columnFormulas().isEmpty());
    }

    /** The answers of the sheet for these cells, or null when the sheet has no formula. */
    SheetCalculator.Outcome calculate(FillStructure structure, FillDoc doc) {
        if (!hasFormulas(structure)) {
            return null;
        }
        return SheetCalculator.calculate(structure.tab(), inputs(structure, doc), formulas.limits());
    }

    /**
     * The values the calculator reads: a number as a number, a yes or no as a boolean (also the answer of a Yes/No cell
     * with a reason, whose reason is never read), anything else as text; an attachment cell holds files and is never an
     * operand. A sheet where the filler adds all the rows is given the rows
     * in order, any other sheet the cells by their key.
     */
    static SheetCalculator.Inputs inputs(FillStructure structure, FillDoc doc) {
        boolean byRow = structure.fillerAddsAllRows();
        Map<String, Object> cells = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FillDoc.Row row : doc.rows()) {
            Map<String, Object> mine = new LinkedHashMap<>();
            for (Map.Entry<String, Object> cell : row.cells().entrySet()) {
                Object value = operand(structure, row.id(), cell.getKey(), cell.getValue());
                if (value != null) {
                    mine.put(cell.getKey(), value);
                    if (!byRow) {
                        cells.put(row.id() + ":" + cell.getKey(), value);
                    }
                }
            }
            if (byRow) {
                rows.add(mine);
            }
        }
        return new SheetCalculator.Inputs(cells, rows);
    }

    private static Object operand(FillStructure structure, String rowId, String column, Object value) {
        if (structure.column(column) == null || value instanceof List<?>) {
            return null;
        }
        if (value instanceof FillDoc.Choice choice) {
            // A formula reads the answer of a Yes/No cell with a reason, never the reason (SH5.1, ADR-0263).
            return choice.answer();
        }
        FillStructure.CellType type = structure.effective(rowId, column).type();
        if (type == FillStructure.CellType.NUMBER && value instanceof String text) {
            try {
                return new BigDecimal(text).doubleValue();
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (type == FillStructure.CellType.TEXT && value instanceof String text && isPlainNumber(text)) {
            // A number typed into a cell is a number, as in a spreadsheet (SH5.1, ADR-0263): an uploaded form makes
            // every column text, and SUM skips text, so "5" typed for a mark must count. A date or a word stays text.
            return new BigDecimal(text.strip()).doubleValue();
        }
        return value;
    }

    /** An optional sign, digits, an optional fraction: what a person types for a number; nothing else. */
    static boolean isPlainNumber(String text) {
        String t = text.strip();
        return t.length() <= 40 && t.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");
    }

    /** The answers as the screen reads them. */
    SheetCalculationView view(FillStructure structure, FillDoc doc) {
        SheetCalculator.Outcome outcome = calculate(structure, doc);
        if (outcome == null) {
            return new SheetCalculationView(0, List.of(), List.of());
        }
        List<SheetCalculatedCellView> cells = new ArrayList<>();
        outcome.cells().forEach((cell, answer) -> cells.add(new SheetCalculatedCellView(cell, answer(answer))));
        List<SheetCalculatedColumnView> columns = new ArrayList<>();
        outcome.columns().forEach((column, answers) -> columns.add(new SheetCalculatedColumnView(column,
                answers.stream().map(FillCalculations::answer).toList())));
        return new SheetCalculationView(structure.fillerAddsAllRows() ? doc.rows().size() : 0, cells, columns);
    }

    private static SheetCalculatedAnswerView answer(SheetCalculator.Answer answer) {
        return new SheetCalculatedAnswerView(answer.kind().json(), answer.value());
    }
}
