package app.platform.metadata.internal;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionType;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.ApiException;
import app.platformapi.ExpressionProblemView;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What the metadata module needs around the expression engine (ADR-0076, ADR-0077): how the fields of an object look to
 * an expression (their types), how a value typed as text becomes a value of its field, and how an engine error becomes
 * an answer of the API. One place, so the check endpoint, the dependency checks, the saving of a rule and the compiled
 * artefacts of the runtime all read a field in the same way.
 */
@Component
class ExpressionSupport {

    private final ExpressionEngine engine;
    private final MetadataProperties properties;

    ExpressionSupport(ExpressionEngine engine, MetadataProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    ExpressionEngine engine() {
        return engine;
    }

    /** How many formulas may be stacked. */
    int maxFormulaDepth() {
        return properties.limits().maxFormulaDepth();
    }

    /**
     * The type an expression sees in a field type: decimals, currency and percent are all numbers; e-mail, phone, URL,
     * picklists, the identifier of a lookup and an auto-number are all text.
     */
    static ExpressionType typeOf(FieldType type) {
        return switch (type) {
            case NUMBER, DECIMAL, CURRENCY, PERCENT -> ExpressionType.NUMBER;
            case BOOLEAN -> ExpressionType.BOOLEAN;
            case DATE -> ExpressionType.DATE;
            case DATETIME -> ExpressionType.DATETIME;
            case TIME -> ExpressionType.TIME;
            case TEXT, LONG_TEXT, EMAIL, PHONE, URL, PICKLIST, MULTI_PICKLIST, LOOKUP, MASTER_DETAIL, AUTO_NUMBER,
                    FORMULA -> ExpressionType.TEXT;
        };
    }

    /** The type an expression sees in a field: a formula field has the type it is declared to give. */
    static ExpressionType typeOf(FieldDefinition field) {
        if (field.configuration() instanceof FormulaConfiguration formula) {
            return typeOf(formula.resultType());
        }
        return typeOf(field.type());
    }

    /** Every field of the object (retired ones too: they still exist) by API name. */
    static Map<String, ExpressionType> schemaOf(ObjectDefinition object) {
        Map<String, ExpressionType> schema = new LinkedHashMap<>();
        for (FieldDefinition field : object.fields()) {
            schema.put(field.apiName(), typeOf(field));
        }
        return schema;
    }

    /**
     * Turns sample values written as text into values of the fields they are for.
     *
     * @throws ApiException {@code VALIDATION_ERROR} naming each value that is not for a field of the object or does not
     *         fit its field's type
     */
    Map<String, Object> valuesOf(ObjectDefinition object, Map<String, String> written) {
        Map<String, ExpressionType> schema = schemaOf(object);
        Map<String, Object> values = new HashMap<>();
        Map<String, List<String>> problems = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : written.entrySet()) {
            ExpressionType type = schema.get(entry.getKey());
            if (type == null) {
                problems.put("values." + entry.getKey(), List.of("This object has no such field."));
                continue;
            }
            try {
                values.put(entry.getKey(), engine.parseValue(type, entry.getValue(), entry.getKey()));
            } catch (ExpressionException invalid) {
                problems.put("values." + entry.getKey(), List.of(invalid.problem()));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return values;
    }

    /** An engine error as the API shows it. */
    static ExpressionProblemView problem(ExpressionException error) {
        ExpressionException.Position at = error.position();
        return new ExpressionProblemView(error.kind().name(), error.getMessage(), at == null ? null : at.line(),
                at == null ? null : at.column());
    }
}
