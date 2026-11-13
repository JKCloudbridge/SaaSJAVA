package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Alignment;
import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Colour;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Kind;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.Style;
import app.platform.sheets.internal.formula.FormulaTranslator;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.ComparisonOperator;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule;
import org.apache.poi.xssf.usermodel.XSSFCreationHelper;
import org.apache.poi.xssf.usermodel.XSSFDataFormat;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFFontFormatting;
import org.apache.poi.xssf.usermodel.XSSFHyperlink;
import org.apache.poi.xssf.usermodel.XSSFPatternFormatting;
import org.apache.poi.xssf.usermodel.XSSFPrintSetup;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFSheetConditionalFormatting;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCell;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;
import org.springframework.stereotype.Component;

/**
 * Writes a sheet as an .xlsx workbook (SH3, ADR-0253 decision 7), the counterpart of {@link WorkbookReader}. The file
 * is built from our typed model alone and by the library's whole-workbook object, because only that object can store
 * the answer of a formula beside the formula (a streaming writer cannot) and it leaves no temporary file. Nothing a
 * person uploaded is ever copied into it, nothing is opened by name, and the library is not trusted as a guard: the
 * bounds here are ours.
 *
 * <p><strong>What a spreadsheet program must never run.</strong> A formula in the file comes <em>only</em> from the
 * stored formula property of a cell or a calculated column, drawn from the stored form (which our parser accepted and
 * our printer wrote) and checked once more by the library's parser; a formula the library does not know is written
 * as its answer instead. Every other text, fixed text, label, picklist value, footer and title, is a string, and a
 * string that starts with {@code =}, {@code +}, {@code -} or {@code @} also carries the quote prefix, so it stays text
 * when someone edits it. The only link written is the cell's own {@code https} link. There are no macros, images,
 * comments, external references, document author or defined names beyond the built-in rows repeated at the top of a
 * printed page. A font is always one fixed family for its key, a colour is {@code #rrggbb}, a number format is one
 * fixed code. Characters XML cannot hold are dropped.
 *
 * <p>A file is a list of worksheets, one for each sheet (one is used until a template can hold more). The band of
 * column labels, the footer row and the sample rows are exactly what the screen shows, so a person reads the file as
 * the sheet. The size, the number of cells, the time and the number of files written at once are bounded.
 *
 * <p>Nothing typed in a cell, a label, a title, a link or a formula is put in a message.
 */
@Component
final class WorkbookWriter {

    /** The number of blank sample rows of a sheet whose filler adds the rows (the screen shows the same). */
    static final int SAMPLE_ROWS = 10;
    private static final int LABEL_BAND_HEIGHT = 28;
    private static final int DEFAULT_ROW_HEIGHT = 24;
    private static final int DEFAULT_COLUMN_WIDTH = 120;
    private static final int MOST_TEXT = 32_767;
    private static final int MOST_LINK = 2_000;
    private static final int MOST_LIST = 255;
    private static final int MOST_RANGES_PER_LIST = 100;
    private static final String YES = "Yes";
    private static final String NO = "No";

    /**
     * One worksheet to write.
     *
     * @param title the title of the sheet, or null
     * @param grid the body of the sheet (in format version 2, the body of the document's sheet)
     * @param tab what format version 2 adds, or null for a sheet in format version 1
     * @param answers the calculated answers of the formulas, or null when there are none
     * @param footer the text at the right of the footer row; empty leaves the row blank
     * @param plain true writes the rows as a page of their own: no band of column labels and no footer row
     */
    record Page(String title, SheetGrid grid, SheetDocument.Tab tab, SheetCalculator.Outcome answers, String footer,
            boolean plain) {

        /** A sheet in format version 1 with the band and the footer. */
        static Page one(SheetGrid grid, String footer) {
            return new Page(null, grid, null, null, footer, false);
        }
    }

    /**
     * What was written, in counts only.
     *
     * @param bytes the file
     * @param formulas the formulas written as formulas
     * @param formulasAsAnswers the formulas the library could not take, written as their answers
     * @param listsLeftOut the drop-down lists left out (a value with a comma, or a list over 255 characters)
     * @param links the links written
     */
    record Written(byte[] bytes, int formulas, int formulasAsAnswers, int listsLeftOut, int links) {

        /** Prints the size only. */
        @Override
        public String toString() {
            return "Written[" + bytes.length + " bytes]";
        }
    }

    private final ExportProperties settings;
    private final Semaphore places;

    WorkbookWriter(ExportProperties settings) {
        this.settings = settings;
        this.places = new Semaphore(settings.maxConcurrent());
    }

    /** Whether a text is one a spreadsheet program could take for a formula. */
    static boolean looksLikeFormula(String text) {
        return !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0;
    }

    /** A1-style reference of a one-based column and row. */
    static String ref(int column, int row) {
        return FormulaTranslator.letters(column - 1) + row;
    }

    /**
     * The workbook for a list of worksheets.
     *
     * @param pages the sheets, at least one
     * @param description fixed words for the file's information; nothing a person typed
     * @throws ApiException {@code PAYLOAD_TOO_LARGE} when the file would pass a bound, {@code RATE_LIMITED} when too
     *         many files are being written
     */
    Written write(List<Page> pages, String description) {
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("A file has at least one sheet");
        }
        long cells = 0;
        for (Page page : pages) {
            cells += (long) page.grid().columns().size() * (rowsOf(page.grid()).size() + 2);
        }
        if (cells > settings.maxCells()) {
            throw tooLarge();
        }
        try {
            if (!places.tryAcquire(2, TimeUnit.SECONDS)) {
                throw ApiException.rateLimited(2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.rateLimited(2);
        }
        try {
            return build(pages, description);
        } finally {
            places.release();
        }
    }

    private Written build(List<Page> pages, String description) {
        // The workbook is only held in memory and written once; there is no package on disk to close.
        XSSFWorkbook workbook = new XSSFWorkbook();
        Build build = new Build(workbook, System.nanoTime() + settings.maxMillis() * 1_000_000L);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < pages.size(); i++) {
            build.sheet(pages.get(i), SheetNames.of(pages.get(i).title(), i, names));
        }
        if (description != null) {
            workbook.getProperties().getCoreProperties().setDescription(clean(description));
        }
        Bounded out = new Bounded(settings.maxBytes());
        try {
            workbook.write(out);
        } catch (IOException | RuntimeException e) {
            // The library wraps what the buffer throws; a file over the bound is answered as one, in our own words.
            if (out.exceeded) {
                throw tooLarge();
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UncheckedIOException((IOException) e);
        }
        return new Written(out.toByteArray(), build.formulas, build.formulasAsAnswers, build.listsLeftOut,
                build.links);
    }

    private ApiException tooLarge() {
        return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file would be larger than the allowed size of "
                + settings.maxBytes() + " bytes.");
    }

    /** A buffer that refuses to grow past the bound, so a file that is too large is stopped while it is written. */
    private final class Bounded extends ByteArrayOutputStream {

        private final int most;
        /** Whether a write was refused because the file passed the bound. */
        boolean exceeded;

        Bounded(int most) {
            this.most = most;
        }

        @Override
        public synchronized void write(int b) {
            check(1);
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            check(length);
            super.write(bytes, offset, length);
        }

        private void check(int more) {
            if ((long) size() + more > most) {
                exceeded = true;
                throw tooLarge();
            }
        }
    }

    // ---- the rows of a sheet ----

    private record RowSpec(String id, boolean header, int height) {
    }

    private static List<RowSpec> rowsOf(SheetGrid grid) {
        List<RowSpec> rows = new ArrayList<>();
        for (Line line : grid.headerRows()) {
            rows.add(new RowSpec(line.id(), true, height(line)));
        }
        for (Line line : grid.rows()) {
            rows.add(new RowSpec(line.id(), false, height(line)));
        }
        if (grid.dimension() == 1) {
            for (int i = 0; i < SAMPLE_ROWS; i++) {
                rows.add(new RowSpec(null, false, DEFAULT_ROW_HEIGHT));
            }
        }
        return rows;
    }

    private static int height(Line line) {
        return line.height() == null ? DEFAULT_ROW_HEIGHT : line.height();
    }

    // ---- one workbook being built ----

    /** The look of a cell as the library is asked for it; two cells with an equal key share one style. */
    private record StyleKey(String family, int points, boolean bold, boolean italic, boolean underline, String colour,
            String fill, String horizontal, String vertical, boolean wrap, String top, String right, String bottom,
            String left, String numberCode, boolean quotePrefix) {
    }

    private record FontKey(String family, int points, boolean bold, boolean italic, boolean underline,
            String colour) {
    }

    private final class Build {

        private final XSSFWorkbook workbook;
        private final long deadline;
        private final XSSFCreationHelper helper;
        private final XSSFDataFormat formats;
        private final Map<FontKey, XSSFFont> fonts = new HashMap<>();
        private final Map<StyleKey, XSSFCellStyle> styles = new HashMap<>();
        private int formulas;
        private int formulasAsAnswers;
        private int listsLeftOut;
        private int links;

        Build(XSSFWorkbook workbook, long deadline) {
            this.workbook = workbook;
            this.deadline = deadline;
            this.helper = workbook.getCreationHelper();
            this.formats = workbook.createDataFormat();
        }

        void sheet(Page page, String name) {
            new SheetBuild(this, page, workbook.createSheet(name)).run();
        }

        void checkTime() {
            if (System.nanoTime() > deadline) {
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file takes too long to write; the sheet is "
                        + "too large.");
            }
        }

        XSSFCellStyle style(StyleKey key) {
            return styles.computeIfAbsent(key, this::makeStyle);
        }

        private XSSFCellStyle makeStyle(StyleKey key) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(fonts.computeIfAbsent(new FontKey(key.family(), key.points(), key.bold(), key.italic(),
                    key.underline(), key.colour()), this::makeFont));
            if (key.fill() != null) {
                style.setFillForegroundColor(colour(key.fill()));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            style.setAlignment(horizontal(key.horizontal()));
            style.setVerticalAlignment(vertical(key.vertical()));
            style.setWrapText(key.wrap());
            style.setBorderTop(border(key.top()));
            style.setBorderRight(border(key.right()));
            style.setBorderBottom(border(key.bottom()));
            style.setBorderLeft(border(key.left()));
            if (key.numberCode() != null) {
                style.setDataFormat(formats.getFormat(key.numberCode()));
            }
            style.setQuotePrefixed(key.quotePrefix());
            return style;
        }

        private XSSFFont makeFont(FontKey key) {
            XSSFFont font = workbook.createFont();
            font.setFontName(key.family());
            font.setFontHeightInPoints((short) key.points());
            font.setBold(key.bold());
            font.setItalic(key.italic());
            if (key.underline()) {
                font.setUnderline(FontUnderline.SINGLE);
            }
            if (key.colour() != null) {
                font.setColor(colour(key.colour()));
            }
            return font;
        }

        XSSFColor colour(String hex) {
            return new XSSFColor(new byte[] {(byte) Integer.parseInt(hex.substring(1, 3), 16),
                (byte) Integer.parseInt(hex.substring(3, 5), 16), (byte) Integer.parseInt(hex.substring(5, 7), 16)},
                    null);
        }

        private static HorizontalAlignment horizontal(String name) {
            return switch (name == null ? "" : name) {
                case "left" -> HorizontalAlignment.LEFT;
                case "center" -> HorizontalAlignment.CENTER;
                case "right" -> HorizontalAlignment.RIGHT;
                default -> HorizontalAlignment.GENERAL;
            };
        }

        private static VerticalAlignment vertical(String name) {
            return switch (name == null ? "" : name) {
                case "top" -> VerticalAlignment.TOP;
                case "center" -> VerticalAlignment.CENTER;
                default -> VerticalAlignment.BOTTOM;
            };
        }

        private static BorderStyle border(String key) {
            return switch (key == null ? "" : key) {
                case "THIN" -> BorderStyle.THIN;
                case "MEDIUM" -> BorderStyle.MEDIUM;
                case "THICK" -> BorderStyle.THICK;
                case "DOTTED" -> BorderStyle.DOTTED;
                case "DASHED" -> BorderStyle.DASHED;
                case "DOUBLE" -> BorderStyle.DOUBLE;
                default -> BorderStyle.NONE;
            };
        }
    }

    // ---- one worksheet being built ----

    private static final class SheetBuild {

        private final Build build;
        private final Page page;
        private final XSSFSheet sheet;
        private final SheetGrid grid;
        private final SheetDocument.Tab tab;
        private final boolean two;
        private final int first;
        private final List<RowSpec> rows;
        private final Map<String, Integer> rowAt = new HashMap<>();
        private final Map<String, Integer> columnAt = new HashMap<>();
        private final Set<Long> covered = new HashSet<>();
        private final Map<Long, int[]> areaOf = new HashMap<>();
        private FormulaTranslator.Layout layout;
        /** Cells that take a drop-down list: the list, then the rows of each column. */
        private final Map<List<String>, Map<Integer, List<Integer>>> lists = new LinkedHashMap<>();

        SheetBuild(Build build, Page page, XSSFSheet sheet) {
            this.build = build;
            this.page = page;
            this.sheet = sheet;
            this.grid = page.grid();
            this.tab = page.tab();
            this.two = tab != null;
            this.first = page.plain() ? 0 : 1;
            this.rows = rowsOf(grid);
        }

        void run() {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id() != null) {
                    rowAt.put(rows.get(i).id(), i + first);
                }
            }
            for (int i = 0; i < grid.columns().size(); i++) {
                columnAt.put(grid.columns().get(i).id(), i);
            }
            List<String> ids = new ArrayList<>();
            if (!page.plain()) {
                ids.add("");
            }
            rows.stream().filter(row -> row.id() != null).forEach(row -> ids.add(row.id()));
            layout = new FormulaTranslator.Layout(grid.columns().stream().map(Column::id).toList(), ids,
                    grid.dimension() == 1, SAMPLE_ROWS);
            merges();
            columnsAndPane();
            if (!page.plain()) {
                band();
            }
            for (int i = 0; i < rows.size(); i++) {
                build.checkTime();
                body(i, rows.get(i));
            }
            if (!page.plain()) {
                footer();
            }
            conditions();
            validations();
            printSetup();
        }

        // ---- merged areas ----

        private void merges() {
            for (Merge merge : grid.merges()) {
                int[] from = position(merge.from());
                int[] to = position(merge.to());
                if (from == null || to == null || to[0] < from[0] || to[1] < from[1]
                        || from[0] == to[0] && from[1] == to[1]) {
                    continue;
                }
                try {
                    sheet.addMergedRegion(new CellRangeAddress(from[0], to[0], from[1], to[1]));
                } catch (IllegalStateException | IllegalArgumentException e) {
                    continue; // an area that overlaps another is left out; the stored sheet never holds one
                }
                int[] area = {from[0], from[1], to[0], to[1]};
                for (int r = from[0]; r <= to[0]; r++) {
                    for (int c = from[1]; c <= to[1]; c++) {
                        if (r != from[0] || c != from[1]) {
                            covered.add(key(r, c));
                            areaOf.put(key(r, c), area);
                        }
                    }
                }
            }
        }

        private int[] position(String key) {
            int colon = key.indexOf(':');
            if (colon < 0) {
                return null;
            }
            Integer row = rowAt.get(key.substring(0, colon));
            Integer column = columnAt.get(key.substring(colon + 1));
            return row == null || column == null ? null : new int[] {row, column};
        }

        private static long key(int row, int column) {
            return ((long) row << 20) | column;
        }

        // ---- widths, the frozen pane ----

        private void columnsAndPane() {
            sheet.setDefaultRowHeightInPoints(18f);
            for (int c = 0; c < grid.columns().size(); c++) {
                Integer width = grid.columns().get(c).width();
                sheet.setColumnWidth(c, characters(width == null ? DEFAULT_COLUMN_WIDTH : width));
            }
            if (two && tab.freeze() != null && !tab.freeze().isNone()) {
                int frozenRows = tab.freeze().rows() == 0 ? 0 : first + tab.freeze().rows();
                sheet.createFreezePane(tab.freeze().columns(), frozenRows);
            }
        }

        /**
         * Excel's column width unit: 1/256 of a character, from pixels; at least one character, at most 255. A
         * character is 7 pixels and a column has 5 pixels of padding, the measure the old writer used and the reader
         * of an upload undoes (checked side by side against it).
         */
        static int characters(int pixels) {
            double chars = Math.min(255, Math.max(1, (pixels - 5) / 7.0));
            return (int) Math.round(chars * 256);
        }

        // ---- the band, the rows, the footer ----

        private void band() {
            XSSFRow row = sheet.createRow(0);
            row.setHeightInPoints(points(LABEL_BAND_HEIGHT));
            StyleKey style = plainKey(true, Alignment.CENTER, true);
            for (int c = 0; c < grid.columns().size(); c++) {
                String label = grid.columns().get(c).label();
                put(row, c, label, build.style(withQuote(style, label)), null);
            }
        }

        private void footer() {
            int index = first + rows.size();
            XSSFRow row = sheet.createRow(index);
            row.setHeightInPoints(points(DEFAULT_ROW_HEIGHT));
            StyleKey style = plainKey(false, Alignment.RIGHT, false);
            int last = grid.columns().size() - 1;
            for (int c = 0; c <= last; c++) {
                String text = c == last && page.footer() != null && !page.footer().isEmpty() ? page.footer() : null;
                put(row, c, text, build.style(withQuote(style, text)), null);
            }
        }

        private void body(int i, RowSpec spec) {
            int r = i + first;
            XSSFRow row = sheet.createRow(r);
            row.setHeightInPoints(points(spec.height()));
            for (int c = 0; c < grid.columns().size(); c++) {
                if (covered.contains(key(r, c))) {
                    row.createCell(c).setCellStyle(build.style(coveredKey(r, c)));
                    continue;
                }
                cell(row, r, c, spec);
            }
        }

        private void cell(XSSFRow row, int r, int c, RowSpec spec) {
            Column column = grid.columns().get(c);
            String key = spec.id() == null ? null : spec.id() + ":" + column.id();
            Cell found = key == null ? null : grid.cells().get(key);
            String formula = null;
            SheetCalculator.Answer answer = null;
            if (two && key != null && tab.formulas().containsKey(key)) {
                formula = tab.formulas().get(key);
                answer = page.answers() == null ? null : page.answers().cells().get(key);
            } else if (two && spec.id() == null && tab.columnFormulas().containsKey(column.id())) {
                formula = tab.columnFormulas().get(column.id());
                int sample = r - first - (rows.size() - SAMPLE_ROWS);
                List<SheetCalculator.Answer> answers = page.answers() == null ? null
                        : page.answers().columns().get(column.id());
                answer = answers == null || sample < 0 || sample >= answers.size() ? null : answers.get(sample);
            }
            boolean input = found != null && !spec.header() && (found.kind() == Kind.INPUT
                    || found.kind() == null && found.value() == null && formula == null);
            boolean sample = spec.id() == null;
            String text = found == null || input || formula != null ? null : shown(found.value());
            text = text == null ? null : clean(text);
            SheetLook look = two && key != null ? tab.looks().get(key) : null;
            StyleKey style = cellKey(found == null ? null : found.style(), look, input || sample && two);
            if (formula != null) {
                formulaCell(row, r, c, formula, answer, style);
            } else {
                String link = two && key != null && text != null && !text.isEmpty() ? tab.links().get(key) : null;
                put(row, c, text, build.style(withQuote(style, text)), link);
            }
            if (two && formula == null && (input || sample)) {
                listOf(column, key, r, c);
            }
        }

        private XSSFCell formulaCell(XSSFRow row, int r, int c, String stored, SheetCalculator.Answer answer,
                StyleKey style) {
            XSSFCell cell = row.createCell(c);
            cell.setCellStyle(build.style(style));
            String text;
            try {
                text = FormulaTranslator.toExcel(stored, r + 1, layout);
                cell.setCellFormula(text);
            } catch (RuntimeException e) {
                // A formula our printer cannot draw here, or the library does not know, is written as its answer.
                cell.setBlank();
                answerAsValue(cell, answer);
                build.formulasAsAnswers++;
                return cell;
            }
            build.formulas++;
            cached(cell.getCTCell(), answer);
            return cell;
        }

        /** The calculated answer beside the formula, so a program shows the right number before it recalculates. */
        private static void cached(CTCell cell, SheetCalculator.Answer answer) {
            if (answer == null) {
                return;
            }
            switch (answer.kind()) {
                case NUMBER -> cell.setV(answer.value());
                case TEXT -> {
                    cell.setT(STCellType.STR);
                    cell.setV(clean(limit(answer.value())));
                }
                case BOOLEAN -> {
                    cell.setT(STCellType.B);
                    cell.setV("TRUE".equals(answer.value()) ? "1" : "0");
                }
                case ERROR -> {
                    cell.setT(STCellType.E);
                    cell.setV(answer.value());
                }
            }
        }

        private static void answerAsValue(XSSFCell cell, SheetCalculator.Answer answer) {
            if (answer == null) {
                return;
            }
            if (answer.kind() == SheetCalculator.Kind.NUMBER) {
                cell.setCellValue(Double.parseDouble(answer.value()));
            } else {
                cell.setCellValue(clean(limit(answer.value())));
            }
        }

        /** One cell: a string (never a formula) or an empty styled cell, with the cell's own https link, if any. */
        private XSSFCell put(XSSFRow row, int c, String text, XSSFCellStyle style, String link) {
            XSSFCell cell = row.createCell(c);
            cell.setCellStyle(style);
            String clean = text == null ? "" : limit(clean(text));
            if (!clean.isEmpty()) {
                cell.setCellValue(clean);
                if (link != null && httpsLink(link)) {
                    XSSFHyperlink hyperlink = build.helper.createHyperlink(HyperlinkType.URL);
                    hyperlink.setAddress(link);
                    cell.setHyperlink(hyperlink);
                    build.links++;
                }
            }
            return cell;
        }

        // ---- the look of a cell ----

        private StyleKey withQuote(StyleKey key, String text) {
            if (text == null || !looksLikeFormula(clean(text))) {
                return key;
            }
            return new StyleKey(key.family(), key.points(), key.bold(), key.italic(), key.underline(), key.colour(),
                    key.fill(), key.horizontal(), key.vertical(), key.wrap(), key.top(), key.right(), key.bottom(),
                    key.left(), key.numberCode(), true);
        }

        /** The band and the footer: bordered, as in the screen's own grid. */
        private StyleKey plainKey(boolean bold, Alignment alignment, boolean wrap) {
            return new StyleKey(FontFamilies.PAGE_DEFAULT, 11, bold, false, false, null, null,
                    alignment.name().toLowerCase(Locale.ROOT), "center", wrap, "THIN", "THIN", "THIN", "THIN", null,
                    false);
        }

        private StyleKey cellKey(Style style, SheetLook look, boolean fillsIn) {
            Style used = style == null ? new Style(false, false, FontSize.NORMAL) : style;
            if (!two) {
                // Format version 1: every cell bordered, wrapped and centred vertically, as the screen draws it.
                return new StyleKey(FontFamilies.PAGE_DEFAULT, points(used.fontSize()), used.bold(), used.italic(),
                        used.underline(), named(used.colour()), null,
                        used.alignment() == null ? "left" : used.alignment().name().toLowerCase(Locale.ROOT),
                        "center", true, "THIN", "THIN", "THIN", "THIN", null, false);
            }
            SheetLook seen = look == null ? new SheetLook(null, null, null, null, false, null, null, null) : look;
            SheetLook.Borders sides = seen.borders();
            // A cell a filler types into, and says nothing of its look, is bordered so that the place to type is
            // plain; a cell that states a look (even one with no border) is written as it states it.
            boolean plainBox = fillsIn && look == null;
            boolean stated = sides != null && !sides.isEmpty();
            String top = stated ? sides.top() : plainBox ? "THIN" : null;
            String right = stated ? sides.right() : plainBox ? "THIN" : null;
            String bottom = stated ? sides.bottom() : plainBox ? "THIN" : null;
            String left = stated ? sides.left() : plainBox ? "THIN" : null;
            String vertical = seen.verticalAlignment() == null ? null
                    : "MIDDLE".equals(seen.verticalAlignment()) ? "center" : seen.verticalAlignment().toLowerCase(
                            Locale.ROOT);
            return new StyleKey(FontFamilies.of(seen.font()),
                    seen.size() != null ? seen.size() : points(used.fontSize()), used.bold(), used.italic(),
                    used.underline(), seen.colour() != null ? seen.colour() : named(used.colour()), seen.fill(),
                    used.alignment() == null ? null : used.alignment().name().toLowerCase(Locale.ROOT), vertical,
                    seen.wrap(), top, right, bottom, left, NumberFormats.codeOf(seen.numberFormat()), false);
        }

        /** A cell under a merged area: its edge sides are the area's own, and it shares the area's fill. */
        private StyleKey coveredKey(int r, int c) {
            int[] area = areaOf.get(key(r, c));
            Integer anchorRow = area == null ? null : area[0];
            Cell anchor = null;
            String anchorKey = null;
            if (anchorRow != null) {
                RowSpec spec = rows.get(anchorRow - first);
                anchorKey = spec.id() == null ? null : spec.id() + ":" + grid.columns().get(area[1]).id();
                anchor = anchorKey == null ? null : grid.cells().get(anchorKey);
            }
            StyleKey base = cellKey(anchor == null ? null : anchor.style(), two && anchorKey != null
                    ? tab.looks().get(anchorKey) : null, false);
            if (area == null) {
                return base;
            }
            boolean v1 = !two;
            return new StyleKey(base.family(), base.points(), base.bold(), base.italic(), base.underline(),
                    base.colour(), base.fill(), base.horizontal(), base.vertical(), base.wrap(),
                    r == area[0] ? base.top() : v1 ? base.top() : null,
                    c == area[3] ? base.right() : v1 ? base.right() : null,
                    r == area[2] ? base.bottom() : v1 ? base.bottom() : null,
                    c == area[1] ? base.left() : v1 ? base.left() : null, base.numberCode(), false);
        }

        private static int points(FontSize size) {
            return switch (size == null ? FontSize.NORMAL : size) {
                case SMALL -> 9;
                case NORMAL -> 11;
                case LARGE -> 14;
                case TITLE -> 18;
            };
        }

        /** The exact value of a named theme colour; the file holds the colour itself, as the old writer did. */
        private static String named(Colour colour) {
            if (colour == null) {
                return null;
            }
            return switch (colour) {
                case DEFAULT -> null;
                case ACCENT -> "#93418b";
                case SUCCESS -> "#1e7b34";
                case DANGER -> "#b3261e";
            };
        }

        private static float points(int pixels) {
            return Math.round(pixels * 75.0f) / 100.0f;
        }

        // ---- conditional colours ----

        private void conditions() {
            if (!two || tab.conditions().isEmpty()) {
                return;
            }
            XSSFSheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting();
            for (SheetDocument.Condition condition : tab.conditions()) {
                int[] from = position(condition.from());
                int[] to = position(condition.to());
                byte operator = operator(condition.operator());
                if (from == null || to == null || operator == 0 || to[0] < from[0] || to[1] < from[1]) {
                    continue;
                }
                XSSFConditionalFormattingRule rule = condition.isBetween()
                        ? formatting.createConditionalFormattingRule(operator, condition.value(), condition.value2())
                        : formatting.createConditionalFormattingRule(operator, condition.value());
                if (condition.fill() != null) {
                    XSSFPatternFormatting pattern = rule.createPatternFormatting();
                    pattern.setFillBackgroundColor(build.colour(condition.fill()));
                    pattern.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
                }
                if (condition.colour() != null) {
                    XSSFFontFormatting font = rule.createFontFormatting();
                    font.setFontColor(build.colour(condition.colour()));
                }
                formatting.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(from[0], to[0],
                        from[1], to[1])}, rule);
            }
        }

        private static byte operator(String name) {
            return switch (name) {
                case "LT" -> ComparisonOperator.LT;
                case "LTE" -> ComparisonOperator.LE;
                case "EQ" -> ComparisonOperator.EQUAL;
                case "NEQ" -> ComparisonOperator.NOT_EQUAL;
                case "GTE" -> ComparisonOperator.GE;
                case "GT" -> ComparisonOperator.GT;
                case "BETWEEN" -> ComparisonOperator.BETWEEN;
                case "NOT_BETWEEN" -> ComparisonOperator.NOT_BETWEEN;
                default -> (byte) 0;
            };
        }

        // ---- yes/no and picklist cells ----

        /** Notes a cell that a filler types into, if it is a yes/no or a picklist cell. */
        private void listOf(Column column, String key, int r, int c) {
            SheetDocument.Input stated = key == null ? null : tab.inputs().get(key);
            String type = stated != null && stated.type() != null ? stated.type() : column.type().json();
            List<String> values = null;
            if ("boolean".equals(type)) {
                values = List.of(YES, NO);
            } else if ("picklist".equals(type)) {
                values = stated != null && stated.picklist() != null ? stated.picklist() : column.picklist();
            }
            if (values == null || values.isEmpty()) {
                return;
            }
            lists.computeIfAbsent(values, ignored -> new TreeMap<>()).computeIfAbsent(c, ignored -> new ArrayList<>())
                    .add(r);
        }

        private void validations() {
            if (lists.isEmpty()) {
                return;
            }
            XSSFDataValidationHelper helper = new XSSFDataValidationHelper(sheet);
            for (Map.Entry<List<String>, Map<Integer, List<Integer>>> list : lists.entrySet()) {
                List<String> values = list.getKey().stream().map(WorkbookWriter::clean).toList();
                if (!listFits(values)) {
                    build.listsLeftOut++;
                    continue;
                }
                List<CellRangeAddress> ranges = new ArrayList<>();
                for (Map.Entry<Integer, List<Integer>> column : list.getValue().entrySet()) {
                    List<Integer> at = column.getValue().stream().sorted().toList();
                    int start = at.get(0);
                    int previous = start;
                    for (int k = 1; k < at.size(); k++) {
                        int row = at.get(k);
                        if (row != previous + 1) {
                            ranges.add(new CellRangeAddress(start, previous, column.getKey(), column.getKey()));
                            start = row;
                        }
                        previous = row;
                    }
                    ranges.add(new CellRangeAddress(start, previous, column.getKey(), column.getKey()));
                }
                for (int from = 0; from < ranges.size(); from += MOST_RANGES_PER_LIST) {
                    CellRangeAddressList addresses = new CellRangeAddressList();
                    ranges.subList(from, Math.min(ranges.size(), from + MOST_RANGES_PER_LIST))
                            .forEach(addresses::addCellRangeAddress);
                    DataValidationConstraint constraint = helper
                            .createExplicitListConstraint(values.toArray(new String[0]));
                    DataValidation validation = helper.createValidation(constraint, addresses);
                    validation.setShowErrorBox(true);
                    sheet.addValidationData(validation);
                }
            }
        }

        /** A list goes into a file as one text of at most 255 characters whose values hold no comma or quote. */
        private static boolean listFits(List<String> values) {
            int length = 0;
            for (String value : values) {
                if (value.isEmpty() || value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
                    return false;
                }
                length += value.length() + 1;
            }
            return length - 1 <= MOST_LIST;
        }

        // ---- print ----

        private void printSetup() {
            PrintRules.Page printed = PrintRules.of(grid, two ? tab.print() : null);
            XSSFPrintSetup setup = sheet.getPrintSetup();
            setup.setPaperSize(switch (printed.paper()) {
                case "A3" -> PrintSetup.A3_PAPERSIZE;
                case "LETTER" -> PrintSetup.LETTER_PAPERSIZE;
                default -> PrintSetup.A4_PAPERSIZE;
            });
            setup.setLandscape(printed.landscape());
            if (printed.fitToWidth()) {
                sheet.setFitToPage(true);
                setup.setFitWidth((short) 1);
                setup.setFitHeight((short) 0);
            }
            sheet.setMargin(PageMargin.LEFT, PrintRules.MARGIN_SIDE);
            sheet.setMargin(PageMargin.RIGHT, PrintRules.MARGIN_SIDE);
            sheet.setMargin(PageMargin.TOP, PrintRules.MARGIN_TOP);
            sheet.setMargin(PageMargin.BOTTOM, PrintRules.MARGIN_TOP);
            sheet.setMargin(PageMargin.HEADER, 0.3);
            sheet.setMargin(PageMargin.FOOTER, 0.3);
            boolean repeat = !page.plain() && (!two || tab.print() == null || tab.print().repeatHeaderRows());
            if (repeat) {
                sheet.setRepeatingRows(new CellRangeAddress(0, grid.headerRows().size(), -1, -1));
            }
        }
    }

    // ---- small helpers ----

    private static String shown(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? YES : NO;
        }
        return value == null ? null : value.toString();
    }

    private static String limit(String text) {
        return text.length() > MOST_TEXT ? text.substring(0, MOST_TEXT) : text;
    }

    /** Whether an address is one the cell's own link may carry: https, a host, no user information. */
    static boolean httpsLink(String address) {
        if (address.length() > MOST_LINK) {
            return false;
        }
        try {
            URI uri = new URI(address);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && address.codePoints().noneMatch(Character::isISOControl);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The text without the characters XML 1.0 cannot hold. */
    static String clean(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(point -> point == 0x9 || point == 0xA || point == 0xD
                || point >= 0x20 && point <= 0xD7FF || point >= 0xE000 && point <= 0xFFFD
                || point >= 0x10000 && point <= 0x10FFFF).forEach(out::appendCodePoint);
        return out.toString();
    }
}
