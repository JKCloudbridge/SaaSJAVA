package app.platform.sheets.internal;

import app.platform.sheets.internal.formula.FormulaRefused;
import app.platform.sheets.internal.formula.FormulaTranslator;
import app.platform.sheets.internal.formula.XlError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads one worksheet of an .xlsx workbook into a {@link RawSheet} (SH1B, ADR-0253), with our guards first and the
 * library second. It replaces the reader of UI3B (ADR-0195) and keeps every one of its guards.
 *
 * <p><strong>The order.</strong> The announced size and the bytes read were counted by the caller. Then
 * {@link XlsxArchive} scans the archive (parts, duplicate and unsafe names, macros, sizes counted while inflating).
 * Then the styles and the theme are read by the library from the bytes ({@link StyleBook}), after our own count of
 * their elements. The worksheet itself is read by a stream reader of ours, with the document type refused, because
 * only a reader of ours can give the style of each cell, the panes, the conditional colours and the page set-up
 * together; the library's own worksheet reader is not used and neither is the whole-workbook object. What is not a
 * cell (check boxes, links, drawings) is read by {@link ExtrasReader} from the same bytes.
 *
 * <p><strong>Limits while reading.</strong> The number of cells, rows, columns, merged areas and conditional rules is
 * counted as they stream by; a text is cut one character above the limit of a cell, which is enough to refuse it; a
 * formula is checked for length (4,000) and nesting (32) as it arrives, so the library's formula parser (used from the
 * next release) never sees one it would overflow on. A formula comes in as its last value, with a note.
 *
 * <p>Nothing typed in the file is put in a message: a refusal names a limit by its number and a place by its row and
 * column. The name of a worksheet is the organization's text; it is returned to the person and never logged.
 */
final class WorkbookReader {

    private static final int MAX_COLUMN = 16_384;
    private static final int MAX_ROW = 1_048_576;
    private static final int MAX_STRINGS = 1_000_000;
    private static final int MAX_COLUMN_SPECS = 2_000;
    private static final int MAX_RULES_READ = 1_000;
    private static final int MAX_RULES_KEPT = 200;
    private static final int MAX_FORMULA_CHARS = 4_000;
    private static final int MAX_FORMULA_DEPTH = 32;
    private static final int MAX_TITLE_CHARS = 31;
    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_STRICT = "http://purl.oclc.org/ooxml/spreadsheetml/main";
    private static final DateTimeFormatter WITH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter WITH_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,15}(\\.[0-9]{1,10})?");
    private static final Pattern PRINT_ROWS = Pattern.compile("!\\$[0-9]+:\\$[0-9]+");
    private static final Set<Character> TITLE_FORBIDDEN = Set.of(':', '\\', '/', '?', '*', '[', ']');

    private final ImportProperties settings;
    private final SheetProperties.Limits limits;

    WorkbookReader(ImportProperties settings, SheetProperties.Limits limits) {
        this.settings = settings;
        this.limits = limits;
    }

    /**
     * One worksheet of the workbook.
     *
     * @param file the bytes of the file, already bounded by the caller
     * @param requested the worksheet to read, as its number among the visible ones counted from zero, or null for the
     *        first visible worksheet that holds anything
     */
    RawSheet read(byte[] file, Integer requested) {
        XlsxArchive.Parts parts = XlsxArchive.scan(file, settings);
        byte[] workbookPart = parts.part("xl/workbook.xml");
        if (workbookPart == null) {
            throw ImportRefusal.notXlsx();
        }
        Book book = book(workbookPart, parts.part("xl/_rels/workbook.xml.rels"));
        List<Entry> visible = book.visible();
        if (visible.isEmpty()) {
            throw new ImportRefusal("no_sheet", "The workbook has no sheet that can be read.");
        }
        byte[] stringPart = parts.part("xl/sharedStrings.xml");
        List<String> strings = stringPart == null ? List.of() : sharedStrings(stringPart);
        byte[] stylePart = parts.part("xl/styles.xml");
        StyleBook styles = stylePart == null ? StyleBook.none()
                : StyleBook.read(stylePart, parts.part("xl/theme/theme1.xml"), settings);

        RawSheet sheet;
        int chosen;
        if (requested != null) {
            if (requested < 0 || requested >= visible.size()) {
                throw new ImportRefusal("no_such_worksheet", "The worksheet that was chosen is not in the file.");
            }
            chosen = requested;
            sheet = readSheet(parts, book, visible.get(chosen), strings, styles);
        } else {
            sheet = null;
            chosen = 0;
            for (int i = 0; i < visible.size(); i++) {
                RawSheet found = readSheet(parts, book, visible.get(i), strings, styles);
                if (sheet == null || !found.isEmpty()) {
                    sheet = found;
                    chosen = i;
                }
                if (!found.isEmpty()) {
                    break;
                }
            }
        }
        finish(sheet, parts, book, visible, chosen);
        return sheet;
    }

    /** The notes that belong to the workbook, and the list of worksheets the person can choose from. */
    private static void finish(RawSheet sheet, XlsxArchive.Parts parts, Book book, List<Entry> visible, int chosen) {
        List<RawSheet.Worksheet> list = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            Entry entry = visible.get(i);
            byte[] part = parts.part(entry.part());
            int[] size = part == null ? new int[] {0, 0} : declared(part);
            list.add(new RawSheet.Worksheet(i, titleOf(entry.name()), size[0], size[1]));
        }
        sheet.worksheets = list;
        sheet.worksheet = chosen;
        sheet.title = list.get(chosen).title();
        ImportNote.Counts notes = sheet.notes;
        notes.add(ImportNote.OTHER_SHEETS, visible.size() - 1);
        notes.add(ImportNote.HIDDEN_SHEETS, book.hidden());
        notes.add(ImportNote.IMAGES, parts.pictures(false));
        notes.add(ImportNote.VECTOR_PICTURES, parts.pictures(true));
        notes.add(ImportNote.CHARTS, parts.charts());
        notes.add(ImportNote.EXTERNAL_LINKS, parts.externalLinks());
        notes.add(ImportNote.PIVOTS, parts.pivots());
        notes.add(ImportNote.COMMENT_PARTS, parts.comments());
        notes.add(ImportNote.DEFINED_NAMES, book.definedNames());
    }

    /** The name of a tab if it can be used as the title of a sheet, otherwise null. */
    static String titleOf(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_TITLE_CHARS || CellValues.unsafe(name)
                || name.chars().anyMatch(c -> TITLE_FORBIDDEN.contains((char) c)) || name.startsWith("'")
                || name.endsWith("'")) {
            return null;
        }
        return name;
    }

    // ---- the workbook ----

    private record Entry(String name, String part, int position) {
    }

    private record Book(List<Entry> visible, int hidden, int definedNames, boolean date1904,
            Map<Integer, Boolean> printTitles) {
    }

    private Book book(byte[] workbook, byte[] relationships) {
        Map<String, String> targets = relationships == null ? Map.of() : targets(relationships);
        List<Entry> visible = new ArrayList<>();
        int hidden = 0;
        int names = 0;
        int position = 0;
        boolean date1904 = false;
        Map<Integer, Boolean> printTitles = new HashMap<>();
        XMLStreamReader r = Xml.reader(workbook);
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String name = r.getLocalName();
                if ("workbookPr".equals(name)) {
                    date1904 = Xml.flag(r.getAttributeValue(null, "date1904"), false);
                } else if ("sheet".equals(name)) {
                    String id = Xml.relationshipId(r);
                    String state = r.getAttributeValue(null, "state");
                    String target = id == null ? null : targets.get(id);
                    if (state == null || "visible".equals(state)) {
                        if (target != null) {
                            visible.add(new Entry(r.getAttributeValue(null, "name"), target, position));
                        }
                    } else {
                        hidden++;
                    }
                    position++;
                    if (position > 10_000) {
                        throw ImportRefusal.tooLarge("sheets", "The workbook has more worksheets than can be read.");
                    }
                } else if ("definedName".equals(name)) {
                    names++;
                    String local = r.getAttributeValue(null, "localSheetId");
                    boolean titles = "_xlnm.Print_Titles".equals(r.getAttributeValue(null, "name"));
                    String text = r.getElementText();
                    if (titles && local != null && Xml.integer(local) != null) {
                        printTitles.merge(Xml.integer(local), PRINT_ROWS.matcher(text).find(), Boolean::logicalOr);
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        } finally {
            Xml.close(r);
        }
        return new Book(visible, hidden, names, date1904, printTitles);
    }

    /** The target of each relationship that points at a worksheet part inside the archive, by identifier. */
    private static Map<String, String> targets(byte[] relationships) {
        Map<String, String> out = new HashMap<>();
        XMLStreamReader r = Xml.reader(relationships);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())) {
                    String type = r.getAttributeValue(null, "Type");
                    String target = r.getAttributeValue(null, "Target");
                    String id = r.getAttributeValue(null, "Id");
                    if (id == null || target == null || type == null || !type.endsWith("worksheet")
                            || "External".equals(r.getAttributeValue(null, "TargetMode")) || target.contains("..")
                            || target.indexOf('\\') >= 0) {
                        continue;
                    }
                    out.put(id, target.startsWith("/") ? target.substring(1) : "xl/" + target);
                    if (out.size() > 20_000) {
                        throw ImportRefusal.tooLarge("sheets", "The workbook has more worksheets than can be read.");
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        } finally {
            Xml.close(r);
        }
        return out;
    }

    /**
     * The size a worksheet says it covers, as rows and columns, read from its {@code dimension} line without reading
     * the rest. It is what the file claims and is only shown to help the person choose, so a false claim harms
     * nothing; it is bounded to the size of a spreadsheet.
     */
    private static int[] declared(byte[] part) {
        XMLStreamReader r;
        try {
            r = Xml.reader(part);
        } catch (ImportRefusal e) {
            return new int[] {0, 0};
        }
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    if ("dimension".equals(r.getLocalName())) {
                        return span(r.getAttributeValue(null, "ref"));
                    }
                    if ("sheetData".equals(r.getLocalName())) {
                        break;
                    }
                }
            }
        } catch (XMLStreamException e) {
            return new int[] {0, 0};
        } finally {
            Xml.close(r);
        }
        return new int[] {0, 0};
    }

    private static int[] span(String ref) {
        if (ref == null) {
            return new int[] {0, 0};
        }
        int colon = ref.indexOf(':');
        int[] from = reference(colon < 0 ? ref : ref.substring(0, colon));
        int[] to = reference(colon < 0 ? ref : ref.substring(colon + 1));
        if (from == null || to == null || to[0] < from[0] || to[1] < from[1]) {
            return new int[] {0, 0};
        }
        return new int[] {to[0] - from[0] + 1, to[1] - from[1] + 1};
    }

    // ---- shared strings ----

    private List<String> sharedStrings(byte[] part) {
        List<String> strings = new ArrayList<>();
        XMLStreamReader r = Xml.reader(part);
        int phonetic = 0;
        boolean inText = false;
        StringBuilder current = null;
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if ("si".equals(name)) {
                        current = new StringBuilder();
                        if (strings.size() >= MAX_STRINGS) {
                            throw ImportRefusal.tooLarge("cells", "The file holds more texts than a sheet can be "
                                    + "made from.");
                        }
                    } else if ("rPh".equals(name)) {
                        phonetic++;
                    } else if ("t".equals(name) && phonetic == 0 && current != null) {
                        inText = true;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inText) {
                    append(current, r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    if ("t".equals(name)) {
                        inText = false;
                    } else if ("rPh".equals(name)) {
                        phonetic--;
                    } else if ("si".equals(name) && current != null) {
                        strings.add(current.toString());
                        current = null;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw ImportRefusal.notXlsx();
        } finally {
            Xml.close(r);
        }
        return strings;
    }

    /** Appends, never holding more than one character above the longest text a fixed cell may have. */
    private void append(StringBuilder to, String text) {
        int room = Math.max(limits.maxCellChars(), limits.maxFixedTextChars()) + 1 - to.length();
        if (room > 0) {
            to.append(text, 0, Math.min(room, text.length()));
        }
    }

    // ---- the worksheet ----

    private RawSheet readSheet(XlsxArchive.Parts parts, Book book, Entry entry, List<String> strings,
            StyleBook styles) {
        byte[] part = parts.part(entry.part());
        if (part == null) {
            throw new ImportRefusal("no_sheet", "The workbook has no sheet that can be read.");
        }
        RawSheet sheet = new RawSheet();
        new Pass(sheet, strings, styles, book.date1904()).run(part);
        ExtrasReader.read(parts, entry.part(), sheet, limits);
        if (Boolean.TRUE.equals(book.printTitles().get(entry.position()))) {
            RawSheet.Print print = sheet.print;
            sheet.print = print == null ? new RawSheet.Print("A4", "PORTRAIT", false, true)
                    : new RawSheet.Print(print.paper(), print.orientation(), print.fitToWidth(), true);
        }
        return sheet;
    }

    /** The cell being read: a mutable holder, so the loop stays one flat reading of events. */
    private static final class Pending {
        boolean inCell;
        boolean inValue;
        boolean inInline;
        boolean inInlineText;
        boolean inFormula;
        boolean formula;
        String formulaType;
        String formulaShare;
        final StringBuilder formulaText = new StringBuilder();
        int phonetic;
        String type;
        int style = -1;
        int row;
        int column;
        final StringBuilder value = new StringBuilder();
        final StringBuilder inline = new StringBuilder();

        void begin(String cellType, int cellStyle, int cellRow, int cellColumn) {
            inCell = true;
            formula = false;
            inFormula = false;
            formulaType = null;
            formulaShare = null;
            formulaText.setLength(0);
            value.setLength(0);
            inline.setLength(0);
            type = cellType;
            style = cellStyle;
            row = cellRow;
            column = cellColumn;
        }
    }

    /** A conditional format being read. */
    private static final class Conditional {
        String sqref;
        String type;
        String operator;
        int dxf = -1;
        final List<String> formulas = new ArrayList<>();
        boolean inFormula;
        final StringBuilder formula = new StringBuilder();
    }

    /** One pass over the XML of one worksheet. */
    private final class Pass {
        private final RawSheet sheet;
        private final List<String> strings;
        private final StyleBook styles;
        private final boolean date1904;
        private final Set<Integer> fallbackFonts = new HashSet<>();
        private int read;
        private int rulesRead;
        private Conditional rule;

        Pass(RawSheet sheet, List<String> strings, StyleBook styles, boolean date1904) {
            this.sheet = sheet;
            this.strings = strings;
            this.styles = styles;
            this.date1904 = date1904;
        }

        void run(byte[] part) {
            XMLStreamReader r = Xml.reader(part);
            int row = -1;
            int lastColumn = -1;
            Pending cell = new Pending();
            FormulaScan scan = new FormulaScan();
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String name = r.getLocalName();
                        switch (name) {
                            case "row" -> {
                                read = counted(read);
                                Integer at = Xml.integer(r.getAttributeValue(null, "r"));
                                row = at == null ? row + 1 : at - 1;
                                if (row < 0 || row >= MAX_ROW) {
                                    throw ImportRefusal.notXlsx();
                                }
                                lastColumn = -1;
                                rowSpec(r, row);
                            }
                            case "c" -> {
                                read = counted(read);
                                Integer s = Xml.integer(r.getAttributeValue(null, "s"));
                                int[] at = reference(r.getAttributeValue(null, "r"));
                                int cellRow = at == null ? Math.max(row, 0) : at[0];
                                int cellColumn = at == null ? lastColumn + 1 : at[1];
                                if (cellColumn >= MAX_COLUMN) {
                                    throw ImportRefusal.notXlsx();
                                }
                                lastColumn = cellColumn;
                                cell.begin(r.getAttributeValue(null, "t"), s == null ? -1 : s, cellRow, cellColumn);
                            }
                            case "v" -> cell.inValue = cell.inCell;
                            case "f" -> {
                                if (cell.inCell) {
                                    cell.formula = true;
                                    cell.inFormula = true;
                                    cell.formulaType = r.getAttributeValue(null, "t");
                                    cell.formulaShare = r.getAttributeValue(null, "si");
                                    cell.formulaText.setLength(0);
                                    scan.reset();
                                }
                            }
                            case "is" -> cell.inInline = cell.inCell;
                            case "rPh" -> cell.phonetic++;
                            case "t" -> cell.inInlineText = cell.inInline && cell.phonetic == 0;
                            case "col" -> {
                                read = counted(read);
                                column(r);
                            }
                            case "mergeCell" -> {
                                read = counted(read);
                                merge(r.getAttributeValue(null, "ref"));
                            }
                            case "sheetFormatPr" -> {
                                Double rowHeight = Xml.number(r.getAttributeValue(null, "defaultRowHeight"));
                                Double columnWidth = Xml.number(r.getAttributeValue(null, "defaultColWidth"));
                                Double base = Xml.number(r.getAttributeValue(null, "baseColWidth"));
                                sheet.defaultRowPoints = rowHeight != null && rowHeight > 0 ? rowHeight : 15;
                                sheet.defaultColumnChars = columnWidth != null && columnWidth > 0 ? columnWidth
                                        : Math.floor(((base == null ? 8 : base) * 7 + 5) / 7 * 256) / 256;
                            }
                            case "sheetView" -> { }
                            case "pane" -> {
                                if (isMain(r)) {
                                    pane(r);
                                }
                            }
                            case "pageSetUpPr" -> {
                                if (isMain(r) && Xml.flag(r.getAttributeValue(null, "fitToPage"), false)) {
                                    fitPage = true;
                                    sheet.print = new RawSheet.Print("A4", "PORTRAIT", true, false);
                                }
                            }
                            case "pageSetup" -> {
                                if (isMain(r)) {
                                    pageSetup(r);
                                }
                            }
                            case "conditionalFormatting" -> {
                                if (isMain(r)) {
                                    rule = null;
                                    sqrefOfBlock = r.getAttributeValue(null, "sqref");
                                }
                            }
                            case "cfRule" -> {
                                if (isMain(r)) {
                                    rule = new Conditional();
                                    rule.sqref = sqrefOfBlock;
                                    rule.type = r.getAttributeValue(null, "type");
                                    rule.operator = r.getAttributeValue(null, "operator");
                                    Integer dxf = Xml.integer(r.getAttributeValue(null, "dxfId"));
                                    rule.dxf = dxf == null ? -1 : dxf;
                                }
                            }
                            case "formula" -> {
                                if (rule != null && isMain(r)) {
                                    rule.inFormula = true;
                                    rule.formula.setLength(0);
                                }
                            }
                            default -> {
                            }
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS) {
                        if (cell.inValue) {
                            append(cell.value, r.getText());
                        } else if (cell.inInlineText) {
                            append(cell.inline, r.getText());
                        } else if (cell.inFormula) {
                            scan.add(r.getText(), cell.row, cell.column);
                            cell.formulaText.append(r.getText());
                        } else if (rule != null && rule.inFormula && rule.formula.length() < 64) {
                            rule.formula.append(r.getText(), 0, Math.min(64, r.getText().length()));
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "v" -> cell.inValue = false;
                            case "f" -> {
                                cell.inFormula = false;
                                sheet.longestFormula = Math.max(sheet.longestFormula, scan.length());
                                sheet.deepestFormula = Math.max(sheet.deepestFormula, scan.deepest());
                            }
                            case "t" -> cell.inInlineText = false;
                            case "is" -> cell.inInline = false;
                            case "rPh" -> cell.phonetic--;
                            case "c" -> {
                                cell.inCell = false;
                                finish(cell);
                            }
                            case "formula" -> {
                                if (rule != null) {
                                    rule.inFormula = false;
                                    rule.formulas.add(rule.formula.toString().strip());
                                }
                            }
                            case "cfRule" -> {
                                if (rule != null) {
                                    conditional(rule);
                                    rule = null;
                                }
                            }
                            default -> {
                            }
                        }
                    }
                }
            } catch (XMLStreamException e) {
                throw ImportRefusal.notXlsx();
            } finally {
                Xml.close(r);
            }
            sheet.notes.add(ImportNote.FONT_FALLBACK, fallbackFonts.size());
        }

        private String sqrefOfBlock;

        private int counted(int count) {
            if (count + 1 > settings.maxCells()) {
                throw ImportRefusal.tooLarge("cells", "The sheet holds more than " + settings.maxCells()
                        + " cells and rows, which is more than can be read.");
            }
            return count + 1;
        }

        private void rowSpec(XMLStreamReader r, int row) {
            Double height = Xml.number(r.getAttributeValue(null, "ht"));
            boolean custom = Xml.flag(r.getAttributeValue(null, "customHeight"), false);
            boolean hidden = Xml.flag(r.getAttributeValue(null, "hidden"), false);
            if (height != null && height > 0 && height < 5_000) {
                sheet.rowSpecs.put(row, new RawSheet.RowSpec(height, hidden, custom));
                if (custom) {
                    sheet.heights.put(row, Xml.clamp((int) Math.round(height * 4 / 3), 16, 400));
                }
            } else if (hidden) {
                sheet.rowSpecs.put(row, new RawSheet.RowSpec(sheet.defaultRowPoints, true, false));
            }
        }

        private void column(XMLStreamReader r) {
            Integer first = Xml.integer(r.getAttributeValue(null, "min"));
            Integer last = Xml.integer(r.getAttributeValue(null, "max"));
            Double width = Xml.number(r.getAttributeValue(null, "width"));
            if (first == null || last == null || first < 1 || last < first) {
                return;
            }
            boolean custom = Xml.flag(r.getAttributeValue(null, "customWidth"), false);
            boolean hidden = Xml.flag(r.getAttributeValue(null, "hidden"), false);
            if (width != null && width >= 0 && width < 1_000) {
                if (sheet.columnSpecs.size() >= MAX_COLUMN_SPECS) {
                    throw ImportRefusal.tooLarge("cells", "The sheet states more column widths than can be read.");
                }
                sheet.columnSpecs.add(new RawSheet.ColumnSpec(first - 1, Math.min(last, MAX_COLUMN) - 1, width, hidden,
                        custom));
                if (custom) {
                    sheet.widths.add(new RawSheet.Width(first - 1, Math.min(last, MAX_COLUMN) - 1,
                            Xml.clamp((int) Math.round(width * 7 + 5), 40, 1000)));
                }
            }
        }

        private void merge(String ref) {
            if (ref == null) {
                return;
            }
            int colon = ref.indexOf(':');
            if (colon < 0) {
                return;
            }
            int[] from = reference(ref.substring(0, colon));
            int[] to = reference(ref.substring(colon + 1));
            if (from == null || to == null || to[0] < from[0] || to[1] < from[1]) {
                return;
            }
            if (sheet.merges.size() >= limits.maxMerges()) {
                throw new ImportRefusal("too_many_merges", "The sheet has more merged areas than the allowed "
                        + limits.maxMerges() + ".");
            }
            sheet.merges.add(new RawSheet.Area(from[0], from[1], to[0], to[1]));
        }

        private void pane(XMLStreamReader r) {
            if (sawFrozen) {
                return;
            }
            String state = r.getAttributeValue(null, "state");
            if (!"frozen".equals(state) && !"frozenSplit".equals(state)) {
                return;
            }
            sawFrozen = true;
            Double x = Xml.number(r.getAttributeValue(null, "xSplit"));
            Double y = Xml.number(r.getAttributeValue(null, "ySplit"));
            sheet.freezeColumns = x == null ? 0 : (int) Math.max(0, Math.min(MAX_COLUMN, x));
            sheet.freezeRows = y == null ? 0 : (int) Math.max(0, Math.min(MAX_ROW, y));
        }

        private boolean sawFrozen;

        private boolean fitPage;

        private void pageSetup(XMLStreamReader r) {
            Integer paper = Xml.integer(r.getAttributeValue(null, "paperSize"));
            String kind = paper == null ? "A4" : switch (paper) {
                case 8 -> "A3";
                case 1, 2 -> "LETTER";
                default -> "A4";
            };
            String orientation = "landscape".equals(r.getAttributeValue(null, "orientation")) ? "LANDSCAPE"
                    : "PORTRAIT";
            Integer width = Xml.integer(r.getAttributeValue(null, "fitToWidth"));
            boolean fitToWidth = fitPage && (width == null || width != 0);
            sheet.print = new RawSheet.Print(kind, orientation, fitToWidth, false);
        }

        private boolean isMain(XMLStreamReader r) {
            String uri = r.getNamespaceURI();
            return uri == null || uri.isEmpty() || NS_MAIN.equals(uri) || NS_STRICT.equals(uri);
        }

        // ---- conditional colours ----

        private void conditional(Conditional found) {
            rulesRead++;
            if (rulesRead > MAX_RULES_READ) {
                sheet.notes.add(ImportNote.CONDITIONS_LEFT_OUT);
                return;
            }
            String operator = operatorOf(found);
            StyleBook.Paint paint = found.dxf < 0 ? null : styles.paint(found.dxf);
            List<String> operands = new ArrayList<>();
            for (String formula : found.formulas) {
                String number = number(formula);
                if (number != null) {
                    operands.add(number);
                }
            }
            boolean between = "BETWEEN".equals(operator) || "NOT_BETWEEN".equals(operator);
            if (!"cellIs".equals(found.type) || operator == null || paint == null || found.sqref == null
                    || operands.size() != found.formulas.size() || operands.size() != (between ? 2 : 1)) {
                sheet.notes.add(ImportNote.CONDITIONS_LEFT_OUT);
                return;
            }
            for (String range : found.sqref.trim().split("\\s+")) {
                int colon = range.indexOf(':');
                int[] from = reference(colon < 0 ? range : range.substring(0, colon));
                int[] to = reference(colon < 0 ? range : range.substring(colon + 1));
                if (from == null || to == null || to[0] < from[0] || to[1] < from[1]
                        || sheet.rules.size() >= MAX_RULES_KEPT) {
                    sheet.notes.add(ImportNote.CONDITIONS_LEFT_OUT);
                    continue;
                }
                sheet.rules.add(new RawSheet.Rule(from[0], from[1], to[0], to[1], operator, operands.get(0),
                        between ? operands.get(1) : null, paint.fill(), paint.colour()));
            }
        }

        private String operatorOf(Conditional found) {
            if (found.operator == null) {
                return null;
            }
            return switch (found.operator) {
                case "lessThan" -> "LT";
                case "lessThanOrEqual" -> "LTE";
                case "equal" -> "EQ";
                case "notEqual" -> "NEQ";
                case "greaterThanOrEqual" -> "GTE";
                case "greaterThan" -> "GT";
                case "between" -> "BETWEEN";
                case "notBetween" -> "NOT_BETWEEN";
                default -> null;
            };
        }

        /** A plain number written as a formula operand, in its one form, or null when it is anything else. */
        private String number(String formula) {
            if (!NUMBER.matcher(formula).matches()) {
                return null;
            }
            return new BigDecimal(formula).stripTrailingZeros().toPlainString();
        }

        // ---- a cell ----

        /** The first cell of a shared formula: only it holds the text, the others are moved copies of it. */
        private record Master(int row, int column, String text) {
        }

        private final Map<String, Master> masters = new HashMap<>();

        /** What the file stores in the cell, as a value, or null when it stores nothing. */
        private Object typedValue(Pending cell, String type, String value) {
            if ("s".equals(type)) {
                Integer at = Xml.integer(value);
                return at == null || at < 0 || at >= strings.size() ? null : strings.get(at);
            }
            if ("inlineStr".equals(type)) {
                return cell.inline.toString();
            }
            if ("e".equals(type)) {
                return new XlError(value.strip());
            }
            if ("b".equals(type)) {
                return value.isBlank() ? null : (Object) "1".equals(value.strip());
            }
            if ("str".equals(type) || "d".equals(type)) {
                return value.isEmpty() && !cell.formula ? null : value;
            }
            Double number = Xml.number(value.strip());
            return number == null ? null : (Object) number;
        }

        /** Keeps the formula of the cell (SH2): the text, with a shared group's text given to each member. */
        private void keepFormula(Pending cell) {
            String text = cell.formulaText.toString().strip();
            String kind = cell.formulaType;
            RawSheet.Formula formula;
            if ("array".equals(kind) || "dataTable".equals(kind)) {
                formula = new RawSheet.Formula(null, "array");
            } else if ("shared".equals(kind) && cell.formulaShare != null) {
                if (!text.isEmpty()) {
                    masters.put(cell.formulaShare, new Master(cell.row, cell.column, text));
                    formula = new RawSheet.Formula(text, null);
                } else {
                    Master master = masters.get(cell.formulaShare);
                    formula = master == null ? new RawSheet.Formula(null, "shared") : moved(master, cell);
                }
            } else if (text.isEmpty()) {
                formula = new RawSheet.Formula(null, "empty");
            } else {
                formula = new RawSheet.Formula(text, null);
            }
            sheet.formulas.computeIfAbsent(cell.row, key -> new TreeMap<>()).put(cell.column, formula);
        }

        private RawSheet.Formula moved(Master master, Pending cell) {
            try {
                return new RawSheet.Formula(FormulaTranslator.shift(master.text(), cell.row - master.row(),
                        cell.column - master.column()), null);
            } catch (FormulaRefused refused) {
                return new RawSheet.Formula(null, "shared");
            }
        }

        private void finish(Pending cell) {
            String value = cell.value.toString();
            String type = cell.type;
            if (cell.formula) {
                keepFormula(cell);
            }
            Object typed = typedValue(cell, type, value);
            if (typed != null) {
                sheet.typed.computeIfAbsent(cell.row, key -> new TreeMap<>()).put(cell.column, typed);
            }
            StyleBook.Format format = styles.at(Math.max(cell.style, 0));
            String text;
            if ("s".equals(type)) {
                Integer at = Xml.integer(value);
                text = at == null || at < 0 || at >= strings.size() ? null : strings.get(at);
            } else if ("inlineStr".equals(type)) {
                text = cell.inline.toString();
            } else if ("e".equals(type)) {
                sheet.notes.add(ImportNote.ERROR_CELLS);
                keepBlank(cell, format);
                return;
            } else if ("b".equals(type)) {
                text = value.isBlank() ? null : "1".equals(value.trim()) ? "TRUE" : "FALSE";
            } else if ("str".equals(type) || "d".equals(type)) {
                text = value;
            } else {
                text = numberText(value, format);
                if (text != null && format.unknownNumberFormat() && !format.date()) {
                    sheet.notes.add(ImportNote.NUMBER_FORMAT_UNKNOWN);
                }
            }
            if (text == null || text.isBlank()) {
                keepBlank(cell, format);
                return;
            }
            if (cell.formula) {
                sheet.notes.add(ImportNote.FORMULAS);
            }
            if (format.fontFallback()) {
                fallbackFonts.add(format.fontId());
            }
            sheet.put(cell.row, cell.column, new RawSheet.Cell(text, format.bold(), format.italic(), format.points(),
                    format));
        }

        /**
         * A cell with no text but a border on some side is part of the table (FA14, ADR-0250). A column past the
         * sheet's limit is refused at once, with the number, so the file cannot make this reader hold more than that.
         * Its look is kept when it shows something.
         */
        private void keepBlank(Pending cell, StyleBook.Format format) {
            if (format.bordered()) {
                if (cell.column >= limits.maxColumns()) {
                    throw SheetImportBuilder.tooManyColumns(cell.column + 1, limits.maxColumns());
                }
                sheet.border(cell.row, cell.column);
            }
            if (format.visible() && cell.column < limits.maxColumns()) {
                sheet.look(cell.row, cell.column, format);
                if (format.fontFallback()) {
                    fallbackFonts.add(format.fontId());
                }
            }
        }

        /** A number as the text of its digits, or as a date when its style shows one. */
        private String numberText(String value, StyleBook.Format format) {
            String text = value.trim();
            if (text.isEmpty()) {
                return null;
            }
            if (format.date()) {
                Double serial = Xml.number(text);
                String date = serial == null ? null : date(serial, date1904);
                if (date != null) {
                    sheet.notes.add(ImportNote.DATES);
                    return date;
                }
            }
            return text;
        }
    }

    /** Checks a formula as it streams past: its length and the depth of its parentheses, before anything parses it. */
    private static final class FormulaScan {
        private int length;
        private int depth;
        private int deepest;
        private boolean quoted;

        void reset() {
            length = 0;
            depth = 0;
            deepest = 0;
            quoted = false;
        }

        int length() {
            return length;
        }

        int deepest() {
            return deepest;
        }

        void add(String text, int row, int column) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (++length > MAX_FORMULA_CHARS) {
                    throw new ImportRefusal("formula_too_long", where(row, column) + " has a formula longer than the "
                            + "allowed " + MAX_FORMULA_CHARS + " characters.");
                }
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted && c == '(') {
                    deepest = Math.max(deepest, depth + 1);
                    if (++depth > MAX_FORMULA_DEPTH) {
                        throw new ImportRefusal("formula_too_deep", where(row, column) + " has a formula nested "
                                + "deeper than the allowed " + MAX_FORMULA_DEPTH + " levels.");
                    }
                } else if (!quoted && c == ')' && depth > 0) {
                    depth--;
                }
            }
        }

        private static String where(int row, int column) {
            return "Row " + (row + 1) + ", column " + SheetImportBuilder.letters(column);
        }
    }

    /** The date (and time, when there is one) of a spreadsheet serial number, or null when it is out of range. */
    static String date(double serial, boolean date1904) {
        if (serial < 0 || serial >= 2_958_466) {
            return null;
        }
        long days = (long) Math.floor(serial);
        long seconds = Math.round((serial - days) * 86_400);
        if (seconds >= 86_400) {
            days++;
            seconds -= 86_400;
        }
        if (days == 0 && seconds != 0 && !date1904) {
            return String.format(Locale.ROOT, "%02d:%02d", seconds / 3600, seconds / 60 % 60)
                    + (seconds % 60 == 0 ? "" : String.format(Locale.ROOT, ":%02d", seconds % 60));
        }
        LocalDate base = date1904 ? LocalDate.of(1904, 1, 1)
                : days < 60 ? LocalDate.of(1899, 12, 31) : LocalDate.of(1899, 12, 30);
        LocalDate day = base.plusDays(days);
        if (seconds == 0) {
            return day.toString();
        }
        LocalDateTime at = day.atStartOfDay().plusSeconds(seconds);
        return (seconds % 60 == 0 ? WITH_TIME : WITH_SECONDS).format(at);
    }

    /** A cell reference such as {@code B12} as zero based row and column, or null when it is not one. */
    static int[] reference(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        int i = 0;
        long column = 0;
        while (i < ref.length() && ref.charAt(i) >= 'A' && ref.charAt(i) <= 'Z') {
            column = column * 26 + (ref.charAt(i) - 'A' + 1);
            if (column > MAX_COLUMN) {
                return null;
            }
            i++;
        }
        if (i == 0 || i == ref.length() || ref.length() - i > 7) {
            return null;
        }
        long row = 0;
        for (; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            row = row * 10 + (c - '0');
        }
        if (row < 1 || row > MAX_ROW) {
            return null;
        }
        return new int[] {(int) row - 1, (int) column - 1};
    }
}
