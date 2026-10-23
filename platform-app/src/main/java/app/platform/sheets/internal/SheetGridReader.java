package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.ColumnType;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Kind;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SheetGrid.Style;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Turns a document that already fits the schema into the typed model (ADR-0186). It trusts the shape and does no
 * checking of its own: every rule that needs more than a shape is {@link SheetGridValidator}'s, which runs on the
 * result. Because the schema ran first, a missing or odd part is an internal mistake, not input, and fails loudly.
 */
final class SheetGridReader {

    private SheetGridReader() {
    }

    /** The model of the document. */
    static SheetGrid read(JsonNode root) {
        List<Column> columns = new ArrayList<>();
        for (JsonNode column : root.path("columns")) {
            columns.add(column(column));
        }
        Map<String, Cell> cells = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.path("cells").properties()) {
            cells.put(entry.getKey(), cell(entry.getValue()));
        }
        List<Merge> merges = new ArrayList<>();
        for (JsonNode merge : root.path("merges")) {
            merges.add(new Merge(merge.path("from").asString(), merge.path("to").asString()));
        }
        return new SheetGrid(root.path("dimension").asInt(),
                RowsMode.of(root.path("rowsMode").asString()).orElseThrow(), columns, lines(root.path("headerRows")),
                lines(root.path("rows")), cells, merges);
    }

    private static Column column(JsonNode node) {
        List<String> picklist = null;
        JsonNode list = node.path("picklist");
        if (list.isObject()) {
            picklist = new ArrayList<>();
            for (JsonNode value : list.path("values")) {
                picklist.add(value.asString());
            }
        }
        return new Column(node.path("id").asString(), node.path("label").asString(""),
                ColumnType.of(node.path("type").asString()).orElseThrow(), node.path("required").asBoolean(false),
                scalar(node.path("default")), picklist, node.has("width") ? node.path("width").asInt() : null,
                node.path("reportable").asBoolean(false));
    }

    private static List<Line> lines(JsonNode list) {
        List<Line> lines = new ArrayList<>();
        for (JsonNode line : list) {
            lines.add(new Line(line.path("id").asString(), line.has("label") ? line.path("label").asString() : null,
                    line.has("height") ? line.path("height").asInt() : null));
        }
        return lines;
    }

    private static Cell cell(JsonNode node) {
        Style style = null;
        JsonNode styleNode = node.path("style");
        if (styleNode.isObject()) {
            style = new Style(styleNode.path("bold").asBoolean(false), styleNode.path("italic").asBoolean(false),
                    styleNode.has("fontSize") ? FontSize.valueOf(styleNode.path("fontSize").asString()) : null);
        }
        Kind kind = node.has("kind") ? Kind.valueOf(node.path("kind").asString().toUpperCase(java.util.Locale.ROOT))
                : null;
        return new Cell(scalar(node.path("value")), style, kind);
    }

    /** A string, a boolean or null; an empty text is nothing (a cell with no text and an empty text agree). */
    private static Object scalar(JsonNode node) {
        if (node.isString()) {
            return node.asString().isEmpty() ? null : node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return null;
    }
}
