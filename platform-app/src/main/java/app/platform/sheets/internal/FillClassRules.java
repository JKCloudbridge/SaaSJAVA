package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.InputClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who may change which cell of a fill-in in format 3 (SH5, ADR-0251): a cell of the class {@code filler} is the
 * filler's and a cell of the class {@code reviewer} is the reviewer's. The cells document is saved whole, so the
 * rule is
 * applied to what changed: every cell whose value differs between the stored document and the new one must be of the
 * writer's own class. A pure function of the sheet and the two documents, with no database, so it is tested alone.
 *
 * <p>The counted correction of a person who may override the lock (ADR-0192) is a third kind of writer and is not
 * judged here: that person may correct any input cell, as before.
 */
final class FillClassRules {

    /** The words shown for a cell that belongs to the other person. They never repeat a value. */
    static final String OTHER_PARTS = "This cell is filled in by someone else.";

    private FillClassRules() {
    }

    /**
     * The places of the cells that changed although they are not of the writer's class, at most twenty, in the order
     * of the new document and then the removed cells. Empty when the change is the writer's own.
     *
     * @param structure the pinned sheet
     * @param before the stored cells
     * @param after the checked new cells
     * @param writer the class the person writes as
     * @return the places, as {@code cells.rows[<index>].cells.<column>}; a removed cell is placed by its row's id
     */
    static List<String> violations(FillStructure structure, FillDoc before, FillDoc after, InputClass writer) {
        if (!structure.isFormatThree()) {
            return List.of();
        }
        Map<String, Map<String, Object>> old = cellsByRow(before);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < after.rows().size(); i++) {
            FillDoc.Row row = after.rows().get(i);
            Map<String, Object> was = old.getOrDefault(row.id(), Map.of());
            seen.add(row.id());
            for (Map.Entry<String, Object> cell : row.cells().entrySet()) {
                if (!Objects.equals(was.get(cell.getKey()), cell.getValue())
                        && notOwn(structure, row.id(), cell.getKey(), writer)) {
                    out.add("cells.rows[" + i + "].cells." + cell.getKey());
                }
            }
            for (String column : was.keySet()) {
                if (!row.cells().containsKey(column) && notOwn(structure, row.id(), column, writer)) {
                    out.add("cells.rows[" + i + "].cells." + column);
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> row : old.entrySet()) {
            if (seen.contains(row.getKey())) {
                continue;
            }
            for (String column : row.getValue().keySet()) {
                if (notOwn(structure, row.getKey(), column, writer)) {
                    out.add("cells.rows." + row.getKey() + ".cells." + column);
                }
            }
        }
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    /** Whether the sheet has a cell of the reviewer's class, so that there is something for a reviewer to fill. */
    static boolean hasReviewerCells(FillStructure structure) {
        return structure.isFormatThree() && structure.tab().inputs().values().stream()
                .anyMatch(input -> input.cls() == InputClass.REVIEWER);
    }

    private static boolean notOwn(FillStructure structure, String rowId, String column, InputClass writer) {
        if (structure.column(column) == null) {
            return false;
        }
        return structure.effective(rowId, column).cls() != writer;
    }

    private static Map<String, Map<String, Object>> cellsByRow(FillDoc doc) {
        Map<String, Map<String, Object>> byRow = new LinkedHashMap<>();
        for (FillDoc.Row row : doc.rows()) {
            byRow.put(row.id(), row.cells());
        }
        return byRow;
    }
}
