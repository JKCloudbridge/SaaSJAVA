package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a normalised grid as its <em>canonical</em> JSON text and hashes it (ADR-0186). Canonical means: compact, no
 * white space, every object's keys in the natural order of their characters, lists in the order that means something
 * (columns and rows are displayed in order), and only the parts {@link SheetGridNormalizer} leaves. The same grid
 * therefore always gives the same bytes and the same SHA-256, whatever order its keys arrived in and whatever the
 * database does to the order of keys of its own JSON type.
 *
 * <p>The format has no number except small integers (the format, the dimension, widths and heights), so there is no
 * question of how a number is written.
 */
final class SheetGridWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SheetGridWriter() {
    }

    /** The canonical text of the grid. */
    static String write(SheetGrid grid) {
        Map<String, Object> root = new TreeMap<>();
        root.put("formatVersion", SheetGrid.FORMAT_VERSION);
        root.put("dimension", grid.dimension());
        root.put("rowsMode", grid.rowsMode().name());
        root.put("columns", grid.columns().stream().map(SheetGridWriter::column).toList());
        root.put("headerRows", grid.headerRows().stream().map(SheetGridWriter::line).toList());
        root.put("rows", grid.rows().stream().map(SheetGridWriter::line).toList());
        Map<String, Object> cells = new TreeMap<>();
        grid.cells().forEach((key, cell) -> cells.put(key, cell(cell)));
        root.put("cells", cells);
        List<Object> merges = new ArrayList<>();
        for (Merge merge : grid.merges()) {
            Map<String, Object> one = new TreeMap<>();
            one.put("from", merge.from());
            one.put("to", merge.to());
            merges.add(one);
        }
        root.put("merges", merges);
        return JSON.writeValueAsString(root);
    }

    /** The SHA-256 of the bytes. */
    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** The lower case hex of the SHA-256 of the text as UTF-8. */
    static String hashOf(String canonical) {
        return hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static Map<String, Object> column(Column column) {
        Map<String, Object> out = new TreeMap<>();
        out.put("id", column.id());
        out.put("label", column.label());
        out.put("type", column.type().json());
        if (column.required()) {
            out.put("required", true);
        }
        if (column.defaultValue() != null) {
            out.put("default", column.defaultValue());
        }
        if (column.picklist() != null) {
            out.put("picklist", Map.of("values", column.picklist()));
        }
        if (column.width() != null) {
            out.put("width", column.width());
        }
        return out;
    }

    private static Map<String, Object> line(Line line) {
        Map<String, Object> out = new TreeMap<>();
        out.put("id", line.id());
        if (line.label() != null) {
            out.put("label", line.label());
        }
        if (line.height() != null) {
            out.put("height", line.height());
        }
        return out;
    }

    private static Map<String, Object> cell(Cell cell) {
        Map<String, Object> out = new TreeMap<>();
        if (cell.value() != null) {
            out.put("value", cell.value());
        }
        if (cell.style() != null) {
            Map<String, Object> style = new TreeMap<>();
            if (cell.style().bold()) {
                style.put("bold", true);
            }
            if (cell.style().italic()) {
                style.put("italic", true);
            }
            FontSize size = cell.style().fontSize();
            if (size != null && size != FontSize.NORMAL) {
                style.put("fontSize", size.name());
            }
            out.put("style", style);
        }
        if (cell.kind() != null) {
            out.put("kind", cell.kind().json());
        }
        return out;
    }
}
