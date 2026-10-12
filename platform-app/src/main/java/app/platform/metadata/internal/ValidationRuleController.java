package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateValidationRuleRequest;
import app.platformapi.ExpressionTestView;
import app.platformapi.TestValuesRequest;
import app.platformapi.UpdateValidationRuleRequest;
import app.platformapi.ValidationRuleView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The validation rules of an object (Sprint 13, ADR-0078). Thin: the rules are in {@link ValidationRuleService}.
 * Answered only on an organization host; reading needs {@code metadata.view}; a change is made live at once, which is a
 * publication of one change, so it needs {@code metadata.manage} and {@code metadata.publish} (ADR-0068); trying a rule
 * on sample values needs {@code metadata.view} and either of the other two, as checking a change set does. Several
 * changes that belong together go through a change set instead.
 */
@RestController
@Tag(name = "Metadata")
class ValidationRuleController {

    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final String RULES = ApiPaths.METADATA_OBJECTS + "/{objectApiName}/validation-rules";
    private static final String CONDITION_NOTE = "The condition is in the expression language and is true when the "
            + "record must be REJECTED; a blank answer does not reject. It is parsed here (a VALIDATION_ERROR names "
            + "the place); whether its fields exist and whether it gives true or false is checked after the change, "
            + "and a CONFLICT names what is wrong.";

    private final MetadataAdministration administration;
    private final ValidationRuleService service;
    private final ExpressionService expressions;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    ValidationRuleController(MetadataAdministration administration, ValidationRuleService service,
            ExpressionService expressions, MetadataLifecycle lifecycle, ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.expressions = expressions;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    @GetMapping(RULES)
    @Operation(
            operationId = "listValidationRules",
            summary = "The validation rules of an object",
            description = "For members who may view objects and fields. NOT_FOUND for an object the organization "
                    + "does not have.")
    ApiResponse<List<ValidationRuleView>> list(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.validationrule.list", Ability.METADATA_VIEW,
                caller -> service.rules(objectApiName)));
    }

    @GetMapping(RULES + "/{ruleApiName}")
    @Operation(
            operationId = "getValidationRule",
            summary = "One validation rule",
            description = "NOT_FOUND for a rule the object does not have.")
    ApiResponse<ValidationRuleView> get(@PathVariable String objectApiName, @PathVariable String ruleApiName) {
        return ApiResponse.of(administration.run("metadata.validationrule.get", Ability.METADATA_VIEW,
                caller -> service.rule(objectApiName, ruleApiName)));
    }

    @PostMapping(RULES)
    @Operation(
            operationId = "createValidationRule",
            summary = "Add a validation rule to an object",
            description = "Live at once, as a release of one change. The name is turned into the permanent API name "
                    + "with the ending __c. " + CONDITION_NOTE + " CONFLICT for an object whose records belong to "
                    + "the platform, at the limit, or when the result would break a dependency. Audited by name and "
                    + "counts, never by the text.")
    ResponseEntity<ApiResponse<ValidationRuleView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateValidationRuleRequest body) {
        ValidationRuleView created = administration.runAll("metadata.validationrule.create", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.CREATE_VALIDATION_RULE, objectApiName, null, body),
                        ValidationRuleView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(RULES + "/{ruleApiName}")
    @Operation(
            operationId = "updateValidationRule",
            summary = "Change a validation rule",
            description = "Live at once, as a release of one change. The API name and the object never change. "
                    + CONDITION_NOTE + " CONCURRENT_MODIFICATION when someone changed it since it was read.")
    ApiResponse<ValidationRuleView> update(@PathVariable String objectApiName, @PathVariable String ruleApiName,
            @Valid @RequestBody UpdateValidationRuleRequest body) {
        return ApiResponse.of(administration.runAll("metadata.validationrule.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_VALIDATION_RULE, objectApiName, ruleApiName, body),
                        ValidationRuleView.class)));
    }

    @DeleteMapping(RULES + "/{ruleApiName}")
    @Operation(
            operationId = "deleteValidationRule",
            summary = "Remove a validation rule",
            description = "Live at once, as a release of one change. The undo of the release makes it again.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable String ruleApiName) {
        administration.runAll("metadata.validationrule.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_VALIDATION_RULE, objectApiName, ruleApiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(RULES + "/{ruleApiName}/test")
    @Operation(
            operationId = "testValidationRule",
            summary = "Try a validation rule on sample values",
            description = "Answers what would happen to a record with these values: REJECTED with the message of "
                    + "the rule, PASSED, or ERROR with the place when the evaluation itself fails (for example a "
                    + "division by zero). There are no records yet, so the values are typed as text for the type of "
                    + "each field. Nothing is stored and nothing is logged. Needs metadata.view and either "
                    + "metadata.manage or metadata.publish.")
    ApiResponse<ExpressionTestView> test(@PathVariable String objectApiName, @PathVariable String ruleApiName,
            @Valid @RequestBody TestValuesRequest body) {
        return ApiResponse.of(administration.runAny("metadata.validationrule.test", Ability.METADATA_VIEW, LIVE,
                caller -> expressions.testRule(objectApiName, ruleApiName, body.values())));
    }
}
