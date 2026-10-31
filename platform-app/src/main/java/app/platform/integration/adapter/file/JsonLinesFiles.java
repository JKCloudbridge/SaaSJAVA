package app.platform.integration.adapter.file;

import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.FileFormatException;
import app.platform.integration.adapter.FileFormatException.Problem;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.PayloadException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One flat JSON object per line (Sprint 29A, ADR-0240). A value is a string, a number or a yes and no; null is a value
 * that is not there; a list or an object is refused, so a row is always the same kind of thing as a row of a table.
 * The same strict rules as {@link CsvFiles} apply to the text, the bounds and the identifier.
 */
final class JsonLinesFiles {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonLinesFiles() {
    }

    static List<CanonicalEntity> read(byte[] content, String kind, String idColumn, String operationColumn,
            int maxRows) {
        String text = CsvFiles.decode(content);
        List<CanonicalEntity> rows = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank()) {
                continue;
            }
            if (rows.size() >= maxRows) {
                throw new FileFormatException(Problem.TOO_MANY_ROWS);
            }
            rows.add(row(line, kind, idColumn, operationColumn));
        }
        if (rows.isEmpty()) {
            throw new FileFormatException(Problem.EMPTY);
        }
        return rows;
    }

    private static CanonicalEntity row(String line, String kind, String idColumn, String operationColumn) {
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (RuntimeException unreadable) {
            throw new FileFormatException(Problem.ROW_INVALID);
        }
        if (root == null || !root.isObject() || root.size() > CsvFiles.MAX_COLUMNS) {
            throw new FileFormatException(Problem.ROW_INVALID);
        }
        String id = null;
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (!CsvFiles.COLUMN.matcher(name).matches()) {
                throw new FileFormatException(Problem.ROW_INVALID);
            }
            if (value.isNull()) {
                continue;
            }
            Object read = scalar(value);
            if (read == null) {
                throw new FileFormatException(Problem.ROW_INVALID);
            }
            if (name.equals(idColumn)) {
                id = read.toString().strip();
            } else if (!name.equals(operationColumn) && !read.toString().isEmpty()) {
                if (read.toString().length() > CsvFiles.MAX_CELL) {
                    throw new FileFormatException(Problem.ROW_INVALID);
                }
                fields.put(name, read);
            }
        }
        if (id == null || id.isEmpty() || id.length() > CsvFiles.MAX_ID) {
            throw new FileFormatException(Problem.ROW_INVALID);
        }
        return new CanonicalEntity(kind, id, fields);
    }

    private static Object scalar(JsonNode value) {
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return new BigDecimal(value.asString());
        }
        return null;
    }

    static byte[] write(String idColumn, String operationColumn, Operation operation, CanonicalEntity entity) {
        ObjectNode node = JSON.createObjectNode();
        node.put(idColumn, entity.externalId() == null ? "" : entity.externalId());
        node.put(operationColumn, operation.name().toLowerCase(Locale.ROOT));
        for (Map.Entry<String, Object> field : entity.fields().entrySet()) {
            String name = field.getKey();
            if (name.equals(idColumn) || name.equals(operationColumn) || !CsvFiles.COLUMN.matcher(name).matches()) {
                throw new PayloadException("A field cannot be written as a key.");
            }
            Object value = field.getValue();
            if (value instanceof Boolean bool) {
                node.put(name, bool.booleanValue());
            } else if (value instanceof BigDecimal number) {
                node.put(name, number);
            } else {
                node.put(name, CsvFiles.text(value));
            }
        }
        return (JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
