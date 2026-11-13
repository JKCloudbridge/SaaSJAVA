package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Freeze;
import app.platform.sheets.internal.SheetDocument.Input;
import app.platform.sheets.internal.SheetDocument.InputClass;
import app.platform.sheets.internal.SheetDocument.Print;
import app.platform.sheets.internal.SheetDocument.Tab;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Turns a document of format version 2 that already fits its schema into the typed model (SH1A). Like
 * {@link SheetGridReader} it trusts the shape and checks nothing else: every rule is {@link SheetDocumentValidator}'s,
 * which runs on the result. The body of a sheet is read by the version 1 reader, which ignores what version 2 adds;
 * the look, the links and the stated input cells are read here.
 */
final class SheetDocumentReader {

    private SheetDocumentReader() {
    }

    /** The model of the document. */
    static SheetDocument read(JsonNode root) {
        List<Tab> sheets = new ArrayList<>();
        for (JsonNode sheet : root.path("sheets")) {
            sheets.add(tab(sheet));
        }
        return new SheetDocument(sheets);
    }

    /** The body of the first sheet: what every reader that knows only format version 1 needs. */
    static SheetGrid firstBody(JsonNode root) {
        return SheetGridReader.readBody(root.path("sheets").get(0));
    }

    private static Tab tab(JsonNode node) {
        Map<String, SheetLook> looks = new LinkedHashMap<>();
        Map<String, String> links = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.path("cells").properties()) {
            SheetLook look = SheetLook.read(entry.getValue().path("look"));
            if (look != null) {
                looks.put(entry.getKey(), look);
            }
            if (entry.getValue().has("link")) {
                links.put(entry.getKey(), entry.getValue().path("link").asString());
            }
        }
        Map<String, Input> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.path("inputs").properties()) {
            inputs.put(entry.getKey(), input(entry.getValue()));
        }
        List<SheetDocument.Condition> conditions = new ArrayList<>();
        for (JsonNode condition : node.path("conditions")) {
            conditions.add(new SheetDocument.Condition(condition.path("from").asString(),
                    condition.path("to").asString(), condition.path("operator").asString(),
                    condition.path("value").asString(),
                    condition.has("value2") ? condition.path("value2").asString() : null,
                    condition.has("fill") ? condition.path("fill").asString() : null,
                    condition.has("colour") ? condition.path("colour").asString() : null));
        }
        JsonNode freeze = node.path("freeze");
        JsonNode print = node.path("print");
        return new Tab(node.path("key").asString(), node.has("title") ? node.path("title").asString() : null,
                node.path("fillable").asBoolean(true),
                freeze.isObject() ? new Freeze(freeze.path("rows").asInt(), freeze.path("columns").asInt()) : null,
                print.isObject() ? new Print(print.path("paper").asString(), print.path("orientation").asString(),
                        print.path("fitToWidth").asBoolean(), print.path("repeatHeaderRows").asBoolean()) : null,
                inputs, SheetGridReader.readBody(node), looks, links, conditions);
    }

    private static Input input(JsonNode node) {
        List<String> picklist = null;
        JsonNode list = node.path("picklist");
        if (list.isObject()) {
            picklist = new ArrayList<>();
            for (JsonNode value : list.path("values")) {
                picklist.add(value.asString());
            }
        }
        return new Input(node.has("type") ? node.path("type").asString() : null,
                node.path("required").asBoolean(false),
                node.has("class") ? InputClass.of(node.path("class").asString()).orElseThrow() : InputClass.FILLER,
                picklist, node.has("maxFiles") ? node.path("maxFiles").asInt() : null);
    }
}
