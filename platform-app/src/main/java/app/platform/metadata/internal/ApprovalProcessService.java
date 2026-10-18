package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionException;
import app.platform.expression.Purpose;
import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.licensing.Entitlements;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.approval.ApprovalUsage;
import app.platformapi.ApiException;
import app.platformapi.ApprovalCatalogueView;
import app.platformapi.ApprovalOutcomesSpec;
import app.platformapi.ApprovalProcessCheckView;
import app.platformapi.ApprovalProcessView;
import app.platformapi.ApprovalStepSpec;
import app.platformapi.CheckApprovalProcessRequest;
import app.platformapi.CreateApprovalProcessRequest;
import app.platformapi.ErrorCode;
import app.platformapi.UpdateApprovalProcessRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The rules of approval processes (ADR-0145). Like {@link WorkflowService} it runs inside the transaction that
 * {@link MetadataAdministration} opened, and it changes published definitions only. What it checks is what can be
 * checked
 * of the request alone ({@link ApprovalProcessRules}); whether the fields exist, whether the criteria give true or
 * false
 * and whether the outcome literals fit is checked by the lifecycle after the change
 * ({@link ApprovalProcessDependencies}).
 *
 * <p><strong>Versions.</strong> A change of the criteria, the steps, the outcomes, the fallback or the self-approval
 * rule
 * writes a new immutable version and points the process at it; a change of the label, the description or only whether
 * the process is active does not. Requests already pending keep the version they started under.
 *
 * <p><strong>Who may.</strong> Whoever applies a change needs {@code approvals.manage}, also when it comes out of a
 * change
 * set; a process may be switched on, or changed while it is on, only when the plan has the feature {@code approvals}.
 * Switching off, changing a process that is off and removing one that has no pending request are never refused for the
 * plan. A process with a pending request is not removed (ADR-0152).
 */
@Service
class ApprovalProcessService {

    /** The feature that gates approvals (the licensing catalogue of Sprint 6). */
    static final String FEATURE = "approvals";

    private final ApprovalProcessStore store;
    private final ApprovalProcessCodec codec;
    private final ApprovalProcessRules rules;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final MetadataAudit audit;
    private final MetadataProperties properties;
    private final Permissions permissions;
    private final Entitlements entitlements;
    private final ExpressionSupport support;
    private final Clock clock;
    private final ApprovalPlatformLimits platform;
    private final ObjectProvider<ApprovalUsage> usage;

    ApprovalProcessService(ApprovalProcessStore store, ApprovalProcessCodec codec, ApprovalProcessRules rules,
            MetadataCatalogue catalogue, MetadataVersions versions, MetadataAudit audit,
            MetadataProperties properties, Permissions permissions, Entitlements entitlements,
            ExpressionSupport support, Clock clock, ApprovalPlatformLimits platform,
            ObjectProvider<ApprovalUsage> usage) {
        this.store = store;
        this.codec = codec;
        this.rules = rules;
        this.catalogue = catalogue;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
        this.permissions = permissions;
        this.entitlements = entitlements;
        this.support = support;
        this.clock = clock;
        this.platform = platform;
        this.usage = usage;
    }

    // ---- reading ----

    /** The processes of one object, or of every object when none is named. */
    List<ApprovalProcessView> processes(String objectApiName) {
        if (objectApiName != null) {
            requireObject(objectApiName);
        }
        return store.all().stream().filter(row -> objectApiName == null || row.objectApiName().equals(objectApiName))
                .map(codec::view).toList();
    }

    ApprovalProcessView process(String objectApiName, String apiName) {
        requireObject(objectApiName);
        return codec.view(store.find(objectApiName, apiName).orElseThrow(ApprovalProcessService::missing));
    }

    // ---- what a process can be made of ----

    /** The kinds of approver, the completion rules, the states and the limits, as the browser builds its forms from. */
    ApprovalCatalogueView catalogue() {
        List<ApprovalCatalogueView.ApproverKind> kinds = Arrays.stream(ApprovalProcessDefinition.ApproverKind.values())
                .map(kind -> new ApprovalCatalogueView.ApproverKind(kind.name(), kind.summary(), kind.names().name()))
                .toList();
        List<ApprovalCatalogueView.Choice> completions = Arrays.stream(ApprovalProcessDefinition.Completion.values())
                .map(completion -> new ApprovalCatalogueView.Choice(completion.name(), completion.summary())).toList();
        List<ApprovalCatalogueView.Choice> outcomes = Arrays.stream(ApprovalProcessDefinition.Outcome.values())
                .map(outcome -> new ApprovalCatalogueView.Choice(outcome.name(), outcome.summary())).toList();
        List<ApprovalCatalogueView.Choice> states = List.of(
                new ApprovalCatalogueView.Choice("PENDING", "Waiting for a decision. The record is locked."),
                new ApprovalCatalogueView.Choice("APPROVED", "Every step was approved."),
                new ApprovalCatalogueView.Choice("REJECTED", "A person rejected a step."),
                new ApprovalCatalogueView.Choice("RECALLED", "The request was withdrawn."),
                new ApprovalCatalogueView.Choice("CANCELLED", "An administrator ended the request."));
        List<ApprovalCatalogueView.Choice> stepStates = List.of(
                new ApprovalCatalogueView.Choice("WAITING", "Not reached yet."),
                new ApprovalCatalogueView.Choice("ACTIVE", "Waiting for a decision."),
                new ApprovalCatalogueView.Choice("APPROVED", "Approved."),
                new ApprovalCatalogueView.Choice("REJECTED", "Rejected."),
                new ApprovalCatalogueView.Choice("SKIPPED", "Passed over because the request ended."));
        MetadataProperties.Limits limits = properties.limits();
        return new ApprovalCatalogueView(kinds, completions, outcomes, states, stepStates,
                new ApprovalCatalogueView.Limits(limits.maxApprovalSteps(), limits.maxApproversPerStep(),
                        limits.maxApprovalOutcomeFields(), platform.commentLength(), platform.delegationDays()));
    }

    // ---- trying the criteria, the steps and the outcomes ----

    /**
     * What the criteria would do for a record with the sample values, and what is wrong with the steps and the outcomes
     * that were handed in for the object. The real engine and the real checks answer; nothing is stored, audited or
     * logged, and no part of the request is repeated in the answer.
     */
    ApprovalProcessCheckView check(String objectApiName, CheckApprovalProcessRequest request) {
        ObjectDefinition object = requireObject(objectApiName);
        Map<String, List<String>> problems = fieldProblems(object, request.steps(), request.outcomes());
        if (request.entryCriteria() == null || request.entryCriteria().isBlank()) {
            return new ApprovalProcessCheckView("RUNS", null, problems);
        }
        Compiled compiled;
        try {
            compiled = support.engine().compile(request.entryCriteria().strip(), ExpressionSupport.schemaOf(object),
                    Purpose.APPROVAL_ENTRY);
        } catch (ExpressionException problem) {
            throw ApiException.validation("entryCriteria", problem.getMessage());
        }
        Map<String, Object> values = support.valuesOf(object, request.values());
        try {
            return Boolean.TRUE.equals(support.engine().evaluate(compiled, values, clock.instant()))
                    ? new ApprovalProcessCheckView("RUNS", null, problems)
                    : new ApprovalProcessCheckView("SKIPPED", null, problems);
        } catch (ExpressionException problem) {
            return new ApprovalProcessCheckView("ERROR", ExpressionSupport.problem(problem), problems);
        }
    }

    private Map<String, List<String>> fieldProblems(ObjectDefinition object, List<ApprovalStepSpec> asked,
            ApprovalOutcomesSpec outcomes) {
        Problems shape = new Problems();
        List<ApprovalStepSpec> steps = asked.isEmpty() ? List.of() : rules.checkSteps(shape, asked);
        ApprovalOutcomesSpec cleaned = outcomes == null ? ApprovalOutcomesSpec.none()
                : rules.checkOutcomes(shape, outcomes);
        Map<String, List<String>> found = new LinkedHashMap<>(shape.all());
        if (shape.any()) {
            return found;
        }
        ApprovalProcessChecks.check(object, catalogue::object,
                (steps == null ? List.<ApprovalStepSpec>of() : steps).stream().map(ApprovalProcessCodec::step).toList(),
                cleaned == null ? ApprovalProcessDefinition.Outcomes.none() : ApprovalProcessCodec.outcomes(cleaned),
                null, new WorkflowActionChecks.Report() {
                    @Override
                    public void needsField(String needed, String field, String relation) {
                        // Edges matter to the graph only.
                    }

                    @Override
                    public void needsObject(String needed, String relation) {
                        // Edges matter to the graph only.
                    }

                    @Override
                    public void problem(String place, String message) {
                        note(place, message);
                    }

                    @Override
                    public void missing(String place, String message) {
                        note(place, message);
                    }

                    private void note(String place, String message) {
                        List<String> texts = new ArrayList<>(found.getOrDefault(place, List.of()));
                        if (!texts.contains(message)) {
                            texts.add(message);
                        }
                        found.put(place, texts);
                    }
                });
        return found;
    }

    // ---- changing ----

    ApprovalProcessView create(Caller caller, String objectApiName, CreateApprovalProcessRequest request) {
        requireManage(caller);
        eligible(objectApiName);
        Problems problems = new Problems();
        String apiName = problems.check(() -> NameRules.objectApiName(request.name()));
        ApprovalProcessRules.Checked checked = rules.check(problems, request.label(), request.description(),
                request.entryCriteria(), request.allowSelfApproval(), request.steps(), request.outcomes(),
                request.fallback());
        problems.throwIfAny();
        if (store.count(objectApiName) >= properties.limits().maxApprovalProcessesPerObject()) {
            throw new ApiException(ErrorCode.CONFLICT, "The object has reached the most approval processes it may "
                    + "have (" + properties.limits().maxApprovalProcessesPerObject() + ").");
        }
        if (store.nameTaken(objectApiName, apiName)) {
            throw ApiException.validation("name", "An approval process with this name exists already on this object.");
        }
        boolean active = request.active() == null || request.active();
        if (active) {
            requireFeature();
        }
        versions.wrote();
        ApprovalProcessStore.Row row;
        try {
            row = store.insert(objectApiName, apiName, checked.label(), checked.description(), active,
                    checked.entryCriteria(), checked.allowSelfApproval(), codec.writeSteps(checked.steps()),
                    codec.writeOutcomes(checked.outcomes()), codec.writeFallback(checked.fallback()), caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("name", "An approval process with this name exists already on this object.");
        }
        audit.approvalProcessCreated(caller.userId(), objectApiName, apiName, active, checked.steps().size());
        return codec.view(row);
    }

    ApprovalProcessView update(Caller caller, String objectApiName, String apiName,
            UpdateApprovalProcessRequest request) {
        requireManage(caller);
        eligible(objectApiName);
        ApprovalProcessStore.Row row = store.find(objectApiName, apiName).orElseThrow(ApprovalProcessService::missing);
        Problems problems = new Problems();
        ApprovalProcessRules.Checked checked = rules.check(problems, request.label(), request.description(),
                request.entryCriteria(), request.allowSelfApproval(), request.steps(), request.outcomes(),
                request.fallback());
        problems.throwIfAny();
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        boolean definitionChanged = !Objects.equals(checked.entryCriteria(), row.entryCriteria())
                || checked.allowSelfApproval() != row.allowSelfApproval()
                || !codec.writeSteps(checked.steps()).equals(codec.writeSteps(codec.readSteps(row.steps())))
                || !codec.writeOutcomes(checked.outcomes())
                        .equals(codec.writeOutcomes(codec.readOutcomes(row.outcomes())))
                || !Objects.equals(codec.writeFallback(checked.fallback()),
                        codec.writeFallback(codec.readFallback(row.fallback())));
        List<String> changed = new ArrayList<>();
        addIf(changed, "label", !checked.label().equals(row.label()));
        addIf(changed, "description", !checked.description().equals(row.description()));
        addIf(changed, "active", request.active() != row.active());
        addIf(changed, "definition", definitionChanged);
        if (changed.isEmpty()) {
            return codec.view(row);
        }
        if (request.active() && (!row.active() || definitionChanged)) {
            requireFeature();
        }
        int definitionVersion = row.definitionVersion();
        versions.wrote();
        if (definitionChanged) {
            definitionVersion++;
            if (definitionVersion > properties.limits().maxApprovalVersions()) {
                throw new ApiException(ErrorCode.CONFLICT, "This approval process has been through the most "
                        + "definitions it may have (" + properties.limits().maxApprovalVersions() + "). Make a new "
                        + "process instead.");
            }
            store.insertVersion(row.id(), definitionVersion, checked.entryCriteria(), checked.allowSelfApproval(),
                    codec.writeSteps(checked.steps()), codec.writeOutcomes(checked.outcomes()),
                    codec.writeFallback(checked.fallback()), caller.userId());
        }
        if (!store.update(row.id(), request.version(), checked.label(), checked.description(), request.active(),
                definitionVersion, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (changed.equals(List.of("active"))) {
            audit.approvalProcessActivated(caller.userId(), objectApiName, apiName, request.active());
        } else {
            audit.approvalProcessUpdated(caller.userId(), objectApiName, apiName, String.join(",", changed),
                    request.active(), definitionVersion);
        }
        return codec.view(store.find(objectApiName, apiName).orElseThrow(ApprovalProcessService::missing));
    }

    void delete(Caller caller, String objectApiName, String apiName) {
        requireManage(caller);
        requireObject(objectApiName);
        ApprovalProcessStore.Row row = store.find(objectApiName, apiName).orElseThrow(ApprovalProcessService::missing);
        ApprovalUsage pending = usage.getIfAvailable();
        int waiting = pending == null ? 0 : pending.pendingUnder(objectApiName, apiName);
        if (waiting > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This approval process has " + waiting + " request"
                    + (waiting == 1 ? "" : "s") + " waiting for a decision. Switch it off so that no new request "
                    + "starts, and remove it when they have ended.");
        }
        versions.wrote();
        store.delete(row.id(), caller.userId());
        audit.approvalProcessDeleted(caller.userId(), objectApiName, apiName);
    }

    // ---- helpers ----

    private void requireManage(Caller caller) {
        if (!permissions.has(caller.membershipId(), Ability.APPROVALS_MANAGE)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireFeature() {
        if (!entitlements.enabled(FEATURE)) {
            throw new ApiException(ErrorCode.CONFLICT, "Approvals are not part of this organization's plan.");
        }
    }

    private ObjectDefinition requireObject(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private void eligible(String objectApiName) {
        ObjectDefinition object = requireObject(objectApiName);
        if (!RecordTypeRules.allowedOn(object)) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform, so it "
                    + "cannot have an approval process.");
        }
    }

    private static void addIf(List<String> changed, String what, boolean differs) {
        if (differs) {
            changed.add(what);
        }
    }

    private static ApiException missing() {
        return ApiException.notFound("This approval process does not exist.");
    }
}
