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
    private String workbookExtra = "";
    private String sheetPrefix = "";
    private int mergeCount;
    private boolean date1904;

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
        workbookExtra = "<sheet name=\"Two\" sheetId=\"2\" r:id=\"rId2\"/>";
        extra.put("xl/worksheets/sheet2.xml", ("<worksheet xmlns=\"" + MAIN + "\"><sheetData/></worksheet>")
                .getBytes(StandardCharsets.UTF_8));
        return this;
    }

    XlsxFile date1904() {
        date1904 = true;
        return this;
    }

    byte[] bytes() {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
        parts.put("xl/workbook.xml", ("<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + DOC_RELS + "\">"
                + (date1904 ? "<workbookPr date1904=\"1\"/>" : "") + "<sheets><sheet name=\"One\" sheetId=\"1\" "
                + "r:id=\"rId1\"/>" + workbookExtra + "</sheets></workbook>").getBytes(StandardCharsets.UTF_8));
        parts.put("xl/_rels/workbook.xml.rels", ("<Relationships xmlns=\"" + RELS + "\"><Relationship Id=\"rId1\" "
                + "Type=\"worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"worksheet\" "
                + "Target=\"worksheets/sheet2.xml\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
        parts.put("xl/styles.xml", STYLES.getBytes(StandardCharsets.UTF_8));
        if (sharedStrings != null) {
            parts.put("xl/sharedStrings.xml", sharedStrings.getBytes(StandardCharsets.UTF_8));
        }
        parts.put("xl/worksheets/sheet1.xml", (sheetPrefix + "<worksheet xmlns=\"" + MAIN + "\">"
                + (cols.isEmpty() ? "" : "<cols>" + cols + "</cols>") + "<sheetData>" + rows + "</sheetData>"
                + (mergeCount == 0 ? "" : "<mergeCells count=\"" + mergeCount + "\">" + merges + "</mergeCells>")
                + "</worksheet>").getBytes(StandardCharsets.UTF_8));
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

    private static final String STYLES = "<styleSheet xmlns=\"" + MAIN + "\"><fonts count=\"3\">"
            + "<font><sz val=\"11\"/></font><font><b/><sz val=\"11\"/></font>"
            + "<font><b/><i/><sz val=\"18\"/></font></fonts><cellXfs count=\"4\">"
            + "<xf numFmtId=\"0\" fontId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\"/><xf numFmtId=\"14\" fontId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\"/></cellXfs></styleSheet>";
}
