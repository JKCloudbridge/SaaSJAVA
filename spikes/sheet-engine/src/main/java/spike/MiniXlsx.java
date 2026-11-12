package spike;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A small writer of VALID workbook packages (content types, package relationship, relationship types as full names), so that a
 * strict reader like POI gets as far as the content. The test builder of the platform writes a shortened package that its own
 * lenient reader accepts and a strict one does not. Used only to build hostile and sample files for the proof of concept.
 */
public final class MiniXlsx {

    public static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG = "http://schemas.openxmlformats.org/package/2006/relationships";

    private final StringBuilder rows = new StringBuilder();
    private final StringBuilder merges = new StringBuilder();
    private final Map<String, byte[]> extra = new LinkedHashMap<>();
    private final StringBuilder extraOverrides = new StringBuilder();
    private String prefix = "";
    private String styles = "<styleSheet xmlns=\"" + MAIN + "\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/>"
            + "</font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill "
            + "patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\">"
            + "<xf/></cellStyleXfs><cellXfs count=\"1\"><xf/></cellXfs></styleSheet>";
    private String sharedStrings;
    private int mergeCount;
    private String sheetState = "";
    private String mainType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";

    public MiniXlsx row(int number, String... cells) {
        rows.append("<row r=\"").append(number).append("\">");
        for (String c : cells) {
            rows.append(c);
        }
        rows.append("</row>");
        return this;
    }

    public MiniXlsx merge(String ref) {
        merges.append("<mergeCell ref=\"").append(ref).append("\"/>");
        mergeCount++;
        return this;
    }

    public MiniXlsx part(String name, byte[] bytes) {
        extra.put(name, bytes);
        return this;
    }

    public MiniXlsx override(String partName, String contentType) {
        extraOverrides.append("<Override PartName=\"").append(partName).append("\" ContentType=\"").append(contentType)
                .append("\"/>");
        return this;
    }

    /** Declares the workbook as macro enabled (a file type this platform refuses). */
    public MiniXlsx macroEnabled() {
        mainType = "application/vnd.ms-excel.sheet.macroEnabled.main+xml";
        return this;
    }

    public MiniXlsx prefix(String text) {
        prefix = text;
        return this;
    }

    public MiniXlsx styles(String xml) {
        styles = xml;
        return this;
    }

    public MiniXlsx sharedStrings(String xml) {
        sharedStrings = xml;
        return this;
    }

    public static String text(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + text.replace("&", "&amp;").replace("<", "&lt;")
                + "</t></is></c>";
    }

    public static String number(String ref, String value) {
        return "<c r=\"" + ref + "\"><v>" + value + "</v></c>";
    }

    public static String formula(String ref, String formula, String cachedValue) {
        return "<c r=\"" + ref + "\"><f>" + formula.replace("&", "&amp;").replace("<", "&lt;") + "</f><v>" + cachedValue
                + "</v></c>";
    }

    public byte[] bytes() {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas."
                + "openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/"
                + "vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"" + mainType + "\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\""
                + "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\""
                + "/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + (sharedStrings == null ? "" : "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/"
                        + "vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>")
                + extraOverrides + "</Types>").getBytes(StandardCharsets.UTF_8));
        parts.put("_rels/.rels", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"" + PKG + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + R + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
                .getBytes(StandardCharsets.UTF_8));
        parts.put("xl/workbook.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"" + MAIN + "\" xmlns:r=\""
                + R + "\"><sheets><sheet name=\"One\" sheetId=\"1\" r:id=\"rId1\"" + sheetState + "/></sheets></workbook>")
                .getBytes(StandardCharsets.UTF_8));
        parts.put("xl/_rels/workbook.xml.rels", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"" + PKG
                + "\"><Relationship Id=\"rId1\" Type=\"" + R + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + R + "/styles\" Target=\"styles.xml\"/>"
                + (sharedStrings == null ? "" : "<Relationship Id=\"rId3\" Type=\"" + R
                        + "/sharedStrings\" Target=\"sharedStrings.xml\"/>")
                + "</Relationships>").getBytes(StandardCharsets.UTF_8));
        parts.put("xl/styles.xml", styles.getBytes(StandardCharsets.UTF_8));
        if (sharedStrings != null) {
            parts.put("xl/sharedStrings.xml", sharedStrings.getBytes(StandardCharsets.UTF_8));
        }
        parts.put("xl/worksheets/sheet1.xml", (prefix + "<worksheet xmlns=\"" + MAIN + "\"><sheetData>" + rows
                + "</sheetData>" + (mergeCount == 0 ? "" : "<mergeCells count=\"" + mergeCount + "\">" + merges
                        + "</mergeCells>") + "</worksheet>").getBytes(StandardCharsets.UTF_8));
        parts.putAll(extra);
        return zip(parts);
    }

    public static byte[] zip(Map<String, byte[]> parts) {
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
}
