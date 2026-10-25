package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Alignment;
import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Colour;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Kind;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.Style;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a grid as an .xlsx workbook with the JDK's own zip stream and no library (FA7, ADR-0201), the counterpart of
 * {@link XlsxReader}. The workbook is the sheet as a person reads it on the screen: the band of column labels, the
 * header rows, the named rows (or, when the filler adds the rows, the same blank sample rows the screen shows), every
 * cell bordered, merged areas, column widths and row heights, bold, italic, underline, the four sizes, alignment and
 * the named text colours, and one footer row across the full width with the given text at its right.
 *
 * <p><strong>What a spreadsheet program must never run.</strong> Every cell is written as an inline string: a text that
 * starts with {@code =}, {@code +}, {@code -} or {@code @} is a string in the file, never a formula (there is no
 * {@code <f>} element anywhere), and such a cell also carries the quote prefix, so it stays text when someone edits it.
 * There are no macros, no links, no images and no external references; only the six parts a workbook needs. Characters
 * that XML cannot hold are dropped. The input cells of a template are written empty. The size of the result is bounded.
 *
 * <p>Nothing typed in a cell is put in a message.
 */
final class XlsxWriter {

    /** The number of blank sample rows of a sheet whose filler adds the rows (the screen shows the same). */
    static final int SAMPLE_ROWS = 10;
    /** The height of the band of column labels, in pixels (the screen's own). */
    private static final int LABEL_BAND_HEIGHT = 28;
    private static final int DEFAULT_ROW_HEIGHT = 24;
    private static final int DEFAULT_COLUMN_WIDTH = 120;
    /** Wider than this (pixels, all columns together) the page is landscape: A4 portrait holds about this much. */
    static final int PORTRAIT_WIDTH = 720;
    private static final int MOST_BYTES = 5_000_000;
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RELS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String SHEET_NAME = "Sheet1";
    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String TYPES = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String KIND = "application/vnd.openxmlformats";
    private static final String KIND_SHEET = KIND + "-officedocument.spreadsheetml";

    /** Whether a text is one a spreadsheet program could take for a formula. */
    static boolean looksLikeFormula(String text) {
        return !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0;
    }

    /** The orientation a sheet of this width is printed in. */
    static boolean landscape(SheetGrid grid) {
        int width = 0;
        for (SheetGrid.Column column : grid.columns()) {
            width += column.width() == null ? DEFAULT_COLUMN_WIDTH : column.width();
        }
        return width > PORTRAIT_WIDTH;
    }

    private record FontKey(boolean bold, boolean italic, boolean underline, int points, String colour) {
    }

    private record XfKey(FontKey font, String horizontal, boolean wrap, boolean quotePrefix) {
    }

    private record Row(String id, boolean header, boolean sample, int height) {
    }

    private final Map<FontKey, Integer> fonts = new LinkedHashMap<>();
    private final Map<XfKey, Integer> formats = new LinkedHashMap<>();

    /**
     * The workbook for a grid.
     *
     * @param grid the sheet
     * @param footerRight the text at the right of the footer row; empty leaves the row blank
     * @throws ApiException {@code PAYLOAD_TOO_LARGE} when the file would pass the limit
     */
    byte[] write(SheetGrid grid, String footerRight) {
        fonts.clear();
        formats.clear();
        List<Row> rows = rowsOf(grid);
        Map<String, Integer> rowAt = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id() != null) {
                rowAt.put(rows.get(i).id(), i + 2); // row 1 is the band of column labels
            }
        }
        Map<String, Integer> columnAt = new HashMap<>();
        for (int i = 0; i < grid.columns().size(); i++) {
            columnAt.put(grid.columns().get(i).id(), i + 1);
        }
        String sheet = sheetXml(grid, rows, rowAt, columnAt, footerRight == null ? "" : footerRight);
        String styles = stylesXml();
        int titleRows = 1 + grid.headerRows().size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", DECLARATION + "<Types xmlns=\"" + TYPES + "\">"
                    + "<Default Extension=\"rels\" ContentType=\"" + KIND + "-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"" + KIND_SHEET + ".sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"" + KIND_SHEET
                    + ".worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"" + KIND_SHEET + ".styles+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", DECLARATION + "<Relationships xmlns=\"" + RELS + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + OFFICE_RELS + "/officeDocument\" "
                    + "Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip, "xl/workbook.xml", DECLARATION + "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + OFFICE_RELS
                    + "\"><sheets><sheet name=\"" + SHEET_NAME + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                    + "<definedNames><definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">" + SHEET_NAME
                    + "!$1:$" + titleRows + "</definedName></definedNames></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", DECLARATION + "<Relationships xmlns=\"" + RELS + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + OFFICE_RELS + "/worksheet\" "
                    + "Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"" + OFFICE_RELS
                    + "/styles\" Target=\"styles.xml\"/></Relationships>");
            put(zip, "xl/styles.xml", styles);
            put(zip, "xl/worksheets/sheet1.xml", sheet);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (out.size() > MOST_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file would be larger than the allowed size of "
                    + MOST_BYTES + " bytes.");
        }
        return out.toByteArray();
    }

    // ---- the rows ----

    private static List<Row> rowsOf(SheetGrid grid) {
        List<Row> rows = new ArrayList<>();
        for (Line line : grid.headerRows()) {
            rows.add(new Row(line.id(), true, false, line.height() == null ? DEFAULT_ROW_HEIGHT : line.height()));
        }
        for (Line line : grid.rows()) {
            rows.add(new Row(line.id(), false, false, line.height() == null ? DEFAULT_ROW_HEIGHT : line.height()));
        }
        if (grid.dimension() == 1) {
            for (int i = 0; i < SAMPLE_ROWS; i++) {
                rows.add(new Row(null, false, true, DEFAULT_ROW_HEIGHT));
            }
        }
        return rows;
    }

    private String sheetXml(SheetGrid grid, List<Row> rows, Map<String, Integer> rowAt, Map<String, Integer> columnAt,
            String footer) {
        int columnCount = grid.columns().size();
        int footerRow = rows.size() + 2;
        // Where a merged area covers cells: they are written empty (with their border) and the area is listed once.
        Map<Long, Merge> anchors = new HashMap<>();
        Set<Long> covered = new HashSet<>();
        List<String> mergeRefs = new ArrayList<>();
        for (Merge merge : grid.merges()) {
            int[] from = position(merge.from(), rowAt, columnAt);
            int[] to = position(merge.to(), rowAt, columnAt);
            if (from == null || to == null || to[0] < from[0] || to[1] < from[1]
                    || from[0] == to[0] && from[1] == to[1]) {
                continue;
            }
            anchors.put(key(from[0], from[1]), merge);
            for (int r = from[0]; r <= to[0]; r++) {
                for (int c = from[1]; c <= to[1]; c++) {
                    if (r != from[0] || c != from[1]) {
                        covered.add(key(r, c));
                    }
                }
            }
            mergeRefs.add(ref(from[1], from[0]) + ":" + ref(to[1], to[0]));
        }
        StringBuilder data = new StringBuilder();
        // Row 1: the band of column labels.
        data.append("<row r=\"1\" ht=\"").append(rowPoints(LABEL_BAND_HEIGHT)).append("\" customHeight=\"1\">");
        for (int c = 1; c <= columnCount; c++) {
            String label = grid.columns().get(c - 1).label();
            data.append(cell(c, 1, label, new Style(true, false, FontSize.NORMAL, false, Alignment.CENTER, null),
                    true));
        }
        data.append("</row>");
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int r = i + 2;
            data.append("<row r=\"").append(r).append("\" ht=\"").append(rowPoints(row.height()))
                    .append("\" customHeight=\"1\">");
            for (int c = 1; c <= columnCount; c++) {
                if (covered.contains(key(r, c))) {
                    data.append(cell(c, r, null, null, true));
                    continue;
                }
                Cell found = row.id() == null ? null
                        : grid.cells().get(row.id() + ":" + grid.columns().get(c - 1).id());
                String text = null;
                Style style = null;
                if (found != null) {
                    boolean input = !row.header() && (found.kind() == Kind.INPUT
                            || found.kind() == null && found.value() == null);
                    // A template shows its input cells empty: what a filler will type is not part of the sheet.
                    text = input ? null : shown(found.value());
                    style = found.style();
                }
                data.append(cell(c, r, text, style, true));
            }
            data.append("</row>");
        }
        data.append("<row r=\"").append(footerRow).append("\" ht=\"").append(rowPoints(DEFAULT_ROW_HEIGHT))
                .append("\" customHeight=\"1\">");
        for (int c = 1; c <= columnCount; c++) {
            boolean last = c == columnCount;
            data.append(cell(c, footerRow, last && !footer.isEmpty() ? footer : null,
                    new Style(false, false, FontSize.NORMAL, false, Alignment.RIGHT, null), false));
        }
        data.append("</row>");

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<worksheet xmlns=\"").append(MAIN).append("\"><sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        xml.append("<dimension ref=\"A1:").append(ref(columnCount, footerRow)).append("\"/>");
        xml.append("<sheetFormatPr defaultRowHeight=\"18\"/><cols>");
        for (int c = 1; c <= columnCount; c++) {
            Integer width = grid.columns().get(c - 1).width();
            xml.append("<col min=\"").append(c).append("\" max=\"").append(c).append("\" width=\"")
                    .append(characters(width == null ? DEFAULT_COLUMN_WIDTH : width)).append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols><sheetData>").append(data).append("</sheetData>");
        if (!mergeRefs.isEmpty()) {
            xml.append("<mergeCells count=\"").append(mergeRefs.size()).append("\">");
            mergeRefs.forEach(ref -> xml.append("<mergeCell ref=\"").append(ref).append("\"/>"));
            xml.append("</mergeCells>");
        }
        xml.append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" "
                + "footer=\"0.3\"/>");
        xml.append("<pageSetup paperSize=\"9\" orientation=\"").append(landscape(grid) ? "landscape" : "portrait")
                .append("\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>");
        return xml.toString();
    }

    /** One cell: an inline string (never a formula) or an empty bordered cell. */
    private String cell(int column, int row, String text, Style style, boolean wrap) {
        String clean = text == null ? "" : clean(text);
        int format = format(style, wrap, looksLikeFormula(clean));
        String at = ref(column, row);
        if (clean.isEmpty()) {
            return "<c r=\"" + at + "\" s=\"" + format + "\"/>";
        }
        return "<c r=\"" + at + "\" s=\"" + format + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + escape(clean)
                + "</t></is></c>";
    }

    // ---- the styles ----

    private int format(Style style, boolean wrap, boolean quotePrefix) {
        Style used = style == null ? new Style(false, false, FontSize.NORMAL) : style;
        FontKey font = new FontKey(used.bold(), used.italic(), used.underline(), points(used.fontSize()),
                rgb(used.colour()));
        fonts.putIfAbsent(font, fonts.size() + 1);
        XfKey key = new XfKey(font, horizontal(used.alignment()), wrap, quotePrefix);
        return formats.computeIfAbsent(key, ignored -> formats.size() + 1);
    }

    private String stylesXml() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<styleSheet xmlns=\"").append(MAIN).append("\">");
        xml.append("<fonts count=\"").append(fonts.size() + 1).append("\"><font><sz val=\"11\"/><name val=\"Calibri\"/>"
                + "</font>");
        for (FontKey font : fonts.keySet()) {
            xml.append("<font>");
            if (font.bold()) {
                xml.append("<b/>");
            }
            if (font.italic()) {
                xml.append("<i/>");
            }
            if (font.underline()) {
                xml.append("<u/>");
            }
            xml.append("<sz val=\"").append(font.points()).append("\"/>");
            if (font.colour() != null) {
                xml.append("<color rgb=\"").append(font.colour()).append("\"/>");
            }
            xml.append("<name val=\"Calibri\"/></font>");
        }
        xml.append("</fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill "
                + "patternType=\"gray125\"/></fill></fills>");
        xml.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border>"
                + "<left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right>"
                + "<top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom>"
                + "<diagonal/></border></borders>");
        xml.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                + "</cellStyleXfs>");
        xml.append("<cellXfs count=\"").append(formats.size() + 1)
                .append("\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>");
        for (XfKey key : formats.keySet()) {
            xml.append("<xf numFmtId=\"0\" fontId=\"").append(fonts.get(key.font())).append("\" fillId=\"0\" "
                    + "borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"");
            if (key.quotePrefix()) {
                xml.append(" quotePrefix=\"1\"");
            }
            xml.append("><alignment horizontal=\"").append(key.horizontal()).append("\" vertical=\"center\"");
            if (key.wrap()) {
                xml.append(" wrapText=\"1\"");
            }
            xml.append("/></xf>");
        }
        xml.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>"
                + "</cellStyles></styleSheet>");
        return xml.toString();
    }

    private static int points(FontSize size) {
        return switch (size == null ? FontSize.NORMAL : size) {
            case SMALL -> 9;
            case NORMAL -> 11;
            case LARGE -> 14;
            case TITLE -> 18;
        };
    }

    private static String rgb(Colour colour) {
        if (colour == null) {
            return null;
        }
        return switch (colour) {
            case DEFAULT -> null;
            case ACCENT -> "FF93418B";
            case SUCCESS -> "FF1E7B34";
            case DANGER -> "FFB3261E";
        };
    }

    private static String horizontal(Alignment alignment) {
        return alignment == null ? "left" : alignment.name().toLowerCase(Locale.ROOT);
    }

    // ---- small helpers ----

    private static String shown(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? "Yes" : "No";
        }
        return value == null ? null : value.toString();
    }

    private static int[] position(String key, Map<String, Integer> rowAt, Map<String, Integer> columnAt) {
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

    /** A1-style reference of a one-based column and row. */
    static String ref(int column, int row) {
        StringBuilder name = new StringBuilder();
        for (int n = column; n > 0; n = (n - 1) / 26) {
            name.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return name.toString() + row;
    }

    private static String rowPoints(int pixels) {
        return Double.toString(Math.round(pixels * 75.0) / 100.0);
    }

    private static String characters(int pixels) {
        return Double.toString(Math.round(Math.max(1, (pixels - 5) / 7.0) * 100.0) / 100.0);
    }

    /** The text without the characters XML 1.0 cannot hold. */
    private static String clean(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(point -> point == 0x9 || point == 0xA || point == 0xD
                || point >= 0x20 && point <= 0xD7FF || point >= 0xE000 && point <= 0xFFFD
                || point >= 0x10000 && point <= 0x10FFFF).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
