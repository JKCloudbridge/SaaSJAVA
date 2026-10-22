package app.platform.approval.internal;

import app.platform.approval.internal.ApprovalCaller.Member;
import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.RecordTitles;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ApprovalProcessDefinition.Approver;
import app.platform.metadata.ApprovalProcessDefinition.ApproverKind;
import app.platform.metadata.ApprovalProcessDefinition.Completion;
import app.platform.metadata.Metadata;
import app.platform.security.Ability;
import app.platformapi.ApiException;
import app.platform.security.ApproverDirectory;
import app.platformapi.ApprovalApproverSpec;
import app.platformapi.ApprovalChoicesView;
import app.platformapi.ApprovalPersonView;
import app.platformapi.ApprovalResolutionView;
import app.platformapi.ApprovalStepSpec;
import app.platformapi.ResolveApprovalRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Who would be asked?" for a record, with steps that may not be saved yet (ADR-0146): the very resolution a real
 * request
 * uses, run against a record the caller can read, with nothing stored, locked or sent. The browser holds no resolution
 * rule; this is how a screen shows an administrator what a process would do before it is made live.
 */
@Service
@Transactional(readOnly = true)
class ApprovalResolveService {

    private final ApproverResolver resolver;
    private final ApprovalCaller callers;
    private final RecordTitles titles;
    private final BackgroundRecords records;
    private final Metadata metadata;

    ApprovalResolveService(ApproverResolver resolver, ApprovalCaller callers, RecordTitles titles,
            BackgroundRecords records, Metadata metadata) {
        this.resolver = resolver;
        this.callers = callers;
        this.titles = titles;
        this.records = records;
        this.metadata = metadata;
    }

    ApprovalResolutionView resolve(Member member, String objectApiName, ResolveApprovalRequest request) {
        callers.require(member, Ability.APPROVALS_MANAGE);
        if (metadata.object(objectApiName).isEmpty()
                || !titles.titlesOf(objectApiName, List.of(request.recordId())).containsKey(request.recordId())) {
            throw ApiException.notFound("This record does not exist.");
        }
        DataRecord record = records.find(objectApiName, request.recordId()).filter(found -> !found.deleted())
                .orElseThrow(() -> ApiException.notFound("This record does not exist."));
        Approver fallback = request.fallback() == null ? null : approver(request.fallback(), "fallback");
        ApproverResolver.Context context = new ApproverResolver.Context(record, member.userId(),
                Boolean.TRUE.equals(request.allowSelfApproval()), fallback, objectApiName, "");
        List<ApprovalResolutionView.ResolvedStep> steps = new ArrayList<>();
        for (int i = 0; i < request.steps().size(); i++) {
            ApprovalStepSpec spec = request.steps().get(i);
            ApprovalProcessDefinition.Step step = step(spec, "steps[" + i + "]");
            ApproverResolution.Resolved resolved = resolver.resolve(step, context);
            Map<UUID, String> names = resolver.names(resolved.assignees().stream()
                    .map(ApproverResolution.Assignee::userId).toList());
            steps.add(new ApprovalResolutionView.ResolvedStep(spec.name(), spec.label(),
                    resolved.assignees().stream().map(assignee -> new ApprovalPersonView(assignee.userId(),
                            names.get(assignee.userId()))).toList(), resolved.left(), resolved.usedFallback(),
                    resolved.nobody(), resolved.nobody() && step.skipWhenNobody()));
        }
        return new ApprovalResolutionView(steps);
    }

    /** The profiles, roles and public groups a rule can name, for the builder (needs approvals.manage). */
    ApprovalChoicesView choices(Member member) {
        callers.require(member, Ability.APPROVALS_MANAGE);
        return new ApprovalChoicesView(named(resolver.directory().profiles()), named(resolver.directory().roles()),
                named(resolver.directory().groups()));
    }

    /**
     * People who can be asked to decide, found by a part of their name: for the pickers of a reassignment, a delegation
     * and a rule. Any member may search; the answer is names and identifiers of active members who hold a licence and
     * nothing else. The text must match something: blank or missing text answers empty, so the membership cannot be
     * read page by page through this endpoint.
     */
    List<ApprovalPersonView> people(String text, Integer limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        Map<UUID, String> names = new java.util.HashMap<>();
        return resolver.eligible(text, size, names).stream()
                .map(candidate -> new ApprovalPersonView(candidate.userId(), names.get(candidate.userId()))).toList();
    }

    private static List<ApprovalChoicesView.Named> named(List<ApproverDirectory.Named> found) {
        return found.stream().map(one -> new ApprovalChoicesView.Named(one.id(), one.name())).toList();
    }

    private static ApprovalProcessDefinition.Step step(ApprovalStepSpec spec, String place) {
        Completion completion = Completion.fromCode(spec.completion() == null ? "" : spec.completion().strip()
                .toUpperCase(Locale.ROOT)).orElseThrow(() -> ApiException.validation(place + ".completion",
                        "Choose ANY or ALL."));
        List<Approver> approvers = new ArrayList<>();
        for (int j = 0; j < spec.approvers().size(); j++) {
            approvers.add(approver(spec.approvers().get(j), place + ".approvers[" + j + "]"));
        }
        return new ApprovalProcessDefinition.Step(spec.name(), spec.label(), completion, approvers,
                Boolean.TRUE.equals(spec.skipWhenNobody()));
    }

    private static Approver approver(ApprovalApproverSpec spec, String place) {
        ApproverKind kind = ApproverKind.fromCode(spec.kind() == null ? "" : spec.kind().strip()
                .toUpperCase(Locale.ROOT)).orElseThrow(() -> ApiException.validation(place + ".kind",
                        "Choose one of the kinds of approver."));
        return new Approver(kind, spec.value() == null || spec.value().isBlank() ? null : spec.value().strip());
    }
}
