package app.platform.sheets.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Makes a small .xlsx workbook in memory for the tests of the reader (UI3B): a zip of the XML parts a sheet needs,
 * built by hand so that a test can state exactly what the file holds and make it hostile in one way at a time. The
 * styles part has four cell styles: 0 plain, 1 bold, 2 a date (format 14), 3 italic and bold at 18 points.
 */
final class XlsxFile {

    static final int PLAIN = 0;
    static final int BOLD = 1;
    static final int DATE = 2;
    static final int TITLE = 3;
    static final int BOXED = 4;
    static final int UNDERLINED = 5;
    static final int BORDER_NONE = 6;
    /** A large bold italic title in a box with a heavier line. */
    static final int TITLE_BOX = 7;
    static final int STRAY = 8;

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RELS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String DOC_RELS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final StringBuilder rows = new StringBuilder();
    private final StringBuilder merges = new StringBuilder();
    private final Map<String, byte[]> extra = new LinkedHashMap<>();
    private String cols = "";
    private String sharedStrings;
    private String contentTypes = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument."
            + "spreadsheetml.sheet.main+xml\"/></Types>";
    private final StringBuilder workbookExtra = new StringBuilder();
    private final StringBuilder workbookRels = new StringBuilder();
    private String beforeData = "";
    private String afterMerges = "";
    private int extraSheets;
    private String sheetPrefix = "";
    private int mergeCount;
    private boolean date1904;
    private String styles = STYLES;

    /** One row; a height in points makes it a custom height. */
    XlsxFile row(int number, Integer heightPoints, String... cells) {
        rows.append("<row r=\"").append(number).append('"');
        if (heightPoints != null) {
            rows.append(" ht=\"").append(heightPoints).append("\" customHeight=\"1\"");
        }
        rows.append('>');
        for (String cell : cells) {
            rows.append(cell);
        }
        rows.append("</row>");
        return this;
    }

    XlsxFile row(int number, String... cells) {
        return row(number, null, cells);
    }

    /** A cell that holds text written in place. */
    static String text(String ref, String text, int style) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\" s=\"" + style + "\"><is><t>" + escape(text) + "</t></is></c>";
    }

    static String text(String ref, String text) {
        return text(ref, text, PLAIN);
    }

    /** A cell that points at a shared text. */
    static String shared(String ref, int index) {
        return "<c r=\"" + ref + "\" t=\"s\"><v>" + index + "</v></c>";
    }

    static String number(String ref, String value, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><v>" + value + "</v></c>";
    }

    static String formula(String ref, String formula, String value) {
        return "<c r=\"" + ref + "\"><f>" + escape(formula) + "</f><v>" + value + "</v></c>";
    }

    static String bool(String ref, boolean value) {
        return "<c r=\"" + ref + "\" t=\"b\"><v>" + (value ? 1 : 0) + "</v></c>";
    }

    static String error(String ref) {
        return "<c r=\"" + ref + "\" t=\"e\"><v>#DIV/0!</v></c>";
    }

    /** A cell with no text at all, as a workbook writes one that only has a style. */
    static String blank(String ref) {
        return "<c r=\"" + ref + "\" s=\"1\"/>";
    }

    /** A cell with no text and a line on all four sides (FA14). */
    static String boxed(String ref) {
        return "<c r=\"" + ref + "\" s=\"" + BOXED + "\"/>";
    }

    /** A cell with no text and a line below it only: a border on one side is enough (FA14). */
    static String underlined(String ref) {
        return "<c r=\"" + ref + "\" s=\"" + UNDERLINED + "\"/>";
    }

    /** A cell with no text whose border names the style "none": it has no border (FA14). */
    static String noBorder(String ref) {
        return "<c r=\"" + ref + "\" s=\"" + BORDER_NONE + "\"/>";
    }

    /** A cell with no text whose style points at a border that the file does not have (FA14). */
    static String strayBorder(String ref) {
        return "<c r=\"" + ref + "\" s=\"" + STRAY + "\"/>";
    }

    /** A cell that holds text and is boxed. */
    static String boxedText(String ref, String text) {
        return text(ref, text, BOXED);
    }

    XlsxFile widths(int first, int last, double width) {
        cols += "<col min=\"" + first + "\" max=\"" + last + "\" width=\"" + width + "\" customWidth=\"1\"/>";
        return this;
    }

    XlsxFile merge(String ref) {
        merges.append("<mergeCell ref=\"").append(ref).append("\"/>");
        mergeCount++;
        return this;
    }

    XlsxFile sharedStrings(String... strings) {
        StringBuilder xml = new StringBuilder("<sst xmlns=\"" + MAIN + "\">");
        for (String text : strings) {
            xml.append("<si><t>").append(escape(text)).append("</t></si>");
        }
        sharedStrings = xml.append("</sst>").toString();
        return this;
    }

    XlsxFile part(String name, byte[] bytes) {
        extra.put(name, bytes);
        return this;
    }

    XlsxFile contentTypes(String xml) {
        contentTypes = xml;
        return this;
    }

    /** Text placed before the root element of the sheet part, for example a document type declaration. */
    XlsxFile sheetPrefix(String prefix) {
        sheetPrefix = prefix;
        return this;
    }

    XlsxFile secondSheet() {
        return extraSheet("Two", null, "<worksheet xmlns=\"" + MAIN + "\"><sheetData/></worksheet>");
    }

    /**
     * Another worksheet after the first, with the XML it holds; {@code state} is null for a visible one, otherwise
     * {@code hidden} or {@code veryHidden}.
     */
    XlsxFile extraSheet(String name, String state, String xml) {
        int number = extraSheets + 2;
        extraSheets++;
        workbookExtra.append("<sheet name=\"").append(escape(name)).append("\" sheetId=\"").append(number)
                .append('"').append(state == null ? "" : " state=\"" + state + "\"").append(" r:id=\"rId")
                .append(number).append("\"/>");
        workbookRels.append("<Relationship Id=\"rId").append(number).append("\" Type=\"worksheet\" Target="
                + "\"worksheets/sheet").append(number).append(".xml\"/>");
        extra.put("xl/worksheets/sheet" + number + ".xml", xml.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** The XML of a worksheet that holds the given rows, as text, for {@link #extraSheet}. */
    static String sheetXml(String rows) {
        return "<worksheet xmlns=\"" + MAIN + "\" xmlns:r=\"" + DOC_RELS + "\"><sheetData>" + rows
                + "</sheetData></worksheet>";
    }

    /** Elements before the data of the first sheet: the sheet properties, its views and its default sizes. */
    XlsxFile beforeData(String xml) {
        beforeData = xml;
        return this;
    }

    /** Elements after the merged areas of the first sheet: conditional formats, links, the page set-up. */
    XlsxFile afterMerges(String xml) {
        afterMerges = xml;
        return this;
    }

    XlsxFile date1904() {
        date1904 = true;
        return this;
    }

    /** Replaces the styles part, for a file whose styles are hostile or have no list of borders. */
    XlsxFile styles(String xml) {
        styles = xml;
        return this;
    }

    byte[] bytes() {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
        parts.put("xl/workbook.xml", ("<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + DOC_RELS + "\">"
                + (date1904 ? "<workbookPr date1904=\"1\"/>" : "") + "<sheets><sheet name=\"One\" sheetId=\"1\" "
                + "r:id=\"rId1\"/>" + workbookExtra + "</sheets></workbook>").getBytes(StandardCharsets.UTF_8));
        parts.put("xl/_rels/workbook.xml.rels", ("<Relationships xmlns=\"" + RELS + "\"><Relationship Id=\"rId1\" "
                + "Type=\"worksheet\" Target=\"worksheets/sheet1.xml\"/>" + workbookRels
                        + "</Relationships>").getBytes(StandardCharsets.UTF_8));
        parts.put("xl/styles.xml", styles.getBytes(StandardCharsets.UTF_8));
        if (sharedStrings != null) {
            parts.put("xl/sharedStrings.xml", sharedStrings.getBytes(StandardCharsets.UTF_8));
        }
        parts.put("xl/worksheets/sheet1.xml", (sheetPrefix + "<worksheet xmlns=\"" + MAIN + "\" xmlns:r=\"" + DOC_RELS
                + "\">" + beforeData
                + (cols.isEmpty() ? "" : "<cols>" + cols + "</cols>") + "<sheetData>" + rows + "</sheetData>"
                + (mergeCount == 0 ? "" : "<mergeCells count=\"" + mergeCount + "\">" + merges + "</mergeCells>")
                + afterMerges + "</worksheet>").getBytes(StandardCharsets.UTF_8));
        parts.putAll(extra);
        return zip(parts);
    }

    static byte[] zip(Map<String, byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The same styles as {@link #STYLES} without the list of borders: every cell has none (FA14). */
    static final String STYLES_WITHOUT_BORDERS = "<styleSheet xmlns=\"" + MAIN + "\"><fonts count=\"3\">"
            + "<font><sz val=\"11\"/></font><font><b/><sz val=\"11\"/></font>"
            + "<font><b/><i/><sz val=\"18\"/></font></fonts><cellXfs count=\"4\">"
            + "<xf numFmtId=\"0\" fontId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\"/><xf numFmtId=\"14\" fontId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\"/></cellXfs></styleSheet>";

    /**
     * The borders of the styles part: 0 none, 1 a thin line on all four sides, 2 a line below only, 3 a side that
     * names the style "none" (so no border), 4 a bold title box.
     */
    private static final String BORDER_LIST = "<borders count=\"5\">"
            + "<border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right>"
            + "<top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom>"
            + "<diagonal/></border>"
            + "<border><left/><right/><top/><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border>"
            + "<border><left style=\"none\"/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"medium\"/><right style=\"medium\"/><top style=\"medium\"/>"
            + "<bottom style=\"medium\"/><diagonal/></border></borders>";

    private static final String STYLES = "<styleSheet xmlns=\"" + MAIN + "\"><fonts count=\"3\">"
            + "<font><sz val=\"11\"/></font><font><b/><sz val=\"11\"/></font>"
            + "<font><b/><i/><sz val=\"18\"/></font></fonts>" + BORDER_LIST + "<cellXfs count=\"9\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" borderId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" borderId=\"0\"/>"
            + "<xf numFmtId=\"14\" fontId=\"0\" borderId=\"0\"/><xf numFmtId=\"0\" fontId=\"2\" borderId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" borderId=\"1\"/><xf numFmtId=\"0\" fontId=\"0\" borderId=\"2\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" borderId=\"3\"/><xf numFmtId=\"0\" fontId=\"2\" borderId=\"4\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" borderId=\"99\"/></cellXfs></styleSheet>";
}
