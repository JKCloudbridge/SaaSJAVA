package app.platform.sheets.internal;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the shape of a grid against its schema file (ADR-0070 pattern, ADR-0173, ADR-0186). The schema is the one
 * published in the OpenAPI document ({@code sheet-grid.json} of the contract module), so what the API promises and what
 * the application enforces cannot drift apart. Only the shape is checked here; everything a schema cannot say is
 * {@link SheetGridValidator}'s.
 *
 * <p>A problem is answered with a place and fixed words. The place is made only of names the schema itself defines,
 * positions, and the keys of the map of cells when they have the shape of a cell reference; anything else a document
 * contained (a hostile key, for example) is shown as an ellipsis, so an error message never repeats what was typed.
 */
final class SheetSchemas {

    /** The resource inside the contract module. */
    static final String RESOURCE = "/schemas/sheet-grid.json";

    private static final String SCHEMAS_POINTER = "#/components/schemas/";
    private static final String ROOT = "SheetGrid";
    private static final int MOST_ISSUES = 20;

    /** The property names of the schema: the only words, besides positions and identifiers, a place may contain. */
    private static final Set<String> NAMES = Set.of("formatVersion", "dimension", "rowsMode", "columns", "headerRows",
            "rows", "cells", "merges", "id", "label", "type", "required", "default", "picklist", "values", "width",
            "height", "value", "style", "kind", "bold", "italic", "fontSize", "from", "to");
    private static final Pattern CELL = Pattern.compile("[A-Za-z0-9_-]{1,40}:[A-Za-z0-9_-]{1,40}");

    /**
     * One place that is wrong.
     *
     * @param place where, for example {@code grid.columns[1].label}
     * @param message what is wrong, in fixed words
     */
    record Issue(String place, String message) {
    }

    private final JsonNode schemas;
    private final Schema compiled;

    SheetSchemas() {
        JsonMapper json = JsonMapper.builder().build();
        try (InputStream in = SheetSchemas.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The schema of a sheet grid is missing");
            }
            this.schemas = json.readTree(in).path("schemas");
        } catch (IOException e) {
            throw new IllegalStateException("The schema of a sheet grid cannot be read");
        }
        ObjectNode root = json.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", SCHEMAS_POINTER + ROOT);
        root.putObject("components").set("schemas", schemas.deepCopy());
        this.compiled = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(root);
    }

    /** The schemas by name, as published in the OpenAPI document. A copy: callers may change it. */
    ObjectNode publishedSchemas() {
        return (ObjectNode) schemas.deepCopy();
    }

    /** Every place where the document does not fit the shape, at most twenty; empty when it fits. */
    List<Issue> validate(JsonNode document, String prefix) {
        Set<Issue> issues = new LinkedHashSet<>();
        for (Error error : compiled.validate(document)) {
            issues.add(new Issue(placeOf(prefix, error), plain(error)));
            if (issues.size() >= MOST_ISSUES) {
                break;
            }
        }
        return new ArrayList<>(issues);
    }

    private static String placeOf(String prefix, Error error) {
        StringBuilder place = new StringBuilder(prefix);
        String previous = "";
        for (String part : error.getInstanceLocation().toString().split("/")) {
            append(place, part, previous);
            if (!part.isEmpty()) {
                previous = part;
            }
        }
        String keyword = error.getKeyword();
        boolean namesProperty = "required".equals(keyword) || "additionalProperties".equals(keyword)
                || "unevaluatedProperties".equals(keyword);
        if (namesProperty && error.getProperty() != null && !error.getProperty().isBlank()) {
            // A property the schema defines may be named (a missing one); a property it does not define is whatever
            // the sender typed, even when it looks like a word, and is never repeated.
            append(place, error.getProperty(), "");
        }
        return place.toString();
    }

    /**
     * Adds one part of a location. A position is a number; a name is shown when the schema defines it; the one place
     * where keys are the sender's own is the map of cells, and a key there is shown when it has the shape of a cell
     * reference, which is two identifiers. Everything else is an ellipsis.
     */
    private static void append(StringBuilder place, String part, String previous) {
        if (part.isEmpty() || "$".equals(part)) {
            return;
        }
        if (part.chars().allMatch(Character::isDigit) && part.length() < 9) {
            place.append('[').append(part).append(']');
        } else if (NAMES.contains(part)) {
            place.append('.').append(part);
        } else if ("cells".equals(previous) && CELL.matcher(part).matches()) {
            place.append('.').append(part);
        } else {
            place.append(".…");
        }
    }

    private static String plain(Error error) {
        String keyword = error.getKeyword() == null ? "" : error.getKeyword();
        return switch (keyword) {
            case "required" -> "This is needed here and is missing.";
            case "additionalProperties", "unevaluatedProperties" -> "This is not allowed here.";
            case "enum" -> "This is not one of the allowed values.";
            case "const" -> "The format of the grid is not supported.";
            case "type" -> "This has the wrong kind of value.";
            case "pattern", "propertyNames" -> "An identifier or a cell reference has an invalid shape.";
            case "minItems" -> "This list needs at least one entry.";
            case "minLength" -> "This text is too short.";
            case "maxLength" -> "This text is longer than allowed.";
            case "minimum" -> "This number is below the allowed range.";
            case "maximum" -> "This number is above the allowed range.";
            default -> "This is not valid here.";
        };
    }
}
