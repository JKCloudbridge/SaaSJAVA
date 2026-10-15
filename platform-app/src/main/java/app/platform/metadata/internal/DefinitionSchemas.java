package app.platform.metadata.internal;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the JSON of a page layout, a record page or an application against its schema (ADR-0070). The schemas are
 * the ones published in the OpenAPI document ({@code metadata-definitions.json} of the contract module), so what the
 * API promises and what the application enforces cannot drift apart. A document that does not fit is answered with
 * every place that is wrong, in plain words, never with the library's own wording and never with a value that was
 * typed (the text of an error is shown to the person, but the audit trail and the logs only count problems).
 */
@Component
final class DefinitionSchemas {

    /** The resource inside the contract module. */
    static final String RESOURCE = "/schemas/metadata-definitions.json";

    /** The kinds of definition that have a schema. */
    enum Kind {
        PAGE_LAYOUT("PageLayoutDefinition"),
        RECORD_PAGE("RecordPageDefinition"),
        APPLICATION("ApplicationDefinition"),
        TAB("TabDefinition"),
        CUSTOM_PAGE("CustomPageDefinition");

        private final String schemaName;

        Kind(String schemaName) {
            this.schemaName = schemaName;
        }

        String schemaName() {
            return schemaName;
        }
    }

    /**
     * One place that is wrong.
     *
     * @param path where, starting with {@code definition}, for example {@code definition.sections[1].fields[0].field}
     * @param message what is wrong, in words
     */
    record Issue(String path, String message) {
    }

    private static final int MOST_ISSUES = 20;
    private static final String SCHEMAS_POINTER = "#/components/schemas/";

    private final JsonMapper json = JsonMapper.builder().build();
    private final JsonNode schemas;
    private final Map<Kind, Schema> compiled = new EnumMap<>(Kind.class);

    DefinitionSchemas() {
        try (InputStream in = DefinitionSchemas.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The schemas of layouts, record pages and applications are missing");
            }
            this.schemas = json.readTree(in).path("schemas");
        } catch (IOException e) {
            throw new IllegalStateException("The schemas of layouts, record pages and applications cannot be read");
        }
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        for (Kind kind : Kind.values()) {
            ObjectNode root = json.createObjectNode();
            root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            root.put("$ref", SCHEMAS_POINTER + kind.schemaName());
            root.putObject("components").set("schemas", schemas.deepCopy());
            compiled.put(kind, registry.getSchema(root));
        }
    }

    /** The schemas by name, as published in the OpenAPI document. A copy: callers may change it. */
    ObjectNode publishedSchemas() {
        return (ObjectNode) schemas.deepCopy();
    }

    /**
     * Every place where the document does not fit its schema; empty when it fits.
     *
     * @param prefix what to call the document in the paths, for example {@code definition}
     */
    List<Issue> validate(Kind kind, JsonNode document, String prefix) {
        List<Error> errors = compiled.get(kind).validate(document);
        Set<Issue> issues = new LinkedHashSet<>();
        for (Error error : errors) {
            issues.add(new Issue(pathOf(prefix, error), plain(error)));
            if (issues.size() >= MOST_ISSUES) {
                break;
            }
        }
        return new ArrayList<>(issues);
    }

    /**
     * The path of the place, from the location of the error (a JSON pointer such as {@code /sections/0/fields/1}) and,
     * for a missing or unexpected property, its name: {@code definition.sections[0].fields[1].field}.
     */
    private static String pathOf(String prefix, Error error) {
        StringBuilder path = new StringBuilder(prefix);
        for (String part : error.getInstanceLocation().toString().split("/")) {
            if (part.isEmpty() || part.equals("$")) {
                continue;
            }
            if (part.chars().allMatch(Character::isDigit)) {
                path.append('[').append(part).append(']');
            } else {
                path.append('.').append(part);
            }
        }
        String property = error.getProperty();
        String keyword = error.getKeyword();
        boolean namesProperty = "required".equals(keyword) || "unevaluatedProperties".equals(keyword)
                || "additionalProperties".equals(keyword);
        if (namesProperty && property != null && !property.isBlank()) {
            path.append('.').append(property);
        }
        return path.toString();
    }

    private static String plain(Error error) {
        String keyword = error.getKeyword() == null ? "" : error.getKeyword();
        JsonNode rule = error.getSchemaNode();
        return switch (keyword) {
            case "required" -> "This is needed here and is missing.";
            case "unevaluatedProperties", "additionalProperties" -> "This is not allowed here.";
            case "enum" -> "Must be one of: " + listOf(rule) + ".";
            case "const" -> "Must be " + (rule == null ? "a fixed value" : rule.asString()) + ".";
            case "type" -> "Must be " + typeWords(rule) + ".";
            case "pattern" -> "Does not have the allowed shape.";
            case "format" -> "Is not in the allowed format.";
            case "minItems" -> "Needs at least " + number(rule) + " entries.";
            case "maxItems" -> "May have at most " + number(rule) + " entries.";
            case "uniqueItems" -> "Must not repeat an entry.";
            case "minLength" -> "Needs at least " + number(rule) + " characters.";
            case "maxLength" -> "May have at most " + number(rule) + " characters.";
            case "minimum" -> "Must be at least " + number(rule) + ".";
            case "maximum" -> "Must be at most " + number(rule) + ".";
            case "oneOf", "anyOf" -> "Does not fit any of the allowed forms: name exactly one of the properties "
                    + "that this kind of entry takes.";
            case "not" -> "Has a property that does not go with the others.";
            case "false" -> "Does not belong with the type of this entry.";
            default -> "Is not allowed here.";
        };
    }

    private static String number(JsonNode rule) {
        return rule == null ? "the allowed number of" : rule.asString();
    }

    private static String listOf(JsonNode rule) {
        if (rule == null || !rule.isArray()) {
            return "the allowed values";
        }
        List<String> values = new ArrayList<>();
        rule.forEach(value -> values.add(value.asString()));
        return String.join(", ", values);
    }

    private static String typeWords(JsonNode rule) {
        if (rule == null) {
            return "of another type";
        }
        if (rule.isArray()) {
            List<String> types = new ArrayList<>();
            rule.forEach(type -> types.add(article(type.asString())));
            return String.join(" or ", types);
        }
        return article(rule.asString());
    }

    private static String article(String type) {
        return switch (type) {
            case "object" -> "an object";
            case "array" -> "a list";
            case "string" -> "text";
            case "integer" -> "a whole number";
            case "number" -> "a number";
            case "boolean" -> "true or false";
            default -> "of another type";
        };
    }
}
