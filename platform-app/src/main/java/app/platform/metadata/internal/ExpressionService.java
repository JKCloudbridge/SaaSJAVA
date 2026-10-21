package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.Expression;
import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionType;
import app.platform.expression.Purpose;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.ValidationRule;
import app.platformapi.ApiException;
import app.platformapi.CheckExpressionRequest;
import app.platformapi.ExpressionCheckView;
import app.platformapi.ExpressionTestView;
import app.platformapi.FormulaReportItemView;
import app.platformapi.FunctionView;
import app.platformapi.TestExpressionRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Checking an expression and trying it on sample values, without records (ADR-0079). This is how the browser gets
 * immediate feedback in Sprint 13 without a second engine in TypeScript: it asks the real one. Nothing is stored, and
 * nothing the person typed (the expression, the sample values, the answer) is logged or audited, because it is text of
 * the organization. It runs inside the transaction that {@link MetadataAdministration} opened after checking the
 * caller's abilities.
 */
@Service
class ExpressionService {

    private final MetadataCatalogue catalogue;
    private final ExpressionSupport support;

    ExpressionService(MetadataCatalogue catalogue, ExpressionSupport support) {
        this.catalogue = catalogue;
        this.support = support;
    }

    // ---- checking ----

    /** Whether the expression is valid for the object, where it is wrong when it is not, the type and the fields. */
    ExpressionCheckView check(CheckExpressionRequest request) {
        ObjectDefinition object = requireObject(request.objectApiName());
        Purpose purpose = purposeOf(request.purpose());
        ExpressionType expected = expectedOf(purpose, request.resultType());
        ExpressionEngine engine = support.engine();
        Expression parsed;
        try {
            parsed = engine.parse(request.expression());
        } catch (ExpressionException problem) {
            return new ExpressionCheckView(false, ExpressionSupport.problem(problem), null, List.of(), false);
        }
        try {
            Compiled compiled = engine.compile(request.expression(), ExpressionSupport.schemaOf(object), purpose,
                    expected);
            return new ExpressionCheckView(true, null, compiled.type().name(), compiled.fields(),
                    compiled.usesClock());
        } catch (ExpressionException problem) {
            return new ExpressionCheckView(false, ExpressionSupport.problem(problem), null, parsed.fields(), false);
        }
    }

    // ---- trying ----

    /** What an expression that is not stored yet would do on the sample values. */
    ExpressionTestView test(TestExpressionRequest request) {
        ObjectDefinition object = requireObject(request.objectApiName());
        Purpose purpose = purposeOf(request.purpose());
        Map<String, Object> values = support.valuesOf(object, request.values());
        return run(object, request.expression(), purpose, null, values, null);
    }

    /** What a stored validation rule would do on the sample values. */
    ExpressionTestView testRule(String objectApiName, String ruleApiName, Map<String, String> written) {
        ObjectDefinition object = requireObject(objectApiName);
        ValidationRule rule = catalogue.snapshot().validationRule(objectApiName, ruleApiName)
                .orElseThrow(() -> ApiException.notFound("This validation rule does not exist."));
        return run(object, rule.expression(), Purpose.VALIDATION_RULE, null, support.valuesOf(object, written),
                rule.errorMessage());
    }

    /** What a stored formula field would give on the sample values. */
    ExpressionTestView testFormula(String objectApiName, String fieldApiName, Map<String, String> written) {
        ObjectDefinition object = requireObject(objectApiName);
        FieldDefinition field = object.field(fieldApiName)
                .orElseThrow(() -> ApiException.notFound("This field does not exist."));
        if (!(field.configuration() instanceof FormulaConfiguration formula)) {
            throw ApiException.validation("field", "This field is not a formula.");
        }
        return run(object, formula.expression(), Purpose.FORMULA, ExpressionSupport.typeOf(formula.resultType()),
                support.valuesOf(object, written), null);
    }

    private ExpressionTestView run(ObjectDefinition object, String text, Purpose purpose, ExpressionType expected,
            Map<String, Object> values, String ruleMessage) {
        ExpressionEngine engine = support.engine();
        Compiled compiled;
        try {
            compiled = engine.compile(text, ExpressionSupport.schemaOf(object), purpose, expected);
        } catch (ExpressionException problem) {
            return error(null, problem);
        }
        try {
            Object result = engine.evaluate(compiled, values, Instant.now());
            String type = compiled.type().name();
            if (purpose == Purpose.VALIDATION_RULE) {
                boolean rejected = Boolean.TRUE.equals(result);
                return new ExpressionTestView(rejected ? "REJECTED" : "PASSED", type, null,
                        rejected ? ruleMessage : null, null);
            }
            return new ExpressionTestView("VALUE", type, engine.formatValue(result), null, null);
        } catch (ExpressionException problem) {
            return error(compiled.type().name(), problem);
        }
    }

    private static ExpressionTestView error(String type, ExpressionException problem) {
        return new ExpressionTestView("ERROR", type, null, null, ExpressionSupport.problem(problem));
    }

    // ---- the library and the report ----

    /** The functions of the language, so that no screen holds a list of its own. */
    List<FunctionView> functions() {
        return ExpressionEngine.functions().stream().map(function -> new FunctionView(function.name(),
                function.signature(), function.description(), function.rulesOnly())).toList();
    }

    /**
     * The stored formulas that do not pass the language (ADR-0060 point 8, ADR-0077): they were saved before formulas
     * were checked. Nothing is removed; each keeps its text and is reported until its text is fixed.
     */
    List<FormulaReportItemView> formulaReport() {
        List<FormulaReportItemView> report = new ArrayList<>();
        for (ObjectDefinition object : catalogue.objects()) {
            FormulaAnalysis analysis = FormulaAnalysis.of(object, support.engine(), support.maxFormulaDepth());
            for (FormulaAnalysis.Entry entry : analysis.entries().values()) {
                if (!entry.verified() && !entry.valid()) {
                    report.add(new FormulaReportItemView(object.apiName(), entry.field().apiName(),
                            entry.problem().getMessage()));
                }
            }
        }
        return report;
    }

    // ---- helpers ----

    private ObjectDefinition requireObject(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private static Purpose purposeOf(String purpose) {
        try {
            return Purpose.valueOf(purpose == null ? "" : purpose.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("purpose", "Choose FORMULA or VALIDATION_RULE.");
        }
    }

    private static ExpressionType expectedOf(Purpose purpose, String resultType) {
        if (resultType == null || resultType.isBlank()) {
            return null;
        }
        FieldType type = FieldType.fromCode(resultType.strip()).filter(FieldRules.FORMULA_RESULTS::contains)
                .orElseThrow(() -> ApiException.validation("resultType", "Choose a type a formula can give: text, "
                        + "number, decimal, currency, percent, checkbox, date, date and time or time."));
        return purpose == Purpose.FORMULA ? ExpressionSupport.typeOf(type) : null;
    }
}
