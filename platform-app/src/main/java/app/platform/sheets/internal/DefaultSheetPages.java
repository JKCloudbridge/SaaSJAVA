package app.platform.sheets.internal;

import app.platform.sheets.SheetPages;
import app.platform.sheets.internal.SheetGrid.Alignment;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.ColumnType;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Kind;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SheetGrid.Style;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A page of positioned cells as a sheet document, an Excel file and the cells of a file (FA8, ADR-0202). The page is
 * turned into the same typed grid every sheet is, so the screen's grid and the file writer draw it exactly as they draw
 * a template: the columns are named A, B, C ... and the rows r1, r2 ... and nothing of the page is stored.
 */
@Service
class DefaultSheetPages implements SheetPages {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final int MOST_CELLS = 5_000;

    private final SpreadsheetImporter importer;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultSheetPages(SpreadsheetImporter importer) {
        this.importer = importer;
    }

    @Override
    public byte[] xlsx(Page page) {
        return new XlsxWriter().write(gridOf(page), "", true);
    }

    @Override
    public Map<String, Object> grid(Page page) {
        return json.readValue(SheetGridWriter.write(gridOf(page)), MAP);
    }

    @Override
    public PrintSettings print(Page page) {
        return XlsxWriter.printSettings(gridOf(page));
    }

    @Override
    public List<Found> read(byte[] file) {
        RawSheet raw;
        try {
            raw = importer.readCells(file);
        } catch (ImportRefusal e) {
            throw switch (e.kind()) {
                case TOO_LARGE -> new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, e.words());
                case BUSY -> ApiException.rateLimited(2);
                default -> ApiException.validation("file", e.words());
            };
        }
        List<Found> found = new ArrayList<>();
        raw.rows.forEach((row, cells) -> cells.forEach((column, cell) -> {
            if (cell.text() != null && !cell.text().isBlank()) {
                found.add(new Found(row, column, cell.text()));
            }
        }));
        return found;
    }

    private static SheetGrid gridOf(Page page) {
        int columns = page.columnWidths().size();
        int rows = page.rowHeights().size();
        if (columns < 1 || rows < 1 || page.cells().size() > MOST_CELLS) {
            throw new IllegalArgumentException("A page has at least one cell and at most " + MOST_CELLS);
        }
        List<Column> columnList = new ArrayList<>();
        for (int c = 1; c <= columns; c++) {
            columnList.add(new Column("c" + c, letters(c), ColumnType.TEXT, false, null, null,
                    page.columnWidths().get(c - 1), false));
        }
        List<Line> rowList = new ArrayList<>();
        for (int r = 1; r <= rows; r++) {
            rowList.add(new Line("r" + r, null, page.rowHeights().get(r - 1)));
        }
        Map<String, SheetGrid.Cell> cells = new LinkedHashMap<>();
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= columns; c++) {
                cells.put("r" + r + ":c" + c, new SheetGrid.Cell(null, null, Kind.FIXED));
            }
        }
        for (SheetPages.Cell one : page.cells()) {
            if (one.row() < 1 || one.row() > rows || one.column() < 1 || one.column() > columns) {
                throw new IllegalArgumentException("A cell is outside its page");
            }
            Style style = new Style(one.bold(), false, one.large() ? FontSize.LARGE : FontSize.NORMAL, false,
                    one.center() ? Alignment.CENTER : null, null);
            String text = one.text() == null || one.text().isEmpty() ? null : one.text();
            cells.put("r" + one.row() + ":c" + one.column(), new SheetGrid.Cell(text, style, Kind.FIXED));
        }
        List<Merge> merges = new ArrayList<>();
        for (SheetPages.Area area : page.merges()) {
            merges.add(new Merge("r" + area.top() + ":c" + area.left(), "r" + area.bottom() + ":c" + area.right()));
        }
        return new SheetGrid(2, RowsMode.FIXED, columnList, List.of(), rowList, cells, merges);
    }

    private static String letters(int column) {
        StringBuilder name = new StringBuilder();
        for (int n = column; n > 0; n = (n - 1) / 26) {
            name.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return name.toString();
    }
}
