package app.platform.sheets.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Documents of format version 2 for the tests of the sheets module (SH1A), as plain maps so that a test can break one
 * thing at a time, and the bridge between the two formats that the proof that nothing moved uses: a version 1 document
 * read as a version 2 document of one sheet, and back.
 */
final class TwoDocs {

    private TwoDocs() {
    }

    /** The smallest valid document of format version 2: the smallest version 1 grid as the one sheet {@code main}. */
    static Map<String, Object> minimal() {
        return wrap(GridDocs.minimal());
    }

    /** A version 1 document as the one sheet of a version 2 document, with nothing of version 2 added. */
    static Map<String, Object> wrap(Map<String, Object> versionOne) {
        Map<String, Object> sheet = GridDocs.map("key", "main");
        versionOne.forEach((name, value) -> {
            if (!"formatVersion".equals(name)) {
                sheet.put(name, value);
            }
        });
        return GridDocs.map("formatVersion", 2, "sheets", new ArrayList<>(List.of(sheet)));
    }

    /** The first sheet of a document made by {@link #minimal()} or {@link #rich()}, as the mutable map it is. */
    @SuppressWarnings("unchecked") // the documents are built by this class, so the sheet is a map
    static Map<String, Object> sheet(Map<String, Object> document) {
        return ((List<Map<String, Object>>) document.get("sheets")).get(0);
    }

    @SuppressWarnings("unchecked") // the documents are built by this class, so the sheets are a list of maps
    static List<Map<String, Object>> sheets(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("sheets");
    }

    @SuppressWarnings("unchecked") // as above: the cells are a map
    static Map<String, Object> cells(Map<String, Object> document) {
        return (Map<String, Object>) sheet(document).get("cells");
    }

    /** The cells of one sheet. */
    @SuppressWarnings("unchecked") // as above
    static Map<String, Object> cells0(Map<String, Object> sheet) {
        return (Map<String, Object>) sheet.get("cells");
    }

    /**
     * A document that uses every part of format version 2: a title, frozen panes, a page set-up, a look on a header
     * cell, a link, an input cell with a look and no value, and an attachment cell.
     */
    static Map<String, Object> rich() {
        Map<String, Object> document = minimal();
        Map<String, Object> sheet = sheet(document);
        sheet.put("title", "Plant activity");
        sheet.put("freeze", GridDocs.map("rows", 1, "columns", 1));
        sheet.put("print", GridDocs.map("paper", "A4", "orientation", "LANDSCAPE", "fitToWidth", true,
                "repeatHeaderRows", true));
        GridDocs.list(sheet, "columns").get(0).put("width", 8);
        GridDocs.list(sheet, "rows").get(0).put("height", 4);
        Map<String, Object> cells = cells(document);
        cells.put("h1:c1", GridDocs.map("value", "Item", "style", GridDocs.map("bold", true), "look",
                GridDocs.map("font", "SANS_NEUTRAL", "size", 12, "fill", "#FFEECC", "colour", "#112233",
                        "borders", GridDocs.map("top", "THIN", "bottom", "DOUBLE"), "wrap", true)));
        cells.put("h1:c2", GridDocs.map("value", "Open the guide", "link", "https://example.test/guide"));
        cells.put("r1:c2", GridDocs.map("look", GridDocs.map("fill", "#AABBCC", "verticalAlignment", "TOP",
                "numberFormat", "TWO_DECIMALS")));
        sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("type", "attachment", "required", true, "maxFiles", 3)));
        return document;
    }

    static byte[] bytes(Map<String, Object> document) {
        return GridDocs.bytes(document);
    }

    /**
     * A version 1 document (its canonical text) as a version 2 document of one sheet called {@code main}, with nothing
     * of version 2 added.
     */
    static String asOneSheet(String canonicalVersionOne) {
        ObjectNode body = (ObjectNode) GridDocs.JSON.readTree(canonicalVersionOne);
        body.remove("formatVersion");
        body.put("key", "main");
        ObjectNode root = GridDocs.JSON.createObjectNode();
        root.put("formatVersion", 2);
        root.putArray("sheets").add(body);
        return GridDocs.JSON.writeValueAsString(root);
    }

    /**
     * The first sheet of a version 2 document written as a version 1 document: the key is removed and the format
     * number is 1. It is a version 1 document only if the sheet has no part of version 2.
     */
    static byte[] backToVersionOne(String canonicalVersionTwo) {
        JsonNode root = GridDocs.JSON.readTree(canonicalVersionTwo);
        ObjectNode sheet = (ObjectNode) root.path("sheets").get(0).deepCopy();
        sheet.remove("key");
        sheet.put("formatVersion", 1);
        return GridDocs.JSON.writeValueAsString(sheet).getBytes(StandardCharsets.UTF_8);
    }
}
