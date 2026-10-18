package app.platform.metadata.internal;

import app.platform.metadata.ApprovalProcessDefinition;
import app.platformapi.ApprovalApproverSpec;
import app.platformapi.ApprovalFieldSpec;
import app.platformapi.ApprovalOutcomesSpec;
import app.platformapi.ApprovalProcessView;
import app.platformapi.ApprovalStepSpec;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the steps, the outcomes and the fallback of an approval process into the JSON the database keeps and back, and
 * a
 * stored row into the public {@link ApprovalProcessDefinition} and the API's {@link ApprovalProcessView} (ADR-0145).
 * The
 * stored form is the very request record the API takes, so what is saved is what was checked. Nothing here evaluates
 * anything.
 */
@Component
class ApprovalProcessCodec {

    private static final TypeReference<List<ApprovalStepSpec>> STEPS = new TypeReference<>() {
    };

    private final JsonMapper json = JsonMapper.builder().build();

    String writeSteps(List<ApprovalStepSpec> steps) {
        return json.writeValueAsString(steps);
    }

    String writeOutcomes(ApprovalOutcomesSpec outcomes) {
        return json.writeValueAsString(outcomes);
    }

    /** The fallback as JSON, or null for none. */
    String writeFallback(ApprovalApproverSpec fallback) {
        return fallback == null ? null : json.writeValueAsString(fallback);
    }

    List<ApprovalStepSpec> readSteps(String stored) {
        return json.readValue(stored, STEPS);
    }

    ApprovalOutcomesSpec readOutcomes(String stored) {
        return json.readValue(stored, ApprovalOutcomesSpec.class);
    }

    ApprovalApproverSpec readFallback(String stored) {
        return stored == null ? null : json.readValue(stored, ApprovalApproverSpec.class);
    }

    /**
     * The public definition of a stored process.
     *
     * @throws IllegalStateException when the stored definition does not fit this release (the message names no value)
     */
    ApprovalProcessDefinition definition(ApprovalProcessStore.Row row) {
        List<ApprovalProcessDefinition.Step> steps = readSteps(row.steps()).stream().map(ApprovalProcessCodec::step)
                .toList();
        ApprovalApproverSpec fallback = readFallback(row.fallback());
        return new ApprovalProcessDefinition(row.id(), row.versionId(), row.objectApiName(), row.apiName(), row.label(),
                row.description(), row.active(), row.definitionVersion(), row.entryCriteria(), row.allowSelfApproval(),
                steps, outcomes(readOutcomes(row.outcomes())), fallback == null ? null : approver(fallback),
                row.version());
    }

    static ApprovalProcessDefinition.Step step(ApprovalStepSpec spec) {
        ApprovalProcessDefinition.Completion completion = ApprovalProcessDefinition.Completion
                .fromCode(spec.completion()).orElseThrow(() -> new IllegalStateException(
                        "A stored approval process has a completion rule this release does not know"));
        return new ApprovalProcessDefinition.Step(spec.name(), spec.label(), completion,
                spec.approvers().stream().map(ApprovalProcessCodec::approver).toList());
    }

    static ApprovalProcessDefinition.Approver approver(ApprovalApproverSpec spec) {
        ApprovalProcessDefinition.ApproverKind kind = ApprovalProcessDefinition.ApproverKind.fromCode(spec.kind())
                .orElseThrow(() -> new IllegalStateException(
                        "A stored approval process has an approver kind this release does not know"));
        return new ApprovalProcessDefinition.Approver(kind, spec.value());
    }

    static ApprovalProcessDefinition.Outcomes outcomes(ApprovalOutcomesSpec spec) {
        return new ApprovalProcessDefinition.Outcomes(fields(spec.approved()), fields(spec.rejected()),
                fields(spec.recalled()));
    }

    private static List<ApprovalProcessDefinition.Assignment> fields(List<ApprovalFieldSpec> specs) {
        return specs.stream().map(spec -> new ApprovalProcessDefinition.Assignment(spec.field(), spec.value()))
                .toList();
    }

    ApprovalProcessView view(ApprovalProcessStore.Row row) {
        return new ApprovalProcessView(row.apiName(), row.objectApiName(), row.label(), row.description(), row.active(),
                row.definitionVersion(), row.entryCriteria(), row.allowSelfApproval(), readSteps(row.steps()),
                readOutcomes(row.outcomes()), readFallback(row.fallback()), row.version());
    }
}
