package app.platform.approval.internal;

import app.platform.approval.internal.ApprovalCaller.Member;
import app.platform.approval.internal.ApprovalStore.AssignmentRow;
import app.platform.approval.internal.ApprovalStore.HistoryRow;
import app.platform.approval.internal.ApprovalStore.InstanceFilter;
import app.platform.approval.internal.ApprovalStore.InstanceRow;
import app.platform.approval.internal.ApprovalStore.StepRow;
import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.RecordTitles;
import app.platform.licensing.Entitlements;
import app.platform.metadata.CompiledApprovalProcess;
import app.platform.metadata.ConditionOutcome;
import app.platform.metadata.Metadata;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Ability;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.RecordRef;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApprovalActionsView;
import app.platformapi.ApprovalAssignmentView;
import app.platformapi.ApprovalHistoryView;
import app.platformapi.ApprovalInboxSummaryView;
import app.platformapi.ApprovalInstanceSummaryView;
import app.platformapi.ApprovalInstanceView;
import app.platformapi.ApprovalPersonView;
import app.platformapi.ApprovalStepView;
import app.platformapi.ApprovalTaskView;
import app.platformapi.Cursors;
import app.platformapi.Pagination;
import app.platformapi.RecordApprovalView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the screens read of approvals (ADR-0145, ADR-0152): a request with its steps, its people and, for those who may
 * see it, its history; the inbox of a person; the panel of a record. The server decides what the caller may do now
 * ({@link ApprovalActionsView}) and the screens offer only that. Names are the display names of active members, and a
 * person who is no longer one has none.
 *
 * <p><strong>Who sees what.</strong> A request is seen by whoever submitted it, whoever was asked to decide it and
 * whoever holds {@code approvals.view-all} <em>for a record they can read</em>; everybody else gets "not found". The
 * history, where the comments are, is shown to those same people; a member who can only see that a record is being
 * approved sees the state and the steps. A task for a record the person cannot read is shown as such, with no title: an
 * approval grants no access (ADR-0146).
 */
@Service
@Transactional(readOnly = true)
class ApprovalViews {

    private final ApprovalStore store;
    private final ApproverResolver resolver;
    private final ApprovalCaller callers;
    private final RecordTitles titles;
    private final Metadata metadata;
    private final MetadataResolver definitions;
    private final BackgroundRecords records;
    private final ApprovalEngine engine;
    private final Decisions decisions;
    private final Entitlements entitlements;
    private final ApprovalProperties properties;

    ApprovalViews(ApprovalStore store, ApproverResolver resolver, ApprovalCaller callers, RecordTitles titles,
            Metadata metadata, MetadataResolver definitions, BackgroundRecords records, ApprovalEngine engine,
            Decisions decisions, Entitlements entitlements, ApprovalProperties properties) {
        this.store = store;
        this.resolver = resolver;
        this.callers = callers;
        this.titles = titles;
        this.metadata = metadata;
        this.definitions = definitions;
        this.records = records;
        this.engine = engine;
        this.decisions = decisions;
        this.entitlements = entitlements;
        this.properties = properties;
    }

    // ---- one request ----

    /** The request in full for a caller who may see it; "not found" for anybody else. */
    ApprovalInstanceView instance(Member member, UUID id) {
        InstanceRow instance = store.instance(id).orElseThrow(ApprovalViews::missing);
        return full(member, instance, requireSeen(member, instance));
    }

    /** The request after an action of the caller, who has just acted on it. */
    ApprovalInstanceView full(Member member, InstanceRow instance) {
        return full(member, instance, seen(member, instance));
    }

    private ApprovalInstanceView full(Member member, InstanceRow instance, boolean historyVisible) {
        List<StepRow> steps = store.steps(instance.id());
        List<AssignmentRow> tasks = store.assignmentsOf(instance.id());
        List<HistoryRow> history = historyVisible ? store.history(instance.id()) : List.of();
        Set<UUID> people = new LinkedHashSet<>();
        add(people, instance.submittedBy());
        tasks.forEach(task -> {
            people.add(task.assigneeId());
            add(people, task.forUserId());
        });
        history.forEach(line -> {
            add(people, line.actorId());
            add(people, line.onBehalfOf());
            add(people, line.targetId());
        });
        Map<UUID, String> names = resolver.names(people);
        List<ApprovalStepView> stepViews = new ArrayList<>();
        for (StepRow step : steps) {
            List<ApprovalAssignmentView> taskViews = tasks.stream().filter(task -> task.stepId().equals(step.id()))
                    .map(task -> new ApprovalAssignmentView(task.id(), person(task.assigneeId(), names), task.via(),
                            task.forUserId() == null ? null : person(task.forUserId(), names), task.status(),
                            task.decidedAt())).toList();
            stepViews.add(new ApprovalStepView(step.position(), step.name(), step.label(), step.completion(),
                    step.status(), step.activatedAt(), step.finishedAt(), taskViews));
        }
        List<ApprovalHistoryView> lines = history.stream().map(line -> new ApprovalHistoryView(line.id(),
                line.stepPosition(), line.kind(), line.actorId() == null ? null : person(line.actorId(), names),
                line.onBehalfOf() == null ? null : person(line.onBehalfOf(), names),
                line.targetId() == null ? null : person(line.targetId(), names), line.reason(), line.comment(),
                line.createdAt())).toList();
        return new ApprovalInstanceView(summary(instance, steps, names), stepViews,
                actions(member, instance, tasks), historyVisible, lines);
    }

    // ---- lists of requests (administrators and people with view-all) ----

    /** A page of requests the caller may see, newest first. */
    ApiPageResponse<ApprovalInstanceSummaryView> instances(Member member, String status, String object,
            String process, boolean attention, UUID record, Integer limit, String cursor) {
        boolean all = callers.has(member, Ability.APPROVALS_VIEW_ALL) || callers.has(member, Ability.APPROVALS_MANAGE);
        InstanceFilter filter = new InstanceFilter(blank(status), blank(object), blank(process),
                attention ? Boolean.TRUE : null, record, all ? null : member.userId());
        int size = size(limit);
        Instant before = null;
        UUID beforeId = null;
        if (cursor != null) {
            String[] parts = Cursors.decode(cursor).map(text -> text.split("\\|")).orElseThrow(
                    () -> ApiException.validation("cursor", "This is not a cursor of this list."));
            before = Instant.parse(parts[0]);
            beforeId = UUID.fromString(parts[1]);
        }
        List<InstanceRow> found = store.instances(filter, before, beforeId, size + 1);
        boolean more = found.size() > size;
        List<InstanceRow> page = more ? found.subList(0, size) : found;
        // A member with view-all sees only the requests for records they can read.
        List<InstanceRow> visible = all ? readable(page) : page;
        Map<UUID, List<StepRow>> steps = store.stepsFor(page.stream().map(InstanceRow::id).toList()).stream()
                .collect(Collectors.groupingBy(StepRow::instanceId));
        Set<UUID> people = new LinkedHashSet<>();
        visible.forEach(instance -> add(people, instance.submittedBy()));
        Map<UUID, String> names = resolver.names(people);
        List<ApprovalInstanceSummaryView> views = visible.stream().map(instance -> summary(instance,
                steps.getOrDefault(instance.id(), List.of()), names)).toList();
        InstanceRow last = page.isEmpty() ? null : page.get(page.size() - 1);
        Pagination pagination = more && last != null
                ? Pagination.more(size, Cursors.encode(last.startedAt() + "|" + last.id()))
                : Pagination.last(size);
        return ApiPageResponse.of(views, pagination);
    }

    /** The requests among these for records the caller can read (the others are left out as if they did not exist). */
    private List<InstanceRow> readable(List<InstanceRow> page) {
        Map<String, List<InstanceRow>> byObject = page.stream().collect(Collectors.groupingBy(
                InstanceRow::objectApiName, LinkedHashMap::new, Collectors.toList()));
        Set<UUID> readable = new LinkedHashSet<>();
        byObject.forEach((object, rows) -> readable.addAll(titles.titlesOf(object,
                rows.stream().map(InstanceRow::recordId).toList()).keySet()));
        return page.stream().filter(instance -> readable.contains(instance.recordId())).toList();
    }

    // ---- the inbox ----

    /** A page of one person's tasks. */
    ApiPageResponse<ApprovalTaskView> inbox(Member member, String tab, String object, String process,
            Integer limit, String cursor) {
        String which = tab == null || tab.isBlank() ? "TO_APPROVE" : tab.strip().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("TO_APPROVE", "DELEGATED", "DECIDED").contains(which)) {
            throw ApiException.validation("tab", "Choose TO_APPROVE, DELEGATED or DECIDED.");
        }
        int size = size(limit);
        Instant after = null;
        UUID afterId = null;
        if (cursor != null) {
            String[] parts = Cursors.decode(cursor).map(text -> text.split("\\|")).orElseThrow(
                    () -> ApiException.validation("cursor", "This is not a cursor of this list."));
            after = Instant.parse(parts[0]);
            afterId = UUID.fromString(parts[1]);
        }
        List<AssignmentRow> found = store.tasks(member.userId(), which, blank(object), blank(process), after, afterId,
                size + 1);
        boolean more = found.size() > size;
        List<AssignmentRow> page = more ? found.subList(0, size) : found;
        Map<UUID, InstanceRow> instances = store.instances(page.stream().map(AssignmentRow::instanceId).distinct()
                .toList()).stream().collect(Collectors.toMap(InstanceRow::id, row -> row));
        Map<UUID, StepRow> steps = store.stepsById(page.stream().map(AssignmentRow::stepId).distinct().toList())
                .stream().collect(Collectors.toMap(StepRow::id, row -> row));
        Map<UUID, String> readable = new LinkedHashMap<>();
        instances.values().stream().collect(Collectors.groupingBy(InstanceRow::objectApiName)).forEach(
                (name, rows) -> readable.putAll(titles.titlesOf(name,
                        rows.stream().map(InstanceRow::recordId).distinct().toList())));
        Set<UUID> people = new LinkedHashSet<>();
        page.forEach(task -> add(people, task.forUserId()));
        instances.values().forEach(instance -> add(people, instance.submittedBy()));
        Map<UUID, String> names = resolver.names(people);
        List<ApprovalTaskView> views = new ArrayList<>();
        for (AssignmentRow task : page) {
            InstanceRow instance = instances.get(task.instanceId());
            StepRow step = steps.get(task.stepId());
            if (instance == null || step == null) {
                continue;
            }
            List<StepRow> instanceSteps = store.steps(instance.id());
            boolean open = "OPEN".equals(task.status()) && instance.pending();
            ObjectDefinition object2 = metadata.object(instance.objectApiName()).orElse(null);
            boolean readableRecord = readable.containsKey(instance.recordId());
            views.add(new ApprovalTaskView(task.id(), summary(instance, instanceSteps, names),
                    object2 == null ? null : object2.label(), readableRecord ? readable.get(instance.recordId()) : null,
                    readableRecord, step.label(), task.via(),
                    task.forUserId() == null ? null : person(task.forUserId(), names), task.status(),
                    task.createdAt(), task.decidedAt(), new ApprovalActionsView(false, open, open, open,
                            false, false, false, false)));
        }
        AssignmentRow last = page.isEmpty() ? null : page.get(page.size() - 1);
        Instant lastKey = last == null ? null : "DECIDED".equals(which) ? last.decidedAt() : last.createdAt();
        Pagination pagination = more && last != null
                ? Pagination.more(size, Cursors.encode(lastKey + "|" + last.id())) : Pagination.last(size);
        return ApiPageResponse.of(views, pagination);
    }

    /** How many open tasks the caller has (the badge on the inbox), and whether the link should be shown at all. */
    ApprovalInboxSummaryView summary(Member member) {
        int open = store.openTasks(member.userId());
        boolean available = open > 0 || entitlements.enabled(ApprovalEngine.FEATURE) || store.involved(member.userId());
        return new ApprovalInboxSummaryView(open, available);
    }

    // ---- the panel of a record ----

    /** The approval panel of one record; "not found" when the caller cannot read the record. */
    RecordApprovalView record(Member member, String objectApiName, UUID recordId) {
        if (!titles.titlesOf(objectApiName, List.of(recordId)).containsKey(recordId)) {
            throw ApiException.notFound("This record does not exist.");
        }
        boolean hasProcess = !metadata.approvalProcesses(objectApiName).isEmpty();
        Optional<InstanceRow> pending = store.pendingFor(objectApiName, recordId);
        Optional<InstanceRow> shown = pending.isPresent() ? pending : store.latestFor(objectApiName, recordId);
        if (!hasProcess && shown.isEmpty()) {
            return new RecordApprovalView(false, false, null, List.of(), null, ApprovalActionsView.none());
        }
        ApprovalInstanceView current = null;
        ApprovalActionsView actions = ApprovalActionsView.none();
        if (shown.isPresent()) {
            InstanceRow instance = shown.get();
            current = full(member, instance, seen(member, instance));
            actions = current.actions();
        }
        List<RecordApprovalView.ApprovalChoiceView> choices = new ArrayList<>();
        String reason = null;
        if (pending.isPresent()) {
            reason = "already_pending";
        } else if (!entitlements.enabled(ApprovalEngine.FEATURE)) {
            reason = "feature_off";
        } else if (!decisions.can(member.membershipId(), objectApiName, ObjectAction.UPDATE).allowed()
                || !decisions.can(member.membershipId(), objectApiName, ObjectAction.UPDATE, new RecordRef(recordId))
                        .allowed()) {
            reason = "not_editable";
        } else {
            DataRecord record = records.find(objectApiName, recordId).orElse(null);
            if (record != null && !record.deleted()) {
                for (CompiledApprovalProcess process : definitions.approvalProcesses(objectApiName)) {
                    if (engine.judge(process, record) == ConditionOutcome.TRUE) {
                        choices.add(new RecordApprovalView.ApprovalChoiceView(process.definition().apiName(),
                                process.definition().label()));
                    }
                }
            }
            if (choices.isEmpty()) {
                reason = "no_process_applies";
            }
        }
        ApprovalActionsView merged = new ApprovalActionsView(!choices.isEmpty(), actions.canApprove(),
                actions.canReject(), actions.canReassign(), actions.canRecall(), actions.canCancel(),
                actions.canAssign(), actions.canReapply());
        return new RecordApprovalView(true, pending.isPresent(), current, choices, choices.isEmpty() ? reason : null,
                merged);
    }

    // ---- building blocks ----

    ApprovalInstanceSummaryView summary(InstanceRow instance, List<StepRow> steps, Map<UUID, String> names) {
        String currentLabel = instance.currentStep() == null ? null : steps.stream()
                .filter(step -> step.position() == instance.currentStep()).map(StepRow::label).findFirst()
                .orElse(null);
        return new ApprovalInstanceSummaryView(instance.id(), instance.processApiName(), instance.processLabel(),
                instance.processVersionNumber(), instance.objectApiName(), instance.recordId(), instance.status(),
                instance.currentStep(), currentLabel, steps.size(), instance.attention(),
                instance.outcomeFailure(), instance.submittedBy() == null ? null
                        : person(instance.submittedBy(), names), instance.startedAt(), instance.finishedAt(),
                instance.version());
    }

    private ApprovalActionsView actions(Member member, InstanceRow instance, List<AssignmentRow> tasks) {
        boolean pending = instance.pending();
        boolean open = pending && tasks.stream().anyMatch(task -> task.assigneeId().equals(member.userId())
                && "OPEN".equals(task.status()));
        boolean override = callers.has(member, Ability.APPROVALS_OVERRIDE_LOCK);
        boolean manage = callers.has(member, Ability.APPROVALS_MANAGE);
        boolean submitter = member.userId().equals(instance.submittedBy());
        boolean reapply = !pending && instance.outcomeFailure() != null && manage;
        return new ApprovalActionsView(false, open, open, open, pending && (submitter || override),
                pending && override, pending && manage, reapply);
    }

    /** Whether the caller may see the request, and so its history; "not found" when they may not. */
    private boolean requireSeen(Member member, InstanceRow instance) {
        if (!seen(member, instance)) {
            throw missing();
        }
        return true;
    }

    private boolean seen(Member member, InstanceRow instance) {
        if (member.userId().equals(instance.submittedBy())
                || store.assignmentsOf(instance.id()).stream().anyMatch(task -> task.assigneeId()
                        .equals(member.userId()))) {
            return true;
        }
        return callers.has(member, Ability.APPROVALS_VIEW_ALL)
                && titles.titlesOf(instance.objectApiName(), List.of(instance.recordId()))
                        .containsKey(instance.recordId());
    }

    private static ApprovalPersonView person(UUID id, Map<UUID, String> names) {
        return new ApprovalPersonView(id, names.get(id));
    }

    private static void add(Collection<UUID> into, UUID id) {
        if (id != null) {
            into.add(id);
        }
    }

    private int size(Integer limit) {
        int asked = limit == null ? properties.inbox().pageSize() : limit;
        if (asked < 1 || asked > properties.inbox().maxPageSize()) {
            throw ApiException.validation("limit", "Must be between 1 and " + properties.inbox().maxPageSize() + ".");
        }
        return asked;
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static ApiException missing() {
        return ApiException.notFound("This approval does not exist.");
    }
}
