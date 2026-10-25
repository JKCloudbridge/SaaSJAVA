package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.inspection.internal.RegisterStore.Stamp;
import app.platform.inspection.internal.TemplateViews.AudiencePick;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.approval.ApprovalDecisions;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHookWriter;
import app.platform.sharedkernel.approval.ApprovalHooks;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sheets.SheetLifecycle;
import app.platform.sheets.SheetMoveRefusal;
import app.platform.sheets.SheetStatus;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the approval engine asks of the cluster for a change request (FA6E, ADR-0199; changed by FA8, ADR-0202). It runs
 * inside the engine's own transaction and must be short.
 *
 * <ul>
 *   <li><strong>When the request starts</strong>: a process whose last step may be skipped is refused, because the last
 *   step is the one that publishes; a request sent again after a recall points its revised copy at the new
 *   approval.</li>
 *   <li><strong>At a step that asks for a choice</strong>: the audience Corporate QMS suggests is kept with the request
 *   (ADR-0200). It shares nothing.</li>
 *   <li><strong>At the last approval</strong> (the one that ends the request): the person must hold the Publisher
 *   access
 *   policy and the revised copy must exist. Then, all in this transaction, the copy goes through review to approved and
 *   becomes the revision in use for the audience and options of the window, the template record keeps the revision and
 *   the moment, the register is stamped with the new revision, and the request is done. Any refusal undoes the whole
 *   approval and nothing changes.</li>
 *   <li><strong>When the request ends otherwise</strong>: rejected or cancelled, the request is declined and final and
 *   the
 *   revised copy, if one was made, is ended as rejected or withdrawn so that it is never the revision in use. Recalled,
 *   the request stays open and can be sent again.</li>
 * </ul>
 *
 * <p>The person who asked and the owner of the template are told in the bell (facts only, a stable key). The people who
 * have to act are told by the engine's own task notices. Nothing a request holds is written to a log, an audit record
 * or
 * a metric: they carry identifiers.
 */
@Component
class ChangeRequestApprovalHooks implements ApprovalHooks {

    static final String LAST_STEP_WORDS = "The last step of the approval process for change requests publishes the "
            + "revision, so it cannot be skipped. Ask your administrator to switch off \"skip this step when nobody "
            + "can decide it\" on the last step.";
    static final String NOT_PUBLISHER_WORDS = "Only a person with the Publisher access policy can give the last "
            + "approval, because it publishes the revision.";
    static final String NO_COPY_WORDS = "The revised sheet has not been made yet. Press \"Create revised copy\" on the "
            + "request, make the changes, then give the last approval.";
    static final String RETIRED_WORDS = "This template is retired, so a revision cannot be published.";
    static final String COPY_WORDS = "The revised sheet cannot be sent for approval as it is. Open it, correct it and "
            + "try again.";
    static final String AUDIENCE_WORDS = "A department you chose cannot be published to: it has no group set up, or "
            + "its group has no members. Choose another or ask your administrator.";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int MOST_APPROVED_CHARS = 2000;

    private final ChangeRequestStore store;
    private final ChangeRequestHeaders headers;
    private final ChangeRequestChanges changes;
    private final ChangeRequestNotices notices;
    private final BackgroundRecords records;
    private final SheetLifecycle sheets;
    private final RegisterStore registers;
    private final TemplatePublishing publishing;
    private final AudienceSuggestions suggestions;
    private final DepartmentAudiences audiences;
    private final ApprovalDecisions decisions;
    private final Permissions permissions;
    private final TenantContexts contexts;
    private final List<ReferenceLabels> labels;

    ChangeRequestApprovalHooks(ChangeRequestStore store, ChangeRequestHeaders headers, ChangeRequestChanges changes,
            ChangeRequestNotices notices, BackgroundRecords records, SheetLifecycle sheets, RegisterStore registers,
            TemplatePublishing publishing, AudienceSuggestions suggestions, DepartmentAudiences audiences,
            ApprovalDecisions decisions, Permissions permissions, TenantContexts contexts,
            List<ReferenceLabels> labels) {
        this.store = store;
        this.headers = headers;
        this.changes = changes;
        this.notices = notices;
        this.records = records;
        this.sheets = sheets;
        this.registers = registers;
        this.publishing = publishing;
        this.suggestions = suggestions;
        this.audiences = audiences;
        this.decisions = decisions;
        this.permissions = permissions;
        this.contexts = contexts;
        this.labels = labels;
    }

    @Override
    public String object() {
        return RequestRecords.OBJECT;
    }

    @Override
    public void onSubmit(SubmitContext context) {
        if (context.lastStepSkippable()) {
            throw new ApprovalHookRefusal("last_step_skippable", LAST_STEP_WORDS);
        }
        // A request sent again after it was recalled is decided by a new approval request: the revised copy follows it.
        // At the first submit the change request does not exist yet and there is no copy.
        UUID actor = context.submitter() != null ? context.submitter() : ActorId.SYSTEM.value();
        store.findByHeader(context.recordId()).ifPresent(row -> sheets.retargetHeldDraft(Templates.OBJECT,
                row.templateId(), context.requestId(), actor));
    }

    @Override
    public void beforeApprove(DecisionContext context, ApprovalHookWriter writer) {
        RequestRow row = store.findByHeader(context.recordId()).orElse(null);
        if (row == null) {
            return;
        }
        if (!context.endsRequest()) {
            if (context.asksForChoice()) {
                suggestions.accept(context, row.templateId());
            }
            return;
        }
        publish(context, row);
    }

    @Override
    public void onEnded(EndContext context) {
        RequestRow row = store.findByHeader(context.recordId()).orElse(null);
        if (row == null) {
            return;
        }
        UUID actor = context.endedBy() != null ? context.endedBy() : ActorId.SYSTEM.value();
        switch (context.outcome()) {
            case APPROVED -> published(context, row, actor);
            case REJECTED -> declined(context, row, actor, SheetStatus.REJECTED);
            case CANCELLED -> declined(context, row, actor, SheetStatus.WITHDRAWN);
            case RECALLED -> {
                // The request stays open and can be sent again; its revised copy stays with it.
            }
            default -> throw new IllegalStateException("Unreachable: every outcome is handled above");
        }
    }

    // ---- the last approval ----

    /** The act of the last approval: see the class comment. Every refusal is a fixed sentence. */
    private void publish(DecisionContext context, RequestRow row) {
        UUID templateId = row.templateId();
        TenantContext member = contexts.current().orElse(null);
        if (member == null || member.membershipId() == null
                || !permissions.has(member.membershipId(), Ability.SHEETS_PUBLISH)) {
            throw new ApprovalHookRefusal("publisher_needed", NOT_PUBLISHER_WORDS);
        }
        DataRecord template = records.find(Templates.OBJECT, templateId)
                .orElseThrow(() -> new ApprovalHookRefusal("template_missing", "This template cannot be found."));
        if (Boolean.TRUE.equals(template.fields().get(Templates.RETIRED))) {
            throw new ApprovalHookRefusal("template_retired", RETIRED_WORDS);
        }
        SheetLifecycle.HeldDraft copy = sheets.heldDraft(Templates.OBJECT, templateId)
                .orElseThrow(() -> new ApprovalHookRefusal("no_revised_copy", NO_COPY_WORDS));
        AudienceChoices.Publish choice = AudienceChoices.parsePublish(context.choices());
        checkOffered(choice);
        UUID approver = context.approver();
        try {
            sheets.submit(Templates.OBJECT, templateId, context.requestId(), approver);
            sheets.reviewed(Templates.OBJECT, templateId, context.requestId(), SheetStatus.APPROVED, approver);
        } catch (SheetMoveRefusal refusal) {
            throw new ApprovalHookRefusal("revised_copy_" + refusal.reason().name().toLowerCase(Locale.ROOT),
                    Templates.fit(refusal.getMessage()));
        }
        TemplatePublishing.Published made;
        try {
            List<AudiencePick> picks = choice.places().stream()
                    .map(place -> new AudiencePick(place.location(), place.department())).toList();
            made = publishing.publishRevised(templateId, copy.versionNo(), approver, picks, choice.viewOnly(),
                    choice.allowDownload());
        } catch (ApiException notReady) {
            throw new ApprovalHookRefusal("audience_not_ready", AUDIENCE_WORDS);
        } catch (SheetMoveRefusal refusal) {
            throw new ApprovalHookRefusal("revised_copy_" + refusal.reason().name().toLowerCase(Locale.ROOT),
                    Templates.fit(refusal.getMessage()));
        }
        String revision = TemplateRevisions.show(made.revision());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        registers.stamp(row.id(), new Stamp(revision, today, approvedBy(context), signature(today)), approver);
        if (!store.answer(row.id(), row.state(), ChangeRequestStatus.DONE, null, copy.versionNo(), approver)) {
            throw new IllegalStateException("The change request moved while its last approval was given");
        }
        changes.published(approver, templateId, row.id(), copy.versionNo(), made.revision(),
                choice.places().size());
    }

    /** The audience must still be offered by the organization lists. */
    private void checkOffered(AudienceChoices.Publish choice) {
        Map<String, List<String>> offered = audiences.offered();
        for (AudienceChoices.Place place : choice.places()) {
            List<String> departments = offered.get(place.location());
            if (departments == null || !departments.contains(place.department())) {
                throw new ApprovalHookRefusal("choice_not_offered", AudienceChoices.NOT_OFFERED);
            }
        }
    }

    /** Who approved so far and the person giving the last approval, with the day of each, one line each. */
    private String approvedBy(DecisionContext context) {
        List<ApprovalDecisions.Approved> done = decisions.approvalsOf(context.requestId());
        Set<UUID> people = new HashSet<>();
        done.forEach(one -> people.add(one.userId()));
        people.add(context.approver());
        Map<UUID, String> names = labels.stream().filter(one -> "User".equals(one.object())).findFirst()
                .map(one -> one.labels(people)).orElse(Map.of());
        StringBuilder text = new StringBuilder();
        for (ApprovalDecisions.Approved one : done) {
            line(text, names.get(one.userId()), one.stepName(), one.at().atZone(ZoneOffset.UTC).toLocalDate());
        }
        line(text, names.get(context.approver()), context.stepName(), LocalDate.now(ZoneOffset.UTC));
        String all = text.toString().strip();
        return all.length() <= MOST_APPROVED_CHARS ? all : all.substring(0, MOST_APPROVED_CHARS);
    }

    private static void line(StringBuilder text, String name, String step, LocalDate day) {
        String who = name == null || name.isBlank() ? "A former member" : name;
        text.append(who);
        if (step != null && !step.isBlank()) {
            text.append(" (").append(step).append(')');
        }
        text.append(", ").append(DAY.format(day)).append('\n');
    }

    private static String signature(LocalDate day) {
        return "Approved electronically on " + DAY.format(day);
    }

    // ---- when the request ends ----

    /** The request ended approved: it was published by the last approval; the copy of its status and the bell follow.
     * */
    private void published(EndContext context, RequestRow row, UUID actor) {
        if (row.state() != ChangeRequestStatus.DONE) {
            // Only the last approval publishes. A request that ends approved without it (a process that was started
            // before this release) must not look finished: the decision is undone.
            throw new IllegalStateException("A change request ended approved without being published");
        }
        followOnRecord(context, ChangeRequestStatus.DONE);
        tellBoth(row, actor, "done");
    }

    /** The request ended without being published: it is declined for good and the revised copy is ended unused. */
    private void declined(EndContext context, RequestRow row, UUID actor, SheetStatus copyEnds) {
        if (!row.state().canMoveTo(ChangeRequestStatus.DECLINED)) {
            return;
        }
        int copy = sheets.closeHeldDraft(Templates.OBJECT, row.templateId(), context.requestId(), copyEnds, actor);
        if (copy != 0) {
            changes.copyDiscarded(actor, row.templateId(), row.id(), copy,
                    copyEnds == SheetStatus.REJECTED ? "rejected" : "withdrawn");
        }
        if (!store.answer(row.id(), row.state(), ChangeRequestStatus.DECLINED, null, null, actor)) {
            return;
        }
        followOnRecord(context, ChangeRequestStatus.DECLINED);
        changes.answered(actor, row.templateId(), row.id(), "declined");
        tellBoth(row, actor, "declined");
    }

    private void followOnRecord(EndContext context, ChangeRequestStatus to) {
        try {
            headers.status(context.recordId(), to);
        } catch (BackgroundWriteException refused) {
            // The record is the platform own, so this is a fault and not an answer for the person: the decision is
            // undone and the error tracking sees a failure without a value.
            throw new IllegalStateException("The status of a change request could not follow its review ("
                    + refused.reason().key() + ")");
        }
    }

    private void tellBoth(RequestRow row, UUID actor, String act) {
        notices.tell(row.createdBy(), actor, row.templateId(), row.id(), act, "chg:" + row.id() + ":" + act);
        Optional<UUID> owner = records.find(Templates.OBJECT, row.templateId()).map(DataRecord::ownerId);
        owner.ifPresent(one -> notices.tell(one, actor, row.templateId(), row.id(), act,
                "chg:" + row.id() + ":" + act + ":owner"));
    }
}
