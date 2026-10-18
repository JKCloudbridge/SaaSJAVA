package app.platform.approval.internal;

import app.platform.approval.ApprovalStartException;
import app.platform.approval.ApprovalStartException.Reason;
import app.platform.approval.Approvals;
import app.platform.approval.internal.ApprovalCaller.Member;
import app.platform.approval.internal.ApprovalMachine.Done;
import app.platform.approval.internal.ApprovalMachine.Effect;
import app.platform.approval.internal.ApprovalMachine.Refused;
import app.platform.approval.internal.ApprovalStore.AssignmentRow;
import app.platform.approval.internal.ApprovalStore.InstanceRow;
import app.platform.approval.internal.ApprovalStore.StepRow;
import app.platform.approval.internal.ApproverResolution.Assignee;
import app.platform.approval.internal.ApproverResolution.Dropped;
import app.platform.approval.internal.ApproverResolution.Resolved;
import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWrites;
import app.platform.data.DataRecord;
import app.platform.data.RecordTitles;
import app.platform.licensing.Entitlements;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ApprovalProcessDefinition.Outcome;
import app.platform.metadata.CompiledApprovalProcess;
import app.platform.metadata.ConditionOutcome;
import app.platform.metadata.Metadata;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.PinnedApprovalProcess;
import app.platform.security.Ability;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.RecordRef;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The approval engine (ADR-0145 to ADR-0149): submit, approve, reject, recall, reassign, assign, cancel and apply an
 * outcome again. Every action is one transaction that writes the request, its steps, its tasks, the history, the
 * outcome
 * update, the audit record and the {@code approval.*} event <strong>together</strong>, so nothing is half done and a
 * crash leaves nothing locked. Nothing here waits on a clock.
 *
 * <p><strong>Order of locks</strong> (ADR-0148): the record's row first, the request's row second, and the steps and
 * tasks only under the request's. A decision reads the request without a lock, locks the record, then locks and
 * re-reads
 * the request; a submit locks the record before it looks for a pending request. That is the order the record lock guard
 * uses, so the two cannot deadlock and a write is never decided half-way through a submit.
 *
 * <p><strong>Who acts.</strong> An approver acts because of the task they hold, not because of an ability; the task is
 * checked in the transaction of the decision, with the standing of the person (an active member who holds the licence
 * their profile needs). The comment of a person is stored in the history and nowhere else: never logged, never in an
 * event, a mail, an audit record or a metric.
 */
@Service
@Transactional
class ApprovalEngine implements Approvals {

    static final String FEATURE = "approvals";

    private static final String ALREADY_PENDING = "This record is already being approved.";
    private static final String NOT_FOUND = "This approval does not exist.";

    private final ApprovalStore store;
    private final ApproverResolver resolver;
    private final MetadataResolver definitions;
    private final Metadata metadata;
    private final BackgroundRecords records;
    private final BackgroundWrites writes;
    private final RecordTitles titles;
    private final Decisions decisions;
    private final Entitlements entitlements;
    private final ApprovalAudit audit;
    private final ApprovalNotifier notifier;
    private final ApprovalCaller callers;
    private final ApprovalProperties properties;
    private final Clock clock;
    private final TransactionTemplate savepoint;

    ApprovalEngine(ApprovalStore store, ApproverResolver resolver, MetadataResolver definitions, Metadata metadata,
            BackgroundRecords records, BackgroundWrites writes, RecordTitles titles, Decisions decisions,
            Entitlements entitlements, ApprovalAudit audit, ApprovalNotifier notifier, ApprovalCaller callers,
            ApprovalProperties properties, Clock clock, PlatformTransactionManager transactions) {
        this.store = store;
        this.resolver = resolver;
        this.definitions = definitions;
        this.metadata = metadata;
        this.records = records;
        this.writes = writes;
        this.titles = titles;
        this.decisions = decisions;
        this.entitlements = entitlements;
        this.audit = audit;
        this.notifier = notifier;
        this.callers = callers;
        this.properties = properties;
        this.clock = clock;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // ---- submitting ----

    /** A member submits a record. */
    InstanceRow submit(Member member, String objectApiName, UUID recordId, String processName, String comment) {
        String note = comment(comment);
        if (!entitlements.enabled(FEATURE)) {
            throw new ApiException(ErrorCode.CONFLICT, "Approvals are not part of this organization's plan.");
        }
        ensureCanSubmit(member, objectApiName, recordId);
        DataRecord record = records.findAndHold(objectApiName, recordId)
                .filter(found -> !found.deleted()).orElseThrow(
                        () -> ApiException.notFound("This record does not exist."));
        Optional<InstanceRow> pending = store.pendingFor(objectApiName, recordId);
        if (pending.isPresent()) {
            if (member.userId().equals(pending.get().submittedBy())) {
                return pending.get();
            }
            throw new ApiException(ErrorCode.CONFLICT, ALREADY_PENDING);
        }
        CompiledApprovalProcess process = chooseProcess(objectApiName, record, processName);
        try {
            return begin(member.userId(), record, process, note, null);
        } catch (ApprovalStartException refused) {
            throw new ApiException(ErrorCode.CONFLICT, words(refused.reason()));
        }
    }

    /** The platform submits a record for a workflow (ADR-0150). */
    @Override
    public Approvals.Started startByPlatform(String objectApiName, UUID recordId, String processApiName,
            BackgroundWrites.Chain chain) {
        if (!entitlements.enabled(FEATURE)) {
            throw new ApprovalStartException(Reason.FEATURE_OFF);
        }
        DataRecord record = records.findAndHold(objectApiName, recordId)
                .orElseThrow(() -> new ApprovalStartException(Reason.RECORD_GONE));
        if (record.deleted()) {
            throw new ApprovalStartException(Reason.RECORD_IN_BIN);
        }
        Optional<InstanceRow> pending = store.pendingFor(objectApiName, recordId);
        if (pending.isPresent()) {
            return new Approvals.Started(pending.get().id(), true);
        }
        CompiledApprovalProcess process = definitions.approvalProcesses(objectApiName).stream()
                .filter(found -> found.definition().apiName().equals(processApiName)).findFirst().orElse(null);
        if (process == null) {
            boolean exists = metadata.approvalProcesses(objectApiName).stream()
                    .anyMatch(found -> found.apiName().equals(processApiName));
            throw new ApprovalStartException(exists ? Reason.PROCESS_INACTIVE : Reason.PROCESS_MISSING);
        }
        ConditionOutcome met = judge(process, record);
        if (met == ConditionOutcome.FALSE) {
            throw new ApprovalStartException(Reason.ENTRY_CRITERIA_NOT_MET);
        }
        if (met == ConditionOutcome.NOT_EVALUABLE) {
            throw new ApprovalStartException(Reason.ENTRY_CRITERIA_NOT_EVALUABLE);
        }
        BackgroundWrites.Chain kept = chain == null || chain.root() == null ? null : chain;
        InstanceRow started = begin(null, record, process, null, kept);
        return new Approvals.Started(started.id(), false);
    }

    private void ensureCanSubmit(Member member, String objectApiName, UUID recordId) {
        if (!titles.titlesOf(objectApiName, List.of(recordId)).containsKey(recordId)) {
            throw ApiException.notFound("This record does not exist.");
        }
        if (!decisions.can(member.membershipId(), objectApiName, ObjectAction.UPDATE).allowed()
                || !decisions.can(member.membershipId(), objectApiName, ObjectAction.UPDATE, new RecordRef(recordId))
                        .allowed()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    /** The process a submit uses: the one named, or the only active one whose entry criteria the record meets. */
    private CompiledApprovalProcess chooseProcess(String objectApiName, DataRecord record, String name) {
        List<CompiledApprovalProcess> active = definitions.approvalProcesses(objectApiName);
        if (name != null && !name.isBlank()) {
            CompiledApprovalProcess named = active.stream()
                    .filter(found -> found.definition().apiName().equals(name.strip())).findFirst().orElse(null);
            if (named == null) {
                boolean exists = metadata.approvalProcesses(objectApiName).stream()
                        .anyMatch(found -> found.apiName().equals(name.strip()));
                if (exists) {
                    throw new ApiException(ErrorCode.CONFLICT, words(Reason.PROCESS_INACTIVE));
                }
                throw ApiException.notFound("This approval process does not exist.");
            }
            requireMet(named, record);
            return named;
        }
        List<CompiledApprovalProcess> fitting = new ArrayList<>();
        for (CompiledApprovalProcess process : active) {
            if (judge(process, record) == ConditionOutcome.TRUE) {
                fitting.add(process);
            }
        }
        if (fitting.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "No approval process applies to this record.");
        }
        if (fitting.size() > 1) {
            throw new ApiException(ErrorCode.CONFLICT, "More than one approval process applies to this record ("
                    + fitting.stream().map(found -> found.definition().label()).collect(Collectors.joining(", "))
                    + "). Choose one.");
        }
        return fitting.get(0);
    }

    private void requireMet(CompiledApprovalProcess process, DataRecord record) {
        ConditionOutcome met = judge(process, record);
        if (met == ConditionOutcome.FALSE) {
            throw new ApiException(ErrorCode.CONFLICT, words(Reason.ENTRY_CRITERIA_NOT_MET));
        }
        if (met == ConditionOutcome.NOT_EVALUABLE) {
            throw new ApiException(ErrorCode.CONFLICT, words(Reason.ENTRY_CRITERIA_NOT_EVALUABLE));
        }
    }

    ConditionOutcome judge(CompiledApprovalProcess process, DataRecord record) {
        Map<String, Object> values = records.engineValues(record.objectApiName(), record);
        return process.evaluate(values, clock.instant());
    }

    /** The words a person is told for a reason a request could not start. */
    static String words(Reason reason) {
        return switch (reason) {
            case ENTRY_CRITERIA_NOT_MET -> "This record does not meet the entry criteria of the approval.";
            case ENTRY_CRITERIA_NOT_EVALUABLE -> "The entry criteria of the approval cannot be evaluated for this "
                    + "record.";
            case NO_APPROVER -> "Nobody can be asked to decide the first step of the approval, so it cannot start.";
            case FEATURE_OFF -> "Approvals are not part of this organization's plan.";
            case RECORD_GONE -> "This record does not exist.";
            case RECORD_IN_BIN -> "This record is in the recycle bin.";
            case PROCESS_INACTIVE -> "This approval process is switched off.";
            case PROCESS_MISSING -> "This approval process does not exist.";
        };
    }

    /** Creates the request, its steps and the tasks of the first step. */
    private InstanceRow begin(UUID submitter, DataRecord record, CompiledApprovalProcess process, String comment,
            BackgroundWrites.Chain chain) {
        ApprovalProcessDefinition definition = process.definition();
        UUID actor = submitter == null ? ActorId.SYSTEM.value() : submitter;
        ApproverResolver.Context context = new ApproverResolver.Context(record, submitter,
                definition.allowSelfApproval(), definition.fallback(), definition.objectApiName(),
                definition.apiName());
        Resolved first = resolver.resolve(definition.steps().get(0), context);
        if (first.nobody()) {
            throw new ApprovalStartException(Reason.NO_APPROVER);
        }
        InstanceRow instance = store.insertInstance(definition.id(), definition.versionId(), definition.apiName(),
                definition.label(), definition.definitionVersion(), definition.objectApiName(), record.id(),
                record.version(), submitter, chain == null ? null : chain.root(),
                chain == null ? 0 : chain.depth(), chain == null ? List.of() : chain.workflows(), actor);
        List<StepRow> steps = new ArrayList<>();
        for (int i = 0; i < definition.steps().size(); i++) {
            ApprovalProcessDefinition.Step step = definition.steps().get(i);
            steps.add(store.insertStep(instance.id(), i + 1, step.name(), step.label(), step.completion().name(),
                    actor));
        }
        store.insertHistory(instance.id(), null, "SUBMITTED", submitter, null, null, null, comment,
                instance.processVersionNumber(), actor);
        activate(instance, steps.get(0), first, definition, actor);
        audit.submitted(submitter, instance, submitter == null ? "platform" : "person");
        return store.instance(instance.id()).orElseThrow();
    }

    /**
     * Makes a step the active one with the tasks of the people it asks (there may be none: the request then waits for
     * an
     * administrator, ADR-0145 decision 6). Writes what was dropped and why, and tells the people asked.
     */
    private void activate(InstanceRow instance, StepRow step, Resolved resolved, ApprovalProcessDefinition definition,
            UUID actor) {
        if (!store.updateStep(step.id(), step.version(), "ACTIVE", actor)) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        store.insertHistory(instance.id(), step.position(), "STEP_ACTIVATED", null, null, null, null, null,
                instance.processVersionNumber(), actor);
        for (Dropped dropped : resolved.dropped()) {
            store.insertHistory(instance.id(), step.position(), "ASSIGNEE_DROPPED", null, null, dropped.userId(),
                    dropped.reason(), null, instance.processVersionNumber(), actor);
        }
        List<UUID> asked = new ArrayList<>();
        for (Assignee assignee : resolved.assignees()) {
            store.insertAssignment(instance.id(), step.id(), assignee.userId(), assignee.via().name(),
                    assignee.forUser(), actor);
            asked.add(assignee.userId());
        }
        if (resolved.nobody()) {
            InstanceRow now = store.instance(instance.id()).orElseThrow();
            save(now, "PENDING", step.position(), "no_approver", now.outcomeFailure(), false, actor);
            store.insertHistory(instance.id(), step.position(), "ATTENTION_RAISED", null, null, null,
                    "no_approver", null, instance.processVersionNumber(), actor);
            audit.attention(now, step.position(), "no_approver", true);
        } else if (instance.currentStep() == null || instance.currentStep() != step.position()) {
            InstanceRow now = store.instance(instance.id()).orElseThrow();
            save(now, "PENDING", step.position(), null, now.outcomeFailure(), false, actor);
        }
        notifier.tasksCreated(instance, step, asked);
        if (!asked.isEmpty()) {
            startTimer(instance, step, definition, actor);
        }
    }

    /**
     * Starts the timer of a step that has people to decide it, when the process reminds or escalates (ADR-0159). The
     * first look is at the first event that falls due; the timer works out the rest each time it looks.
     */
    private void startTimer(InstanceRow instance, StepRow step, ApprovalProcessDefinition definition, UUID actor) {
        if (!ReminderSchedule.used(definition.remindAfterHours(), definition.escalateAfterHours())) {
            return;
        }
        Instant first = ReminderSchedule.next(clock.instant(), definition.remindAfterHours(),
                definition.escalateAfterHours(), ReminderSchedule.State.FRESH);
        if (first != null) {
            store.startTimer(instance.id(), step.id(), first, actor);
        }
    }

    // ---- deciding ----

    /** A person approves their task. */
    InstanceRow approve(Member member, UUID id, long version, String comment) {
        return decide(member, id, version, comment(comment), true);
    }

    /** A person rejects their task; the comment is required. */
    InstanceRow reject(Member member, UUID id, long version, String comment) {
        String note = comment(comment);
        if (note == null) {
            throw ApiException.validation("comment", "Say why you reject it.");
        }
        return decide(member, id, version, note, false);
    }

    private InstanceRow decide(Member member, UUID id, long version, String note, boolean approve) {
        InstanceRow instance = hold(id);
        List<AssignmentRow> tasks = store.assignmentsOf(id);
        List<AssignmentRow> mine = tasks.stream().filter(task -> task.assigneeId().equals(member.userId())).toList();
        if (mine.isEmpty()) {
            throw ApiException.notFound(NOT_FOUND);
        }
        if (!instance.pending() || instance.version() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        AssignmentRow task = mine.stream().filter(candidate -> "OPEN".equals(candidate.status())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.CONCURRENT_MODIFICATION));
        requireStanding(member.userId());
        Loaded loaded = load(instance, tasks);
        ApprovalMachine.Result result = approve ? ApprovalMachine.approve(loaded.model(), member.userId())
                : ApprovalMachine.reject(loaded.model(), member.userId());
        if (result instanceof Refused) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Done done = (Done) result;
        StepRow step = loaded.stepAt(loaded.activePosition());
        persist(loaded, done.model(), member.userId());
        audit.decision(approve ? "approve" : "reject", member.userId(), instance, step.position(),
                "DELEGATED".equals(task.via()));
        UUID onBehalfOf = "DELEGATED".equals(task.via()) ? task.forUserId() : null;
        if (!approve) {
            store.insertHistory(id, step.position(), "REJECTED", member.userId(), onBehalfOf, null, null, note,
                    instance.processVersionNumber(), member.userId());
            audit.stepFinished(step, clock.instant());
            return end(instance, "REJECTED", Outcome.REJECTED, member.userId());
        }
        store.insertHistory(id, step.position(), "TASK_APPROVED", member.userId(), onBehalfOf, null, null, note,
                instance.processVersionNumber(), member.userId());
        if (done.effect() == Effect.TASK_APPROVED) {
            return store.instance(id).orElseThrow();
        }
        store.insertHistory(id, step.position(), "STEP_APPROVED", null, null, null, null, null,
                instance.processVersionNumber(), member.userId());
        audit.stepFinished(step, clock.instant());
        if (done.effect() == Effect.REQUEST_APPROVED) {
            store.insertHistory(id, null, "APPROVED", null, null, null, null, null,
                    instance.processVersionNumber(), member.userId());
            return end(instance, "APPROVED", Outcome.APPROVED, member.userId());
        }
        return advance(instance, done.next(), member.userId());
    }

    /** Resolves the people of the next step and makes it the active one. */
    private InstanceRow advance(InstanceRow instance, int position, UUID actor) {
        PinnedApprovalProcess pinned = pinned(instance);
        ApprovalProcessDefinition definition = pinned.process().definition();
        StepRow next = store.steps(instance.id()).stream().filter(step -> step.position() == position).findFirst()
                .orElseThrow();
        DataRecord record = records.find(instance.objectApiName(), instance.recordId()).orElse(null);
        ApproverResolver.Context context = new ApproverResolver.Context(record, instance.submittedBy(),
                definition.allowSelfApproval(), definition.fallback(), definition.objectApiName(),
                definition.apiName());
        Resolved resolved = resolver.resolve(definition.steps().get(position - 1), context);
        InstanceRow current = store.instance(instance.id()).orElseThrow();
        activate(current, next, resolved, definition, actor);
        return store.instance(instance.id()).orElseThrow();
    }

    // ---- the timer (Sprint 25, ADR-0159) ----

    /**
     * The timer looks at a step whose time has come: reminds the people who still have an open task, asks the fallback
     * approver as well, marks the request overdue. Everything happens under the lock of the record and the request, the
     * same as a decision, so the two cannot interleave; a step that was decided while the timer waited for the lock is
     * left alone. Each thing happens once (the unique rows of {@code approval_reminder}); what was done and when to
     * look next are written to the timer row at the end, in the same transaction.
     */
    void onTimer(UUID stepId) {
        ApprovalStore.TimerRow timer = store.timerOf(stepId).orElse(null);
        StepRow seen = store.step(stepId).orElse(null);
        UUID system = ActorId.SYSTEM.value();
        if (timer == null || seen == null) {
            return;
        }
        InstanceRow instance = hold(seen.instanceId());
        StepRow step = store.step(stepId).orElseThrow();
        if (!instance.pending() || !"ACTIVE".equals(step.status()) || step.activatedAt() == null) {
            store.stopTimer(stepId, system);
            return;
        }
        ApprovalProcessDefinition definition = pinned(instance).process().definition();
        ReminderSchedule.State done = new ReminderSchedule.State(timer.remindersSent(), timer.escalatedAt() != null,
                timer.overdueAt() != null);
        Instant now = clock.instant();
        List<ReminderSchedule.Event> due = ReminderSchedule.due(now, step.activatedAt(), definition.remindAfterHours(),
                definition.escalateAfterHours(), done);
        Instant escalatedAt = timer.escalatedAt();
        Instant overdueAt = timer.overdueAt();
        ReminderSchedule.Event reminder = ReminderSchedule.reminderToSend(due);
        if (reminder != null && store.insertReminder(instance.id(), step.id(), "REMINDER", reminder.sequence(),
                system)) {
            remind(instance, step, reminder.sequence());
        }
        for (ReminderSchedule.Event event : due) {
            if (event.kind() == ReminderSchedule.Kind.ESCALATION
                    && store.insertReminder(instance.id(), step.id(), "ESCALATION", 1, system)) {
                escalate(instance, step, definition);
            } else if (event.kind() == ReminderSchedule.Kind.OVERDUE
                    && store.insertReminder(instance.id(), step.id(), "OVERDUE", 1, system)) {
                markOverdue(instance, step);
            }
            if (event.kind() == ReminderSchedule.Kind.ESCALATION) {
                escalatedAt = now;
            } else if (event.kind() == ReminderSchedule.Kind.OVERDUE) {
                overdueAt = now;
            }
        }
        ReminderSchedule.State after = ReminderSchedule.after(done, due);
        Instant next = ReminderSchedule.next(step.activatedAt(), definition.remindAfterHours(),
                definition.escalateAfterHours(), after);
        if (!store.updateTimer(timer.id(), timer.version(), next, after.remindersSent(), escalatedAt, overdueAt,
                system)) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    private void remind(InstanceRow instance, StepRow step, int sequence) {
        List<UUID> open = store.assignmentsOf(instance.id()).stream()
                .filter(task -> task.stepId().equals(step.id()) && "OPEN".equals(task.status()))
                .map(AssignmentRow::assigneeId).distinct().toList();
        store.insertHistory(instance.id(), step.position(), "REMINDER_SENT", null, null, null, null, null,
                instance.processVersionNumber(), ActorId.SYSTEM.value());
        notifier.reminded(instance, step, open, sequence);
        audit.timer("reminded", instance, step.position(), open.size());
    }

    /** Asks the fallback approver as well, once; the person becomes one more task of the step (like an assign). */
    private void escalate(InstanceRow instance, StepRow step, ApprovalProcessDefinition definition) {
        UUID system = ActorId.SYSTEM.value();
        List<UUID> added = new ArrayList<>();
        if (definition.fallback() != null) {
            DataRecord record = records.find(instance.objectApiName(), instance.recordId()).orElse(null);
            Resolved fallback = resolver.resolve(new ApprovalProcessDefinition.Step(step.name(), step.label(),
                    ApprovalProcessDefinition.Completion.ANY, List.of(definition.fallback())),
                    new ApproverResolver.Context(record, instance.submittedBy(), definition.allowSelfApproval(), null,
                            definition.objectApiName(), definition.apiName()));
            for (Assignee person : fallback.assignees()) {
                List<AssignmentRow> tasks = store.assignmentsOf(instance.id());
                if (tasks.stream().anyMatch(task -> task.stepId().equals(step.id())
                        && task.assigneeId().equals(person.userId()))) {
                    continue;
                }
                Loaded loaded = load(instance, tasks);
                if (ApprovalMachine.assign(loaded.model(), person.userId(), UUID.randomUUID()) instanceof Done done) {
                    persist(loaded, done.model(), system);
                    store.insertAssignment(instance.id(), step.id(), person.userId(), "FALLBACK", null, system);
                    added.add(person.userId());
                }
            }
        }
        store.insertHistory(instance.id(), step.position(), "ESCALATED", null, null, null,
                added.isEmpty() ? "nobody_to_ask" : null, null, instance.processVersionNumber(), system);
        notifier.tasksCreated(instance, step, added);
        audit.timer("escalated", instance, step.position(), added.size());
    }

    /** Marks the request overdue, unless something else already needs an administrator's attention. */
    private void markOverdue(InstanceRow instance, StepRow step) {
        UUID system = ActorId.SYSTEM.value();
        InstanceRow current = store.instance(instance.id()).orElseThrow();
        store.insertHistory(instance.id(), step.position(), "OVERDUE_MARKED", null, null, null, null, null,
                instance.processVersionNumber(), system);
        if (current.attention() == null) {
            save(current, "PENDING", current.currentStep(), "overdue", current.outcomeFailure(), false, system);
            store.insertHistory(instance.id(), step.position(), "ATTENTION_RAISED", null, null, null, "overdue",
                    null, instance.processVersionNumber(), system);
            audit.attention(current, step.position(), "overdue", true);
        }
        audit.timer("overdue", instance, step.position(), 1);
    }

    // ---- recall, cancel ----

    /** The submitter, or a member who may override the lock, withdraws the request. */
    InstanceRow recall(Member member, UUID id, long version, String comment) {
        InstanceRow instance = hold(id);
        boolean override = callers.has(member, Ability.APPROVALS_OVERRIDE_LOCK);
        if (!member.userId().equals(instance.submittedBy()) && !override) {
            throw ApiException.notFound(NOT_FOUND);
        }
        requirePending(instance, version);
        return stop(instance, ApprovalMachine.recall(load(instance, store.assignmentsOf(id)).model()), member,
                "RECALLED", Outcome.RECALLED, comment(comment));
    }

    /** A member who may override the lock ends the request. */
    InstanceRow cancel(Member member, UUID id, long version, String comment) {
        InstanceRow instance = hold(id);
        callers.require(member, Ability.APPROVALS_OVERRIDE_LOCK);
        requirePending(instance, version);
        return stop(instance, ApprovalMachine.cancel(load(instance, store.assignmentsOf(id)).model()), member,
                "CANCELLED", null, comment(comment));
    }

    private InstanceRow stop(InstanceRow instance, ApprovalMachine.Result result, Member member, String kind,
            Outcome outcome, String note) {
        if (result instanceof Refused) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Loaded loaded = load(instance, store.assignmentsOf(instance.id()));
        StepRow active = loaded.activeStep();
        persist(loaded, ((Done) result).model(), member.userId());
        store.insertHistory(instance.id(), active == null ? null : active.position(), kind, member.userId(), null,
                null, null, note, instance.processVersionNumber(), member.userId());
        if (active != null) {
            audit.stepFinished(active, clock.instant());
        }
        return end(instance, kind, outcome, member.userId());
    }

    // ---- reassigning, assigning ----

    /** A person hands their own task to one other person. */
    InstanceRow reassign(Member member, UUID id, UUID to, long version, String comment) {
        InstanceRow instance = hold(id);
        List<AssignmentRow> tasks = store.assignmentsOf(id);
        if (tasks.stream().noneMatch(task -> task.assigneeId().equals(member.userId()))) {
            throw ApiException.notFound(NOT_FOUND);
        }
        requirePending(instance, version);
        requireStanding(member.userId());
        ApprovalProcessDefinition definition = pinned(instance).process().definition();
        requireEligible(to, instance, definition, "toUserId");
        Loaded loaded = load(instance, tasks);
        ApprovalMachine.Result result = ApprovalMachine.reassign(loaded.model(), member.userId(), to,
                UUID.randomUUID());
        if (result instanceof Refused refused) {
            throw refusal(refused, "toUserId");
        }
        StepRow step = loaded.stepAt(loaded.activePosition());
        persist(loaded, ((Done) result).model(), member.userId());
        store.insertAssignment(id, step.id(), to, "REASSIGNED", member.userId(), member.userId());
        store.insertHistory(id, step.position(), "REASSIGNED", member.userId(), null, to, null, comment(comment),
                instance.processVersionNumber(), member.userId());
        audit.reassigned(member.userId(), instance, step.position(), false);
        notifier.tasksCreated(instance, loaded.stepAt(loaded.activePosition()), List.of(to));
        return store.instance(id).orElseThrow();
    }

    /** A member who manages approvals asks one more person to decide the active step. */
    InstanceRow assign(Member member, UUID id, UUID to, long version, String comment) {
        InstanceRow instance = hold(id);
        callers.require(member, Ability.APPROVALS_MANAGE);
        requirePending(instance, version);
        ApprovalProcessDefinition definition = pinned(instance).process().definition();
        requireEligible(to, instance, definition, "toUserId");
        Loaded loaded = load(instance, store.assignmentsOf(id));
        ApprovalMachine.Result result = ApprovalMachine.assign(loaded.model(), to, UUID.randomUUID());
        if (result instanceof Refused refused) {
            throw refusal(refused, "toUserId");
        }
        StepRow step = loaded.stepAt(loaded.activePosition());
        persist(loaded, ((Done) result).model(), member.userId());
        store.insertAssignment(id, step.id(), to, "ADDED", null, member.userId());
        store.insertHistory(id, step.position(), "ASSIGNED", member.userId(), null, to, null, comment(comment),
                instance.processVersionNumber(), member.userId());
        audit.reassigned(member.userId(), instance, step.position(), true);
        InstanceRow current = store.instance(id).orElseThrow();
        if (current.attention() != null) {
            save(current, "PENDING", current.currentStep(), null, current.outcomeFailure(), false, member.userId());
            store.insertHistory(id, step.position(), "ATTENTION_CLEARED", member.userId(), null, null,
                    current.attention(), null, instance.processVersionNumber(), member.userId());
            audit.attention(current, step.position(), current.attention(), false);
        }
        notifier.tasksCreated(instance, loaded.stepAt(loaded.activePosition()), List.of(to));
        return store.instance(id).orElseThrow();
    }

    // ---- applying an outcome again ----

    /** A member who manages approvals writes the outcome updates again after they were refused (ADR-0149). */
    InstanceRow reapply(Member member, UUID id, long recordVersion, long version) {
        InstanceRow instance = hold(id);
        callers.require(member, Ability.APPROVALS_MANAGE);
        if (instance.pending() || instance.outcomeFailure() == null || instance.version() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (store.pendingFor(instance.objectApiName(), instance.recordId()).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "A newer approval of this record is under way, so the outcome "
                    + "of this one is not applied again.");
        }
        DataRecord record = records.find(instance.objectApiName(), instance.recordId()).orElseThrow(
                () -> ApiException.notFound("This record does not exist."));
        if (record.deleted() || record.version() != recordVersion) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Outcome outcome = Outcome.valueOf(instance.status());
        String failure = applyOutcome(instance, pinned(instance).process().definition(), outcome);
        if (failure != null) {
            throw new ApiException(ErrorCode.CONFLICT, "The update could not be made again (" + failure + ").");
        }
        save(instance, instance.status(), null, null, null, true, member.userId());
        InstanceRow after = store.instance(id).orElseThrow();
        store.insertHistory(id, null, "OUTCOME_REAPPLIED", member.userId(), null, null, null, null,
                instance.processVersionNumber(), member.userId());
        audit.outcomeReapplied(member.userId(), after);
        return after;
    }

    // ---- ending a request ----

    /**
     * The request has ended: the status, the unlock (the status is the lock), the outcome update and the event, in this
     * transaction. The outcome update comes after the status, so the lock guard no longer sees a pending request.
     */
    private InstanceRow end(InstanceRow instance, String status, Outcome outcome, UUID actor) {
        InstanceRow current = store.instance(instance.id()).orElseThrow();
        save(current, status, null, null, current.outcomeFailure(), true, actor);
        store.stopTimers(instance.id(), actor);
        String failure = outcome == null ? null
                : applyOutcome(store.instance(instance.id()).orElseThrow(), pinned(instance).process().definition(),
                        outcome);
        InstanceRow ended = store.instance(instance.id()).orElseThrow();
        if (failure != null) {
            save(ended, status, null, null, failure, true, actor);
            store.insertHistory(instance.id(), null, "OUTCOME_FAILED", null, null, null, failure, null,
                    instance.processVersionNumber(), actor);
            audit.outcomeFailed(ended, failure);
            ended = store.instance(instance.id()).orElseThrow();
        } else if (outcome != null
                && !pinned(instance).process().definition().outcomes().of(outcome).isEmpty()) {
            store.insertHistory(instance.id(), null, "OUTCOME_APPLIED", null, null, null, null, null,
                    instance.processVersionNumber(), actor);
        }
        notifier.ended(ended, status, actor);
        audit.ended(status.toLowerCase(java.util.Locale.ROOT), actor.equals(ActorId.SYSTEM.value()) ? null : actor,
                ended);
        return ended;
    }

    /**
     * Writes the outcome updates in a savepoint, as the platform. A refusal does not undo the decision: the request
     * keeps
     * the outcome the people chose and the stable reason is returned (ADR-0149 decision 4).
     *
     * @return null when nothing needed doing or it worked, otherwise the stable reason it was refused
     */
    private String applyOutcome(InstanceRow instance, ApprovalProcessDefinition definition, Outcome outcome) {
        List<ApprovalProcessDefinition.Assignment> updates = definition.outcomes().of(outcome);
        if (updates.isEmpty()) {
            return null;
        }
        BackgroundWrites.Chain chain = instance.chainRoot() == null ? null
                : new BackgroundWrites.Chain(instance.chainRoot(), instance.chainDepth(), instance.chainWorkflows());
        BackgroundWrites.Source source = BackgroundWrites.Source.approval(instance.processApiName(), instance.id(),
                chain);
        List<BackgroundWrites.Assignment> assignments = updates.stream().map(update -> new BackgroundWrites.Assignment(
                update.field(), new BackgroundWrites.Value.Literal(update.value()))).toList();
        try {
            savepoint.executeWithoutResult(status -> writes.update(source, instance.objectApiName(),
                    instance.recordId(), assignments));
            return null;
        } catch (BackgroundWriteException refused) {
            return refused.reason().key();
        } catch (RuntimeException unexpected) {
            return "outcome_update_failed";
        }
    }

    // ---- loading, persisting ----

    private record Loaded(InstanceRow instance, List<StepRow> steps, List<AssignmentRow> tasks,
            ApprovalMachine.Model model) {

        int activePosition() {
            return model.active().map(ApprovalMachine.Step::position).orElseThrow();
        }

        StepRow stepAt(int position) {
            return steps.stream().filter(step -> step.position() == position).findFirst().orElseThrow();
        }

        StepRow activeStep() {
            return steps.stream().filter(step -> "ACTIVE".equals(step.status())).findFirst().orElse(null);
        }
    }

    private Loaded load(InstanceRow instance, List<AssignmentRow> tasks) {
        List<StepRow> steps = store.steps(instance.id());
        List<ApprovalMachine.Step> modelled = new ArrayList<>();
        for (StepRow step : steps) {
            List<ApprovalMachine.Task> stepTasks = tasks.stream().filter(task -> task.stepId().equals(step.id()))
                    .map(task -> new ApprovalMachine.Task(task.id(), task.assigneeId(),
                            ApprovalMachine.TaskStatus.valueOf(task.status()))).toList();
            modelled.add(new ApprovalMachine.Step(step.position(),
                    ApprovalMachine.Completion.valueOf(step.completion()),
                    ApprovalMachine.StepStatus.valueOf(step.status()), stepTasks));
        }
        return new Loaded(instance, steps, tasks,
                new ApprovalMachine.Model(ApprovalMachine.Status.valueOf(instance.status()), modelled));
    }

    /** Writes the steps and the tasks whose state differs from the model. The request itself is saved by the caller. */
    private void persist(Loaded loaded, ApprovalMachine.Model after, UUID actor) {
        for (ApprovalMachine.Step step : after.steps()) {
            StepRow row = loaded.stepAt(step.position());
            if (!row.status().equals(step.status().name())
                    && !store.updateStep(row.id(), row.version(), step.status().name(), actor)) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            for (ApprovalMachine.Task task : step.tasks()) {
                loaded.tasks().stream().filter(row2 -> row2.id().equals(task.id())).findFirst().ifPresent(old -> {
                    if (!old.status().equals(task.status().name())
                            && !store.updateAssignment(old.id(), old.version(), task.status().name(), actor)) {
                        throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
                    }
                });
            }
        }
    }

    // ---- helpers ----

    /**
     * Locks the record's row and then the request's (ADR-0148), and returns the request as it is under both. A request
     * the caller has no business with is "not found" (the callers check that before they say anything else).
     */
    private InstanceRow hold(UUID id) {
        InstanceRow plain = store.instance(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        records.findAndHold(plain.objectApiName(), plain.recordId());
        return store.instanceForUpdate(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private static void requirePending(InstanceRow instance, long version) {
        if (!instance.pending() || instance.version() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    /** Saves the request and returns it again, or refuses when somebody changed it first. */
    private InstanceRow save(InstanceRow current, String status, Integer step, String attention, String failure,
            boolean finished, UUID actor) {
        if (!store.updateInstance(current.id(), current.version(), status, step, attention, failure, finished,
                actor)) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        return store.instance(current.id()).orElseThrow();
    }

    private PinnedApprovalProcess pinned(InstanceRow instance) {
        return definitions.approvalProcessVersion(instance.processVersionId()).orElseThrow(
                () -> new IllegalStateException("The definition of a request is gone"));
    }

    private void requireStanding(UUID userId) {
        ApproverResolution.Person standing = resolver.standingOf(userId);
        if (standing == null || !standing.active() || !standing.licensed()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireEligible(UUID to, InstanceRow instance, ApprovalProcessDefinition definition, String field) {
        ApproverResolution.Person standing = resolver.standingOf(to);
        if (standing == null || !standing.active() || !standing.licensed()) {
            throw ApiException.validation(field, "This person cannot be asked: they are not an active member who "
                    + "holds a licence.");
        }
        if (!definition.allowSelfApproval() && to.equals(instance.submittedBy())) {
            throw ApiException.validation(field, "The person who submitted the request cannot be asked to decide it.");
        }
    }

    private static ApiException refusal(Refused refused, String field) {
        if ("already_asked".equals(refused.reason())) {
            return ApiException.validation(field, "This person has been asked already, in this step.");
        }
        return new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
    }

    /** A comment is bounded and empty when blank; it is text of a person and is never logged. */
    private String comment(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.strip();
        if (clean.length() > properties.limits().commentLength()) {
            throw ApiException.validation("comment", "A comment is at most " + properties.limits().commentLength()
                    + " characters.");
        }
        return clean;
    }
}
