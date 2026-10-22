package app.platform.sheets.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grid documents for the tests of the sheets module, as plain maps so that a test can break one thing at a time. The
 * generator is the one of the prototype of FA0 ported to Java: a header band with two merged cells, a label in the
 * first column of each row, a few fixed cells, the rest input cells that are not stored. Seeded, so that every run
 * makes the same documents.
 */
final class GridDocs {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String[] WORDS = {"Fire", "Exit", "Guard", "Check", "Valid", "Panel", "Alarm", "Stock",
        "Clean", "Label", "Safety", "Gauge"};
    private static final String[] TYPES = {"text", "picklist", "number", "date", "boolean"};

    private GridDocs() {
    }

    static byte[] bytes(Object document) {
        return JSON.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
    }

    static String json(Object document) {
        return JSON.writeValueAsString(document);
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static Map<String, Object> column(String id, String label, String type) {
        Map<String, Object> column = map("id", id, "label", label, "type", type);
        if ("picklist".equals(type)) {
            column.put("picklist", map("values", new ArrayList<>(List.of("Pass", "Fail", "Not applicable"))));
        }
        return column;
    }

    /** The smallest document with a header row, one named row and two columns (a text and a number). */
    static Map<String, Object> minimal() {
        return map("formatVersion", 1, "dimension", 2, "rowsMode", "FIXED",
                "columns", new ArrayList<>(List.of(column("c1", "Item", "text"), column("c2", "Amount", "number"))),
                "headerRows", new ArrayList<>(List.of(map("id", "h1"))),
                "rows", new ArrayList<>(List.of(map("id", "r1", "label", "First"))),
                "cells", map(), "merges", new ArrayList<>());
    }

    /** The smallest one-dimensional document: columns only. */
    static Map<String, Object> minimalOneDimension() {
        Map<String, Object> doc = minimal();
        doc.put("dimension", 1);
        doc.put("rowsMode", "REPEATABLE");
        doc.put("rows", new ArrayList<>());
        return doc;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> doc, String key) {
        return (List<Map<String, Object>>) doc.get(key);
    }

    /** The values of a picklist column, as the mutable list the document holds. */
    @SuppressWarnings("unchecked") // the document is built by this class, so the values are a list of text
    static List<String> picklistValues(Map<String, Object> column) {
        return (List<String>) ((Map<String, Object>) column.get("picklist")).get("values");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cells(Map<String, Object> doc) {
        return (Map<String, Object>) doc.get("cells");
    }

    /** A realistic template, see the class comment. {@code dimension} 1 has no named rows. */
    static Map<String, Object> template(int rows, int columns, int dimension, double fixedRatio, long seed) {
        Random random = new Random(seed);
        List<Map<String, Object>> cols = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            String type = i == 0 ? "text" : TYPES[i % TYPES.length];
            Map<String, Object> column = column(String.format("c%03d", i), "Column " + (i + 1), type);
            if (i % 4 == 0) {
                column.put("required", true);
            }
            cols.add(column);
        }
        List<Map<String, Object>> body = new ArrayList<>();
        if (dimension == 2) {
            for (int i = 0; i < rows; i++) {
                body.add(map("id", String.format("r%03d", i), "label", "Row " + (i + 1), "height", 24));
            }
        }
        Map<String, Object> cells = map();
        List<Map<String, Object>> merges = new ArrayList<>();
        int half = Math.max(1, columns / 2);
        cells.put("h0:c000", map("value", "Inspection of installations", "style", map("bold", true, "fontSize",
                "TITLE")));
        if (columns > half) {
            cells.put("h0:" + String.format("c%03d", half), map("value", "Findings", "style",
                    map("bold", true, "italic", true)));
        }
        if (half > 1) {
            merges.add(map("from", "h0:c000", "to", "h0:" + String.format("c%03d", half - 1)));
        }
        if (columns - half > 1) {
            merges.add(map("from", "h0:" + String.format("c%03d", half), "to",
                    "h0:" + String.format("c%03d", columns - 1)));
        }
        for (Map<String, Object> row : body) {
            cells.put(row.get("id") + ":c000", map("value", row.get("label") + " " + phrase(random, 2)));
            for (int c = 1; c < columns; c++) {
                if (random.nextDouble() < fixedRatio) {
                    Map<String, Object> cell = map("value", phrase(random, 3));
                    if (c % 3 == 0) {
                        cell.put("style", map("italic", true));
                    }
                    cells.put(row.get("id") + ":" + String.format("c%03d", c), cell);
                }
            }
        }
        return map("formatVersion", 1, "dimension", dimension, "rowsMode", dimension == 2 ? "FIXED" : "REPEATABLE",
                "columns", cols, "headerRows", new ArrayList<>(List.of(map("id", "h0", "height", 24))),
                "rows", body, "cells", cells, "merges", merges);
    }

    private static String phrase(Random random, int words) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words; i++) {
            out.append(i == 0 ? "" : " ").append(WORDS[random.nextInt(WORDS.length)]);
        }
        return out.toString();
    }

    /** A copy of the document with every object's keys in a random order (arrays keep theirs). */
    @SuppressWarnings("unchecked")
    static Object shuffled(Object node, Random random) {
        if (node instanceof Map<?, ?> map) {
            List<Map.Entry<String, Object>> entries = new ArrayList<>((java.util.Set<Map.Entry<String, Object>>)
                    (java.util.Set<?>) map.entrySet());
            java.util.Collections.shuffle(entries, random);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : entries) {
                out.put(entry.getKey(), shuffled(entry.getValue(), random));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(shuffled(item, random));
            }
            return out;
        }
        return node;
    }
}
