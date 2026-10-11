package app.platform.metadata.internal;

import app.platform.expression.ExpressionException;
import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.ValidationRule;
import app.platformapi.ApiException;
import app.platformapi.CreateValidationRuleRequest;
import app.platformapi.ErrorCode;
import app.platformapi.UpdateValidationRuleRequest;
import app.platformapi.ValidationRuleView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The rules of validation rules (ADR-0078). Like {@link DefinitionService} it runs inside the transaction that
 * {@link MetadataAdministration} opened after checking the caller's ability, and it changes published definitions only.
 * What it checks is what can be checked of the request alone: the shape of the names, the bounds of the texts, and that
 * the condition follows the grammar of the expression language. Whether the fields the condition uses exist, whether it
 * gives true or false and whether the field of the message exists is checked by the lifecycle after the change
 * ({@link ExpressionDependencies}), because a group of changes may fix a dependency again.
 */
@Service
class ValidationRuleService {

    private final ValidationRuleStore store;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final MetadataAudit audit;
    private final MetadataProperties properties;
    private final ExpressionSupport support;

    ValidationRuleService(ValidationRuleStore store, MetadataCatalogue catalogue, MetadataVersions versions,
            MetadataAudit audit, MetadataProperties properties, ExpressionSupport support) {
        this.store = store;
        this.catalogue = catalogue;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
        this.support = support;
    }

    // ---- reading ----

    List<ValidationRuleView> rules(String objectApiName) {
        requireObject(objectApiName);
        return catalogue.snapshot().validationRules(objectApiName).stream().map(ValidationRuleService::view)
                .toList();
    }

    ValidationRuleView rule(String objectApiName, String apiName) {
        requireObject(objectApiName);
        return view(catalogue.snapshot().validationRule(objectApiName, apiName).orElseThrow(
                ValidationRuleService::missing));
    }

    // ---- changing ----

    ValidationRuleView create(Caller caller, String objectApiName, CreateValidationRuleRequest request) {
        eligible(objectApiName);
        Problems problems = new Problems();
        String apiName = problems.check(() -> NameRules.objectApiName(request.name()));
        Checked checked = check(problems, request.label(), request.description(), request.expression(),
                request.errorMessage(), request.errorField());
        problems.throwIfAny();
        if (store.count(objectApiName) >= properties.limits().maxValidationRulesPerObject()) {
            throw new ApiException(ErrorCode.CONFLICT, "The object has reached the most validation rules it may have ("
                    + properties.limits().maxValidationRulesPerObject() + ").");
        }
        if (store.nameTaken(objectApiName, apiName)) {
            throw ApiException.validation("name", "A validation rule with this name exists already on this object.");
        }
        boolean active = request.active() == null || request.active();
        versions.wrote();
        ValidationRuleStore.Row row;
        try {
            row = store.insert(objectApiName, apiName, checked.label(), checked.description(), checked.expression(),
                    checked.errorMessage(), checked.errorField(), active, caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("name", "A validation rule with this name exists already on this object.");
        }
        audit.validationRuleCreated(caller.userId(), objectApiName, apiName, active, checked.fieldsUsed());
        return view(row.rule());
    }

    ValidationRuleView update(Caller caller, String objectApiName, String apiName,
            UpdateValidationRuleRequest request) {
        eligible(objectApiName);
        ValidationRuleStore.Row row = store.find(objectApiName, apiName).orElseThrow(ValidationRuleService::missing);
        Problems problems = new Problems();
        Checked checked = check(problems, request.label(), request.description(), request.expression(),
                request.errorMessage(), request.errorField());
        problems.throwIfAny();
        ValidationRule before = row.rule();
        if (before.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        List<String> changed = new ArrayList<>();
        addIf(changed, "label", !checked.label().equals(before.label()));
        addIf(changed, "description", !checked.description().equals(before.description()));
        addIf(changed, "expression", !checked.expression().equals(before.expression()));
        addIf(changed, "errorMessage", !checked.errorMessage().equals(before.errorMessage()));
        addIf(changed, "errorField", !Objects.equals(checked.errorField(), before.errorField()));
        addIf(changed, "active", request.active() != before.active());
        if (changed.isEmpty()) {
            return view(before);
        }
        versions.wrote();
        if (!store.update(row.id(), request.version(), checked.label(), checked.description(), checked.expression(),
                checked.errorMessage(), checked.errorField(), request.active(), caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.validationRuleUpdated(caller.userId(), objectApiName, apiName, String.join(",", changed),
                request.active(), checked.fieldsUsed());
        return view(store.find(objectApiName, apiName).orElseThrow(ValidationRuleService::missing).rule());
    }

    void delete(Caller caller, String objectApiName, String apiName) {
        requireObject(objectApiName);
        ValidationRuleStore.Row row = store.find(objectApiName, apiName).orElseThrow(ValidationRuleService::missing);
        versions.wrote();
        store.delete(row.id(), caller.userId());
        audit.validationRuleDeleted(caller.userId(), objectApiName, apiName);
    }

    // ---- the checks of a request ----

    /** The checked and cleaned form of what a request carries. */
    private record Checked(String label, String description, String expression, String errorMessage,
            String errorField, int fieldsUsed) {
    }

    /** Checks every part of the request and keeps every problem found, each under the name of the request field. */
    private Checked check(Problems problems, String label, String description, String expression,
            String errorMessage, String errorField) {
        String cleanLabel = problems.check(() -> NameRules.label("label", label));
        String cleanDescription = problems.check(() -> NameRules.description(description));
        String cleanMessage = problems.check(() -> NameRules.message("errorMessage", errorMessage));
        String cleanField = problems.check(() -> NameRules.fieldReference("errorField", errorField));
        String text = expression == null ? "" : expression.strip();
        int fieldsUsed = 0;
        try {
            fieldsUsed = support.engine().parse(text).fields().size();
        } catch (ExpressionException problem) {
            problems.add("expression", problem.getMessage());
        }
        return new Checked(cleanLabel, cleanDescription, text, cleanMessage, cleanField, fieldsUsed);
    }

    // ---- helpers ----

    private ObjectDefinition requireObject(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private void eligible(String objectApiName) {
        ObjectDefinition object = requireObject(objectApiName);
        if (!RecordTypeRules.allowedOn(object)) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform, so it "
                    + "cannot have a validation rule.");
        }
    }

    private static void addIf(List<String> changed, String what, boolean differs) {
        if (differs) {
            changed.add(what);
        }
    }

    private static ApiException missing() {
        return ApiException.notFound("This validation rule does not exist.");
    }

    static ValidationRuleView view(ValidationRule rule) {
        return new ValidationRuleView(rule.apiName(), rule.objectApiName(), rule.label(), rule.description(),
                rule.expression(), rule.errorMessage(), rule.errorField(), rule.active(), rule.version());
    }
}
