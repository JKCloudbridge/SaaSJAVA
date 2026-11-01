package app.platform.publicapi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Finds what would break a customer's software between two versions of the public contract document (Sprint 30,
 * ADR-0247). Additive change passes: a new operation, a new optional property, a new value of a response, a new
 * status. A change that removes or retypes something, makes something newly required in a request, or removes a value
 * of an enumeration is a break and is reported with its place. The same check as the baseline test of the metadata
 * definitions: a deliberate break needs a new version of the API, not a new baseline.
 */
public final class ContractBreaks {

    /**
     * What a schema says about itself without changing its shape. An enumeration is here because adding a value is
     * additive; removing one is checked on its own.
     */
    private static final Set<String> DOCUMENTATION = Set.of("description", "example", "examples", "title",
            "summary", "externalDocs", "deprecated", "enum");

    private ContractBreaks() {
    }

    /**
     * Everything in {@code current} that breaks a client written against {@code baseline}.
     *
     * @return one sentence for each break; empty when the change is additive
     */
    public static List<String> between(JsonNode baseline, JsonNode current) {
        List<String> breaks = new ArrayList<>();
        Set<String> requestSchemas = requestSchemas(baseline);
        operations(baseline, current, breaks);
        schemas(baseline, current, requestSchemas, breaks);
        return breaks;
    }

    // ---- operations ----

    private static void operations(JsonNode baseline, JsonNode current, List<String> breaks) {
        for (var path : baseline.path("paths").properties()) {
            JsonNode now = current.path("paths").path(path.getKey());
            if (now.isMissingNode()) {
                breaks.add("path removed: " + path.getKey());
                continue;
            }
            for (var method : path.getValue().properties()) {
                if (!isMethod(method.getKey())) {
                    continue;
                }
                String where = method.getKey().toUpperCase() + " " + path.getKey();
                JsonNode operation = now.path(method.getKey());
                if (operation.isMissingNode()) {
                    breaks.add("operation removed: " + where);
                    continue;
                }
                String before = method.getValue().path("operationId").asString();
                if (!before.equals(operation.path("operationId").asString())) {
                    breaks.add("operation renamed: " + where);
                }
                for (var status : method.getValue().path("responses").properties()) {
                    if (operation.path("responses").path(status.getKey()).isMissingNode()) {
                        breaks.add("status removed: " + where + " " + status.getKey());
                    }
                }
                parameters(method.getValue(), operation, where, breaks);
                requestBody(method.getValue(), operation, where, breaks);
            }
        }
    }

    private static void parameters(JsonNode before, JsonNode after, String where, List<String> breaks) {
        Set<String> known = new HashSet<>();
        for (JsonNode parameter : before.path("parameters")) {
            known.add(key(parameter));
            JsonNode same = find(after.path("parameters"), key(parameter));
            if (same == null) {
                breaks.add("parameter removed: " + where + " " + key(parameter));
            } else if (!stripped(parameter.path("schema")).equals(stripped(same.path("schema")))) {
                breaks.add("parameter retyped: " + where + " " + key(parameter));
            } else if (!parameter.path("required").asBoolean(false) && same.path("required").asBoolean(false)) {
                breaks.add("parameter newly required: " + where + " " + key(parameter));
            }
        }
        for (JsonNode parameter : after.path("parameters")) {
            if (!known.contains(key(parameter)) && parameter.path("required").asBoolean(false)) {
                breaks.add("new required parameter: " + where + " " + key(parameter));
            }
        }
    }

    private static void requestBody(JsonNode before, JsonNode after, String where, List<String> breaks) {
        boolean had = !before.path("requestBody").isMissingNode();
        boolean has = !after.path("requestBody").isMissingNode();
        if (!had && has && after.path("requestBody").path("required").asBoolean(false)) {
            breaks.add("request body newly required: " + where);
        }
        if (had && !has) {
            breaks.add("request body removed: " + where);
        }
    }

    private static JsonNode find(JsonNode parameters, String key) {
        for (JsonNode parameter : parameters) {
            if (key(parameter).equals(key)) {
                return parameter;
            }
        }
        return null;
    }

    private static String key(JsonNode parameter) {
        return parameter.path("in").asString() + ":" + parameter.path("name").asString();
    }

    private static boolean isMethod(String key) {
        return Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(key);
    }

    // ---- schemas ----

    private static void schemas(JsonNode baseline, JsonNode current, Set<String> requestSchemas,
            List<String> breaks) {
        for (var schema : baseline.path("components").path("schemas").properties()) {
            JsonNode now = current.path("components").path("schemas").path(schema.getKey());
            String where = "schema " + schema.getKey();
            if (now.isMissingNode()) {
                breaks.add("schema removed: " + schema.getKey());
                continue;
            }
            for (var property : schema.getValue().path("properties").properties()) {
                JsonNode after = now.path("properties").path(property.getKey());
                if (after.isMissingNode()) {
                    breaks.add("property removed: " + where + "." + property.getKey());
                } else if (!stripped(property.getValue()).equals(stripped(after))) {
                    breaks.add("property retyped: " + where + "." + property.getKey());
                } else {
                    for (JsonNode value : property.getValue().path("enum")) {
                        if (!contains(after.path("enum"), value)) {
                            breaks.add("enum value removed: " + where + "." + property.getKey() + " "
                                    + value.asString());
                        }
                    }
                }
            }
            for (JsonNode value : schema.getValue().path("enum")) {
                if (!contains(now.path("enum"), value)) {
                    breaks.add("enum value removed: " + where + " " + value.asString());
                }
            }
            if (requestSchemas.contains(schema.getKey())) {
                Set<String> required = names(schema.getValue().path("required"));
                for (JsonNode name : now.path("required")) {
                    if (!required.contains(name.asString())) {
                        breaks.add("request field newly required: " + where + "." + name.asString());
                    }
                }
            }
        }
    }

    /** The schemas a client sends: reachable from a request body. */
    private static Set<String> requestSchemas(JsonNode document) {
        Set<String> found = new HashSet<>();
        for (var path : document.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (isMethod(method.getKey())) {
                    collect(method.getValue().path("requestBody"), document, found);
                }
            }
        }
        return found;
    }

    private static void collect(JsonNode node, JsonNode document, Set<String> found) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.asString().startsWith("#/components/schemas/")) {
                String name = ref.asString().substring("#/components/schemas/".length());
                if (found.add(name)) {
                    collect(document.path("components").path("schemas").path(name), document, found);
                }
            }
            node.properties().forEach(entry -> collect(entry.getValue(), document, found));
        } else if (node.isArray()) {
            node.forEach(item -> collect(item, document, found));
        }
    }

    private static Set<String> names(JsonNode array) {
        Set<String> names = new HashSet<>();
        array.forEach(item -> names.add(item.asString()));
        return names;
    }

    private static boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode item : array) {
            if (item.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** A schema without the words about it: what is left is its shape. */
    private static String stripped(JsonNode schema) {
        return strip(schema, false).toString();
    }

    /**
     * Copies the node without the documentation keys. The map under {@code properties} is a map of names, not a
     * schema: a property called {@code description} is a property, and stays.
     */
    private static JsonNode strip(JsonNode node, boolean namesOfProperties) {
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry -> {
                if (namesOfProperties || !DOCUMENTATION.contains(entry.getKey())) {
                    copy.set(entry.getKey(), strip(entry.getValue(), "properties".equals(entry.getKey())
                            && !namesOfProperties));
                }
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> copy.add(strip(item, false)));
            return copy;
        }
        return node;
    }
}
