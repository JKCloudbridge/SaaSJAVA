package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWrites;
import app.platform.data.BackgroundWrites.Assignment;
import app.platform.data.BackgroundWrites.Source;
import app.platform.data.BackgroundWrites.Value;
import app.platform.data.DataRecord;
import app.platform.data.NumberSeries;
import app.platform.data.RecordShares;
import app.platform.inspection.internal.DepartmentAudiences.Resolved;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.inspection.internal.TemplateViews.AudienceOptionsView;
import app.platform.inspection.internal.TemplateViews.PublishTemplateRequest;
import app.platform.inspection.internal.TemplateViews.TemplateAudience;
import app.platform.inspection.internal.TemplateViews.TemplateLifecycle;
import app.platform.inspection.internal.TemplateViews.TemplateLifecycleView;
import app.platform.security.Ability;
import app.platform.security.Grantee;
import app.platform.security.GroupMembers;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sheets.SheetLifecycle;
import app.platform.sheets.SheetMoveRefusal;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing a template to the people of a department, taking it back, and retiring it (FA4, ADR-0188). Each act is one
 * transaction: the version moves, the shares are made or removed and the memory of them is written together, or none of
 * it happens.
 *
 * <p><strong>The access.</strong> Publishing is a read share of the header record to the group an administrator keeps
 * for the pair of location and department; the shares are the access and {@link PublicationStore} is the memory, so
 * taking the template back removes exactly the shares the publish made and never one somebody gave by hand. The people
 * who publish need the ability {@code sheets.publish}, which is a different thing from approving; they need no right
 * on the mapping object and no right to share, because the cluster decided the act. A template in review can be
 * published (its shares are not its record, ADR-0148 decision 2).
 *
 * <p><strong>What is never written down</strong>: a name, a number, a location, a department or a group. The audit
 * records and the metrics carry the template's identifier, version numbers and counts.
 */
@Service
@Transactional
class TemplatePublishing {

    private static final String RETIRED_WORDS = "This template is retired.";

    private final TemplateAccess access;
    private final PublicationStore store;
    private final SheetLifecycle sheets;
    private final RecordShares shares;
    private final DepartmentAudiences audiences;
    private final BackgroundRecords records;
    private final BackgroundWrites writes;
    private final GroupMembers groups;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;
    private final NumberSeries numbers;

    TemplatePublishing(TemplateAccess access, PublicationStore store, SheetLifecycle sheets, RecordShares shares,
            DepartmentAudiences audiences, BackgroundRecords records, BackgroundWrites writes, GroupMembers groups,
            AuditRecorder audit, TenantContexts contexts, MeterRegistry meters, NumberSeries numbers) {
        this.access = access;
        this.store = store;
        this.sheets = sheets;
        this.shares = shares;
        this.audiences = audiences;
        this.records = records;
        this.writes = writes;
        this.groups = groups;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
        this.numbers = numbers;
    }

    /** The lifecycle of one template with what a screen may offer the caller. */
    @Transactional(readOnly = true)
    TemplateLifecycleView lifecycle(UUID templateId) {
        Caller caller = access.visible(templateId);
        return view(caller, templateId, header(templateId));
    }

    /** What the publish dialog offers; needs the ability to publish. */
    @Transactional(readOnly = true)
    AudienceOptionsView audienceOptions() {
        access.require(Ability.SHEETS_PUBLISH);
        return audiences.options();
    }

    /**
     * Makes an approved version the one in use for the people of the departments, or changes who the version in use is
     * published to. Every pair is resolved before anything is shared.
     *
     * @throws ApiException {@code NOT_FOUND} (not visible), {@code FORBIDDEN} (no ability), {@code VALIDATION_ERROR}
     *         (a pair that has no group, several, a removed group or one with no members; the place names the pair),
     *         {@code CONFLICT} (retired, nothing to publish, the version is not approved)
     */
    TemplateLifecycleView publish(UUID templateId, PublishTemplateRequest request) {
        Caller caller = access.require(templateId, Ability.SHEETS_PUBLISH);
        store.lock(templateId);
        DataRecord header = header(templateId);
        if (retired(header)) {
            refuse("publish", "retired");
            throw new ApiException(ErrorCode.CONFLICT, RETIRED_WORDS + " It cannot be published.");
        }
        SheetLifecycle.Summary summary = summary(templateId);
        int wanted = request.versionNo() != null ? request.versionNo()
                : summary.approved() > 0 ? summary.approved() : summary.active();
        if (wanted <= 0) {
            refuse("publish", "nothing_to_publish");
            throw new ApiException(ErrorCode.CONFLICT, "There is no approved version of this template to publish.");
        }
        List<Resolved> resolved = resolve(request);
        int replaced = 0;
        if (wanted != summary.active()) {
            try {
                replaced = sheets.publish(Templates.OBJECT, templateId, wanted, caller.userId());
            } catch (SheetMoveRefusal refusal) {
                refuse("publish", refusal.reason().name().toLowerCase(Locale.ROOT));
                throw new ApiException(ErrorCode.CONFLICT, refusal.getMessage());
            }
        }
        Counts counts = shareWith(caller, templateId, wanted, resolved);
        audit.record(base("inspection.template.published", caller, templateId).with("version_no",
                Integer.toString(wanted)).with("replaced", Integer.toString(replaced))
                .with("audiences", Integer.toString(resolved.size())).with("shares_made",
                        Integer.toString(counts.made())).with("shares_ended", Integer.toString(counts.ended())));
        meters.counter("platform.inspection.template", "act", "publish", "result", "ok").increment();
        return view(caller, templateId, header);
    }

    /** Takes the template back from the people: the version in use is approved again and its shares are removed. */
    TemplateLifecycleView unpublish(UUID templateId) {
        Caller caller = access.require(templateId, Ability.SHEETS_PUBLISH);
        store.lock(templateId);
        DataRecord header = header(templateId);
        int version;
        try {
            version = sheets.unpublish(Templates.OBJECT, templateId, caller.userId());
        } catch (SheetMoveRefusal refusal) {
            refuse("unpublish", refusal.reason().name().toLowerCase(Locale.ROOT));
            throw new ApiException(ErrorCode.CONFLICT, "This template is not published.");
        }
        int ended = 0;
        for (PublicationStore.Row row : store.live(templateId)) {
            store.revoke(row.id(), "unpublished", caller.userId());
            if (row.madeShare() && shares.revoke(Templates.OBJECT, templateId,
                    new Grantee(Grantee.Kind.GROUP, row.groupId()), caller.userId())) {
                ended++;
            }
        }
        audit.record(base("inspection.template.unpublished", caller, templateId).with("version_no",
                Integer.toString(version)).with("shares_ended", Integer.toString(ended)));
        meters.counter("platform.inspection.template", "act", "unpublish", "result", "ok").increment();
        return view(caller, templateId, header);
    }

    /**
     * Retires the template for good: the header is marked, the version in use ends as retired, nothing is deleted, the
     * number stays with it and is never given again, and the people it was published to still read it. Refused while a
     * version is waiting for approval (the header is locked then).
     */
    TemplateLifecycleView retire(UUID templateId) {
        Caller caller = access.require(templateId, Ability.SHEETS_RETIRE);
        store.lock(templateId);
        DataRecord header = header(templateId);
        if (retired(header)) {
            refuse("retire", "already_retired");
            throw new ApiException(ErrorCode.CONFLICT, RETIRED_WORDS);
        }
        try {
            writes.update(Source.solution("retire", templateId), Templates.OBJECT, templateId,
                    List.of(new Assignment(Templates.RETIRED, new Value.Literal("true"))));
        } catch (BackgroundWriteException refused) {
            refuse("retire", refused.reason().key());
            throw new ApiException(ErrorCode.CONFLICT, refused.reason() == BackgroundWriteException.Reason.RECORD_LOCKED
                    ? "A version of this template is waiting for approval. Recall it or let the approval finish, "
                            + "then retire the template."
                    : "The template could not be retired right now.");
        }
        int version = sheets.retire(Templates.OBJECT, templateId, caller.userId());
        audit.record(base("inspection.template.retired", caller, templateId).with("version_no",
                Integer.toString(version)));
        meters.counter("platform.inspection.template", "act", "retire", "result", "ok").increment();
        return view(caller, templateId, header(templateId));
    }

    // ---- the audience ----

    private List<Resolved> resolve(PublishTemplateRequest request) {
        return audiences.resolve(request.audiences());
    }

    /**
     * Brings the shares to the groups asked for. A group published to and not asked for any more is taken back; a group
     * asked for that is not yet published to is shared with; a group in both stays and its row follows the version.
     */
    private Counts shareWith(Caller caller, UUID templateId, int version, List<Resolved> resolved) {
        Map<UUID, Resolved> desired = new LinkedHashMap<>();
        resolved.forEach(one -> desired.putIfAbsent(one.groupId(), one));
        int made = 0;
        int ended = 0;
        Set<UUID> already = new HashSet<>();
        for (PublicationStore.Row row : store.live(templateId)) {
            if (desired.containsKey(row.groupId())) {
                already.add(row.groupId());
                if (row.versionNo() != version) {
                    store.moveVersion(row.id(), version, caller.userId());
                }
            } else {
                store.revoke(row.id(), "changed", caller.userId());
                if (row.madeShare() && shares.revoke(Templates.OBJECT, templateId,
                    new Grantee(Grantee.Kind.GROUP, row.groupId()), caller.userId())) {
                    ended++;
                }
            }
        }
        for (Resolved one : desired.values()) {
            if (already.contains(one.groupId())) {
                continue;
            }
            RecordShares.Granted granted = shares.grantRead(Templates.OBJECT, templateId,
                    new Grantee(Grantee.Kind.GROUP, one.groupId()), caller.userId());
            boolean madeIt = granted == RecordShares.Granted.CREATED;
            store.insert(templateId, version, one.pick().location(), one.pick().department(), one.groupId(), madeIt,
                    caller.userId());
            if (madeIt) {
                made++;
            }
        }
        return new Counts(made, ended);
    }

    private record Counts(int made, int ended) {
    }

    // ---- the view ----

    private TemplateLifecycleView view(Caller caller, UUID templateId, DataRecord header) {
        boolean retired = retired(header);
        SheetLifecycle.Summary summary = summary(templateId);
        Map<String, String> locationLabels = null;
        Map<String, String> departmentLabels = null;
        List<TemplateAudience> audience = new ArrayList<>();
        for (PublicationStore.Row row : store.live(templateId)) {
            if (locationLabels == null) {
                locationLabels = audiences.labels(Templates.LOCATION);
                departmentLabels = audiences.labels(Templates.DEPARTMENT);
            }
            audience.add(new TemplateAudience(row.location(), row.department(),
                    locationLabels.getOrDefault(row.location(), row.location()),
                    departmentLabels.getOrDefault(row.department(), row.department()), row.versionNo(),
                    row.publishedAt(), groups.exists(row.groupId())));
        }
        boolean mayPublish = access.has(caller, Ability.SHEETS_PUBLISH);
        boolean mayRetire = access.has(caller, Ability.SHEETS_RETIRE);
        boolean somethingToPublish = summary.approved() > 0 || summary.active() > 0;
        Optional<String> whole = Optional.empty();
        Optional<String> start = Optional.empty();
        if (Templates.isBlank(header.fields().get(Templates.NUMBER)) && !retired) {
            whole = numbers.known(Templates.SERIES, header.fields());
            start = whole.isPresent() ? whole : numbers.prefix(Templates.SERIES, header.fields());
        }
        return new TemplateLifecycleView(templateId, retired, lifecycle(summary, retired), audience,
                mayPublish && !retired && somethingToPublish, mayPublish && summary.active() > 0,
                mayRetire && !retired, start.orElse(null), whole.isPresent());
    }

    static TemplateLifecycle lifecycle(SheetLifecycle.Summary summary, boolean retired) {
        return new TemplateLifecycle(stateOf(summary, retired), summary.active(), summary.approved(),
                summary.inReview(), summary.draft(), summary.retired(), summary.highest());
    }

    /** The state of a template as one word, from the latest thing that happened to it. */
    static String stateOf(SheetLifecycle.Summary summary, boolean retired) {
        if (retired) {
            return "RETIRED";
        }
        if (summary.active() > 0) {
            return "ACTIVE";
        }
        if (summary.approved() > 0) {
            return "APPROVED";
        }
        if (summary.inReview() > 0) {
            return "IN_REVIEW";
        }
        return summary.draft() > 0 ? "DRAFT" : "NONE";
    }

    // ---- helpers ----

    private DataRecord header(UUID templateId) {
        return records.find(Templates.OBJECT, templateId)
                .orElseThrow(() -> ApiException.notFound("This template does not exist."));
    }

    private static boolean retired(DataRecord header) {
        return Boolean.TRUE.equals(header.fields().get(Templates.RETIRED));
    }

    private SheetLifecycle.Summary summary(UUID templateId) {
        return sheets.summaries(Templates.OBJECT, List.of(templateId)).getOrDefault(templateId,
                new SheetLifecycle.Summary(0, 0, 0, 0, 0, 0, 0));
    }

    private AuditRecord base(String type, Caller caller, UUID templateId) {
        return AuditRecord.of(type, AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(contexts.require().tenantId()).onObject(Templates.OBJECT, templateId.toString());
    }

    private void refuse(String act, String reason) {
        meters.counter("platform.inspection.template", "act", act, "result", reason).increment();
    }
}
