package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CheckExpressionRequest;
import app.platformapi.ExpressionCheckView;
import app.platformapi.ExpressionTestView;
import app.platformapi.FormulaReportItemView;
import app.platformapi.FunctionView;
import app.platformapi.TestExpressionRequest;
import app.platformapi.TestValuesRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checking and trying expressions, the list of functions and the report of stored formulas (Sprint 13, ADR-0079).
 * Thin: the work is in {@link ExpressionService}. Answered only on an organization host. Reading the list of functions
 * and the report needs {@code metadata.view}; checking and trying need {@code metadata.view} and either
 * {@code metadata.manage} or {@code metadata.publish}, as the check of a change set does. Nothing is stored and nothing
 * the person typed is logged.
 */
@RestController
@Tag(name = "Metadata")
class ExpressionController {

    private static final List<Ability> EITHER = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final String NOTHING_STORED = "Nothing is stored and nothing is logged. Needs metadata.view and "
            + "either metadata.manage or metadata.publish.";

    private final MetadataAdministration administration;
    private final ExpressionService service;

    ExpressionController(MetadataAdministration administration, ExpressionService service) {
        this.administration = administration;
        this.service = service;
    }

    @PostMapping(ApiPaths.METADATA_EXPRESSIONS + "/check")
    @Operation(
            operationId = "checkExpression",
            summary = "Is this expression valid for an object?",
            description = "Answers valid or not, and when not, where it is wrong (line and column) and what kind of "
                    + "problem it is; when valid, the type it gives and the fields it uses. A formula may also "
                    + "name the type it must give. " + NOTHING_STORED)
    ApiResponse<ExpressionCheckView> check(@Valid @RequestBody CheckExpressionRequest body) {
        return ApiResponse.of(administration.runAny("metadata.expression.check", Ability.METADATA_VIEW, EITHER,
                caller -> service.check(body)));
    }

    @PostMapping(ApiPaths.METADATA_EXPRESSIONS + "/test")
    @Operation(
            operationId = "testExpression",
            summary = "Try an expression that is not stored yet on sample values",
            description = "Answers what a formula would give (VALUE) or what a rule would do (REJECTED or PASSED), or "
                    + "ERROR with the place when the evaluation itself fails. There are no records yet, so the "
                    + "values are typed as text for the type of each field. " + NOTHING_STORED)
    ApiResponse<ExpressionTestView> test(@Valid @RequestBody TestExpressionRequest body) {
        return ApiResponse.of(administration.runAny("metadata.expression.test", Ability.METADATA_VIEW, EITHER,
                caller -> service.test(body)));
    }

    @PostMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}/fields/{fieldApiName}/test")
    @Operation(
            operationId = "testFormulaField",
            summary = "Try a formula field on sample values",
            description = "Answers what the formula would give for a record with these values (VALUE), or ERROR with "
                    + "the place. VALIDATION_ERROR when the field is not a formula. " + NOTHING_STORED)
    ApiResponse<ExpressionTestView> testFormula(@PathVariable String objectApiName,
            @PathVariable String fieldApiName, @Valid @RequestBody TestValuesRequest body) {
        return ApiResponse.of(administration.runAny("metadata.formula.test", Ability.METADATA_VIEW, EITHER,
                caller -> service.testFormula(objectApiName, fieldApiName, body.values())));
    }

    @GetMapping(ApiPaths.METADATA_EXPRESSIONS + "/functions")
    @Operation(
            operationId = "listExpressionFunctions",
            summary = "The functions of the expression language",
            description = "The fixed list: no function can be added by an organization. For members who may view "
                    + "objects and fields.")
    ApiResponse<List<FunctionView>> functions() {
        return ApiResponse.of(administration.run("metadata.expression.functions", Ability.METADATA_VIEW,
                caller -> service.functions()));
    }

    @GetMapping(ApiPaths.METADATA_FORMULA_REPORT)
    @Operation(
            operationId = "listInvalidStoredFormulas",
            summary = "The stored formulas that do not pass the expression language",
            description = "Formulas saved before formulas were checked keep their text and are listed here with the "
                    + "place that is wrong; nothing is removed and they cannot be saved again until fixed. For "
                    + "members who may view objects and fields.")
    ApiResponse<List<FormulaReportItemView>> formulaReport() {
        return ApiResponse.of(administration.run("metadata.formula.report", Ability.METADATA_VIEW,
                caller -> service.formulaReport()));
    }
}
