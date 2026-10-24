package app.platform.sheets.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the first sheet of an .xlsx workbook into a {@link RawSheet} (UI3B, ADR-0195), with the JDK's own zip and XML
 * readers and no library: the format is a zip archive of XML parts and only a handful of them hold what a sheet
 * needs, so every limit can be kept here, while reading, and nothing of the library's format can reach the database
 * or the API (ADR-0186).
 *
 * <p><strong>Hostile files.</strong> The archive is read in one pass from memory (the caller has bounded the file).
 * The number of parts, the size each part inflates to and the size of the whole archive are counted while the bytes
 * are inflated, for the parts that are skipped as well as the ones that are read, so a zip bomb is stopped at the
 * limit and never inflated in full. A part's name is only compared with a fixed list: it is never used as a path and
 * nothing is written anywhere. The XML is read as a stream with the document type declaration switched off, no
 * external entity or schema ever resolved and nothing fetched. Macros and links to other files are never run or
 * followed: a workbook with macros is refused, a link is counted and left out. A text is cut at one character above
 * the limit of a cell, which is enough to refuse it and keeps what is held bounded.
 *
 * <p>Nothing typed in the file is put in a message: a refusal names a limit by its number and a place by its row and
 * column.
 */
final class XlsxReader {

    private static final int MAX_COLUMN = 16_384;
    private static final int MAX_ROW = 1_048_576;
    private static final int MAX_STRINGS = 1_000_000;
    private static final String SHEETS = "xl/worksheets/";
    private static final Set<String> WANTED = Set.of("[Content_Types].xml", "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels", "xl/sharedStrings.xml", "xl/styles.xml");
    private static final Set<Integer> BUILT_IN_DATES = Set.of(14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 28, 29, 30, 31,
            32, 33, 34, 35, 36, 45, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58);
    private static final DateTimeFormatter WITH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter WITH_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ImportProperties settings;
    private final SheetProperties.Limits limits;

    XlsxReader(ImportProperties settings, SheetProperties.Limits limits) {
        this.settings = settings;
        this.limits = limits;
    }

    /** The first sheet of the workbook. */
    RawSheet read(byte[] file) {
        if (file.length >= 4 && (file[0] & 0xff) == 0xd0 && (file[1] & 0xff) == 0xcf && (file[2] & 0xff) == 0x11
                && (file[3] & 0xff) == 0xe0) {
            throw new ImportRefusal("old_format", "This is an older spreadsheet format or a file protected by a "
                    + "password. Save it as .xlsx without a password and upload that.");
        }
        if (file.length < 4 || file[0] != 'P' || file[1] != 'K' || file[2] != 3 || file[3] != 4) {
            throw notXlsx();
        }
        RawSheet sheet = new RawSheet();
        Map<String, byte[]> parts = unzip(file, sheet);
        byte[] types = parts.get("[Content_Types].xml");
        if (types != null && new String(types, StandardCharsets.ISO_8859_1).contains("macroEnabled")) {
            throw macros();
        }
        byte[] workbook = parts.get("xl/workbook.xml");
        if (workbook == null) {
            throw notXlsx();
        }
        Workbook book = workbook(workbook, parts.get("xl/_rels/workbook.xml.rels"));
        byte[] sheetPart = book.target() == null ? null : parts.get(book.target());
        if (sheetPart == null) {
            throw new ImportRefusal("no_sheet", "The workbook has no sheet that can be read.");
        }
        sheet.notes.add(ImportNote.OTHER_SHEETS, book.sheets() - 1);
        byte[] stringPart = parts.get("xl/sharedStrings.xml");
        List<String> strings = stringPart == null ? List.of() : sharedStrings(stringPart);
        byte[] stylePart = parts.get("xl/styles.xml");
        Styles styles = stylePart == null ? Styles.NONE : styles(stylePart);
        worksheet(sheetPart, strings, styles, book.date1904(), sheet);
        return sheet;
    }

    // ---- the archive ----

    private Map<String, byte[]> unzip(byte[] file, RawSheet sheet) {
        Map<String, byte[]> parts = new HashMap<>();
        long inflated = 0;
        long retained = 0;
        int entries = 0;
        int images = 0;
        int charts = 0;
        int links = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                entries++;
                if (entries > settings.maxEntries()) {
                    throw tooLarge("entries", "The file has more than " + settings.maxEntries()
                            + " parts, which is more than a workbook is allowed to have.");
                }
                String name = entry.getName();
                if (name.endsWith("vbaProject.bin")) {
                    throw macros();
                }
                if (name.startsWith("xl/media/") && !entry.isDirectory()) {
                    images++;
                } else if (name.startsWith("xl/charts/chart") && name.endsWith(".xml")) {
                    charts++;
                } else if (name.startsWith("xl/externalLinks/externalLink") && name.endsWith(".xml")) {
                    links++;
                }
                boolean wanted = !entry.isDirectory() && !parts.containsKey(name) && (WANTED.contains(name)
                        || name.startsWith(SHEETS) && name.endsWith(".xml") && name.indexOf('/', SHEETS.length()) < 0);
                ByteArrayOutputStream keep = wanted ? new ByteArrayOutputStream() : null;
                long partSize = 0;
                int n = zip.read(buffer);
                while (n > 0) {
                    partSize += n;
                    inflated += n;
                    if (partSize > settings.maxPartBytes() || inflated > settings.maxInflatedBytes()) {
                        throw unpackedTooLarge();
                    }
                    if (keep != null) {
                        keep.write(buffer, 0, n);
                    }
                    n = zip.read(buffer);
                }
                if (keep != null) {
                    retained += partSize;
                    if (retained > 2L * settings.maxPartBytes()) {
                        throw unpackedTooLarge();
                    }
                    parts.put(name, keep.toByteArray());
                }
                entry = zip.getNextEntry();
            }
        } catch (IOException | IllegalArgumentException e) {
            // The library's message is not repeated: it can quote the name of a part.
            throw notXlsx();
        }
        sheet.notes.add(ImportNote.IMAGES, images);
        sheet.notes.add(ImportNote.CHARTS, charts);
        sheet.notes.add(ImportNote.EXTERNAL_LINKS, links);
        return parts;
    }

    private static ImportRefusal macros() {
        return new ImportRefusal("macros", "The workbook contains macros, which are never read. Save a copy as "
                + ".xlsx without macros and upload that.");
    }

    private static ImportRefusal notXlsx() {
        return new ImportRefusal("not_xlsx", "The file cannot be read as an .xlsx workbook, whatever its name says.");
    }

    private static ImportRefusal unpackedTooLarge() {
        return tooLarge("inflated", "The file is larger than it is allowed to be once it is unpacked.");
    }

    private static ImportRefusal tooLarge(String code, String words) {
        return new ImportRefusal(ImportRefusal.Kind.TOO_LARGE, code, words);
    }

    // ---- the workbook ----

    private record Workbook(String target, int sheets, boolean date1904) {
    }

    private Workbook workbook(byte[] workbook, byte[] relationships) {
        String firstVisible = null;
        String first = null;
        int sheets = 0;
        boolean date1904 = false;
        XMLStreamReader r = xml(workbook);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if ("workbookPr".equals(name)) {
                        date1904 = flag(r.getAttributeValue(null, "date1904"), false);
                    } else if ("sheet".equals(name)) {
                        sheets++;
                        String id = relationshipId(r);
                        String state = r.getAttributeValue(null, "state");
                        if (first == null) {
                            first = id;
                        }
                        if (firstVisible == null && (state == null || "visible".equals(state))) {
                            firstVisible = id;
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw notXlsx();
        } finally {
            close(r);
        }
        String id = firstVisible != null ? firstVisible : first;
        return new Workbook(id == null || relationships == null ? null : targetOf(relationships, id), sheets,
                date1904);
    }

    private static String relationshipId(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if ("id".equals(r.getAttributeLocalName(i))) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    private static String targetOf(byte[] relationships, String id) {
        XMLStreamReader r = xml(relationships);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())
                        && id.equals(r.getAttributeValue(null, "Id"))) {
                    String target = r.getAttributeValue(null, "Target");
                    if (target == null || target.contains("..")) {
                        return null;
                    }
                    return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                }
            }
        } catch (XMLStreamException e) {
            throw notXlsx();
        } finally {
            close(r);
        }
        return null;
    }

    // ---- shared strings and styles ----

    private List<String> sharedStrings(byte[] part) {
        List<String> strings = new ArrayList<>();
        XMLStreamReader r = xml(part);
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
                            throw tooLarge("cells", "The file holds more texts than a sheet can be made from.");
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
            throw notXlsx();
        } finally {
            close(r);
        }
        return strings;
    }

    /** Appends, never holding more than one character above the limit of a cell. */
    private void append(StringBuilder to, String text) {
        int room = limits.maxCellChars() + 1 - to.length();
        if (room > 0) {
            to.append(text, 0, Math.min(room, text.length()));
        }
    }

    private record Font(boolean bold, boolean italic, int points) {
    }

    /** What a cell style says that a sheet keeps: bold, italic, a size, and whether the number is a date. */
    private record Styles(List<Font> fonts, List<Integer> fontOf, List<Boolean> date) {

        static final Styles NONE = new Styles(List.of(), List.of(), List.of());

        Font fontFor(int style) {
            if (style < 0 || style >= fontOf.size()) {
                return null;
            }
            int font = fontOf.get(style);
            return font >= 0 && font < fonts.size() ? fonts.get(font) : null;
        }

        boolean isDate(int style) {
            return style >= 0 && style < date.size() && date.get(style);
        }
    }

    private static Styles styles(byte[] part) {
        Map<Integer, String> formats = new HashMap<>();
        List<Font> fonts = new ArrayList<>();
        List<Integer> fontOf = new ArrayList<>();
        List<Integer> formatOf = new ArrayList<>();
        boolean inFonts = false;
        boolean inXfs = false;
        boolean inFont = false;
        boolean bold = false;
        boolean italic = false;
        int points = 0;
        XMLStreamReader r = xml(part);
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "numFmt" -> {
                            Integer id = integer(r.getAttributeValue(null, "numFmtId"));
                            String code = r.getAttributeValue(null, "formatCode");
                            if (id != null && code != null && formats.size() < 1_000) {
                                formats.put(id, code);
                            }
                        }
                        case "fonts" -> inFonts = true;
                        case "font" -> {
                            inFont = inFonts;
                            bold = false;
                            italic = false;
                            points = 0;
                        }
                        case "b" -> bold = inFont && flag(r.getAttributeValue(null, "val"), true);
                        case "i" -> italic = inFont && flag(r.getAttributeValue(null, "val"), true);
                        case "sz" -> {
                            Double size = number(r.getAttributeValue(null, "val"));
                            points = inFont && size != null ? (int) Math.round(size) : 0;
                        }
                        case "cellXfs" -> inXfs = true;
                        case "xf" -> {
                            if (inXfs && fontOf.size() < 100_000) {
                                Integer font = integer(r.getAttributeValue(null, "fontId"));
                                Integer format = integer(r.getAttributeValue(null, "numFmtId"));
                                fontOf.add(font == null ? -1 : font);
                                formatOf.add(format == null ? 0 : format);
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    if ("font".equals(name) && inFont) {
                        if (fonts.size() < 10_000) {
                            fonts.add(new Font(bold, italic, points));
                        }
                        inFont = false;
                    } else if ("fonts".equals(name)) {
                        inFonts = false;
                    } else if ("cellXfs".equals(name)) {
                        inXfs = false;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw notXlsx();
        } finally {
            close(r);
        }
        List<Boolean> date = new ArrayList<>();
        for (int format : formatOf) {
            String code = formats.get(format);
            date.add(code == null ? BUILT_IN_DATES.contains(format) : isDateFormat(code));
        }
        return new Styles(fonts, fontOf, date);
    }

    /** Whether a number format shows a date or a time: it names a day, month, year, hour or second outside quotes. */
    static boolean isDateFormat(String code) {
        StringBuilder bare = new StringBuilder();
        boolean quoted = false;
        boolean bracket = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quoted) {
                quoted = c != '"';
            } else if (bracket) {
                bracket = c != ']';
            } else if (c == '"') {
                quoted = true;
            } else if (c == '[') {
                bracket = true;
            } else if (c == '\\' || c == '_' || c == '*') {
                i++;
            } else {
                bare.append(Character.toLowerCase(c));
            }
        }
        String text = bare.toString();
        return text.indexOf('d') >= 0 || text.indexOf('y') >= 0 || text.indexOf('h') >= 0
                || text.indexOf('s') >= 0 || text.indexOf('m') >= 0;
    }

    // ---- the sheet ----

    /** The cell being read: a mutable holder, so the loop below stays one flat reading of events. */
    private static final class Pending {
        boolean inCell;
        boolean inValue;
        boolean inInline;
        boolean inInlineText;
        boolean formula;
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
            value.setLength(0);
            inline.setLength(0);
            type = cellType;
            style = cellStyle;
            row = cellRow;
            column = cellColumn;
        }
    }

    private void worksheet(byte[] part, List<String> strings, Styles styles, boolean date1904, RawSheet sheet) {
        XMLStreamReader r = xml(part);
        int read = 0;
        int row = -1;
        int lastColumn = -1;
        Pending cell = new Pending();
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> {
                            read = counted(read);
                            Integer at = integer(r.getAttributeValue(null, "r"));
                            row = at == null ? row + 1 : at - 1;
                            if (row < 0 || row >= MAX_ROW) {
                                throw notXlsx();
                            }
                            lastColumn = -1;
                            Double height = number(r.getAttributeValue(null, "ht"));
                            if (height != null && flag(r.getAttributeValue(null, "customHeight"), false)) {
                                sheet.heights.put(row, clamp((int) Math.round(height * 4 / 3), 16, 400));
                            }
                        }
                        case "c" -> {
                            read = counted(read);
                            Integer s = integer(r.getAttributeValue(null, "s"));
                            int[] at = reference(r.getAttributeValue(null, "r"));
                            int cellRow = at == null ? Math.max(row, 0) : at[0];
                            int cellColumn = at == null ? lastColumn + 1 : at[1];
                            if (cellColumn >= MAX_COLUMN) {
                                throw notXlsx();
                            }
                            lastColumn = cellColumn;
                            cell.begin(r.getAttributeValue(null, "t"), s == null ? -1 : s, cellRow, cellColumn);
                        }
                        case "v" -> cell.inValue = cell.inCell;
                        case "f" -> cell.formula = cell.formula || cell.inCell;
                        case "is" -> cell.inInline = cell.inCell;
                        case "rPh" -> cell.phonetic++;
                        case "t" -> cell.inInlineText = cell.inInline && cell.phonetic == 0;
                        case "col" -> {
                            read = counted(read);
                            column(r, sheet);
                        }
                        case "mergeCell" -> {
                            read = counted(read);
                            merge(r.getAttributeValue(null, "ref"), sheet);
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    if (cell.inValue) {
                        append(cell.value, r.getText());
                    } else if (cell.inInlineText) {
                        append(cell.inline, r.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "v" -> cell.inValue = false;
                        case "t" -> cell.inInlineText = false;
                        case "is" -> cell.inInline = false;
                        case "rPh" -> cell.phonetic--;
                        case "c" -> {
                            cell.inCell = false;
                            finish(sheet, cell, strings, styles, date1904);
                        }
                        default -> {
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw notXlsx();
        } finally {
            close(r);
        }
    }

    private int counted(int read) {
        if (read + 1 > settings.maxCells()) {
            throw tooLarge("cells", "The sheet holds more than " + settings.maxCells()
                    + " cells and rows, which is more than can be read.");
        }
        return read + 1;
    }

    private static void column(XMLStreamReader r, RawSheet sheet) {
        Integer first = integer(r.getAttributeValue(null, "min"));
        Integer last = integer(r.getAttributeValue(null, "max"));
        Double width = number(r.getAttributeValue(null, "width"));
        if (first == null || last == null || width == null || first < 1 || last < first
                || !flag(r.getAttributeValue(null, "customWidth"), false)) {
            return;
        }
        sheet.widths.add(new RawSheet.Width(first - 1, Math.min(last, MAX_COLUMN) - 1,
                clamp((int) Math.round(width * 7 + 5), 40, 1000)));
    }

    private void merge(String ref, RawSheet sheet) {
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

    private static void finish(RawSheet sheet, Pending cell, List<String> strings, Styles styles, boolean date1904) {
        String value = cell.value.toString();
        String type = cell.type;
        String text;
        if ("s".equals(type)) {
            Integer at = integer(value);
            text = at == null || at < 0 || at >= strings.size() ? null : strings.get(at);
        } else if ("inlineStr".equals(type)) {
            text = cell.inline.toString();
        } else if ("e".equals(type)) {
            sheet.notes.add(ImportNote.ERROR_CELLS);
            return;
        } else if ("b".equals(type)) {
            text = value.isBlank() ? null : "1".equals(value.trim()) ? "TRUE" : "FALSE";
        } else if ("str".equals(type) || "d".equals(type)) {
            text = value;
        } else {
            text = numberText(value, cell.style, styles, date1904, sheet);
        }
        if (text == null || text.isBlank()) {
            return;
        }
        if (cell.formula) {
            sheet.notes.add(ImportNote.FORMULAS);
        }
        Font font = styles.fontFor(cell.style);
        sheet.put(cell.row, cell.column, font == null ? new RawSheet.Cell(text, false, false, 0)
                : new RawSheet.Cell(text, font.bold(), font.italic(), font.points()));
    }

    /** A number as the text of its digits, or as a date when its style shows one. */
    private static String numberText(String value, int style, Styles styles, boolean date1904, RawSheet sheet) {
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (styles.isDate(style)) {
            Double serial = number(text);
            String date = serial == null ? null : date(serial, date1904);
            if (date != null) {
                sheet.notes.add(ImportNote.DATES);
                return date;
            }
        }
        return text;
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

    // ---- small helpers ----

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

    private static Integer integer(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double number(String text) {
        if (text == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean flag(String text, boolean absent) {
        if (text == null) {
            return absent;
        }
        return !("0".equals(text) || "false".equalsIgnoreCase(text));
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(most, value));
    }

    private static void close(XMLStreamReader r) {
        try {
            r.close();
        } catch (XMLStreamException e) {
            // The reader holds a byte array and no file: there is nothing to release.
            return;
        }
    }

    /** A stream reader that resolves nothing: no document type, no external entity, no schema, no fetch. */
    private static XMLStreamReader xml(byte[] part) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        try {
            // Belt and braces: the document type is already switched off, so nothing external is ever asked for.
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException e) {
            // A parser that does not know the property resolves nothing either, because of the switches above.
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        }
        try {
            return factory.createXMLStreamReader(new ByteArrayInputStream(part));
        } catch (XMLStreamException e) {
            throw notXlsx();
        }
    }
}
