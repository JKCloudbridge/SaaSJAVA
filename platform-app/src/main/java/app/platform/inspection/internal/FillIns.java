package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryResult;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.inspection.internal.FillInViews.FillInOptionsView;
import app.platform.inspection.internal.FillInViews.FillInPage;
import app.platform.inspection.internal.FillInViews.FillInRow;
import app.platform.inspection.internal.FillInViews.FillInStartedView;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.security.Ability;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillRefusal;
import app.platform.sheets.SheetLifecycle;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting a fill-in of a published template and listing fill-ins (FF1, ADR-0192).
 *
 * <p><strong>Who may start.</strong> A member who can see the template (it is published to them: the record store
 * says so, the cluster has no rule of its own), who holds the right to create fill-ins (the Filler access policy), on a
 * template that is not retired and has a version in use. The fill-in is pinned to that version for ever (acceptance
 * criteria 1 and 6): the database refuses a version that is not in use, so two people starting while an approver
 * publishes a newer version end on the version that was in use for each of them, never on a draft or an approved one.
 * The header is made as the platform, with the person who starts as its owner; the person has no way to make or change
 * a header themselves.
 *
 * <p><strong>Who sees which.</strong> The list is read through the record store, so a filler sees their own fill-ins
 * and a person who may view every fill-in sees all of them, by the private default of the object and the policies of
 * the organization: the cluster adds no rule. A fill-in stays in the list after its template is unpublished or retired.
 *
 * <p><strong>Cost.</strong> A page is one indexed query on the headers (newest first, by the key of the last one seen,
 * never by an offset) plus one statement over the fill-in rows of exactly those headers, which never reads a cells
 * document, plus one over the versions of the templates on the page. None grows with the number of fill-ins before the
 * page (ADR-0173 section 6).
 *
 * <p><strong>What is never written down</strong>: a name, a value, a person's name. The audit record and the metrics
 * carry identifiers and a version number.
 */
@Service
@Transactional
class FillIns {

    private static final int DEFAULT_SIZE = 50;
    private static final int MOST = 200;
    private static final Set<String> STATUSES = Set.of("DRAFT", "SUBMITTED", "RETURNED", "APPROVED");
    private static final List<String> FIELDS = List.of(Fills.NAME, Fills.TEMPLATE_ID, Fills.STATUS);
    private static final String CHANGED = "The template changed while you started. Try again.";

    private final TemplateAccess access;
    private final BackgroundRecords background;
    private final Records records;
    private final SheetLifecycle sheets;
    private final FillLifecycle fills;
    private final FillHeaders headers;
    private final List<ReferenceLabels> labels;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;

    FillIns(TemplateAccess access, BackgroundRecords background, Records records, SheetLifecycle sheets,
            FillLifecycle fills, FillHeaders headers, List<ReferenceLabels> labels, AuditRecorder audit,
            TenantContexts contexts, MeterRegistry meters) {
        this.access = access;
        this.background = background;
        this.records = records;
        this.sheets = sheets;
        this.fills = fills;
        this.headers = headers;
        this.labels = labels;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    /** Whether the caller may start a fill-in on this template, for a screen to offer or hide the button. */
    @Transactional(readOnly = true)
    FillInOptionsView options(UUID templateId) {
        Caller caller = access.visible(templateId);
        DataRecord template = template(templateId);
        int active = activeVersion(templateId);
        String reason = why(caller, template, active);
        return new FillInOptionsView(reason == null, active, reason);
    }

    /**
     * Starts a fill-in on the version of the template that is in use.
     *
     * @throws ApiException {@code NOT_FOUND} (a template the caller cannot see), {@code FORBIDDEN} (no right to start
     *         fill-ins), {@code CONFLICT} (retired, no version in use, or the version moved while this ran)
     */
    FillInStartedView start(UUID templateId) {
        Caller caller = access.visible(templateId);
        DataRecord template = template(templateId);
        if (!access.has(caller, Ability.SHEETS_FILL)) {
            refuse("start", "forbidden");
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        int active = activeVersion(templateId);
        String reason = why(caller, template, active);
        if (reason != null) {
            refuse("start", Boolean.TRUE.equals(template.fields().get(Templates.RETIRED)) ? "retired" : "not_in_use");
            throw new ApiException(ErrorCode.CONFLICT, reason);
        }
        DataRecord header;
        try {
            header = headers.create(caller.userId(), templateId, nameOf(template));
        } catch (BackgroundWriteException refused) {
            refuse("start", refused.reason().key());
            throw new ApiException(ErrorCode.CONFLICT, "The fill-in could not be started right now.");
        }
        try {
            fills.start(Fills.OBJECT, header.id(), Templates.OBJECT, templateId, active, caller.userId());
        } catch (FillRefusal refusal) {
            // The version in use moved between the read above and the insert; the header made above is undone with it.
            refuse("start", refusal.reason().name().toLowerCase(Locale.ROOT));
            throw new ApiException(ErrorCode.CONFLICT, refusal.reason() == FillRefusal.Reason.NOT_IN_USE ? CHANGED
                    : refusal.getMessage());
        }
        audit.record(AuditRecord.of("inspection.fill.started", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(contexts.require().tenantId()).onObject(Fills.OBJECT, header.id().toString())
                .with("template", templateId.toString()).with("version_no", Integer.toString(active)));
        meters.counter("platform.inspection.fill", "act", "start", "result", "ok").increment();
        return new FillInStartedView(header.id(), templateId, active);
    }

    /**
     * One page of the fill-ins the caller may see, newest first.
     *
     * @param templateId only the fill-ins of this template, or null
     * @param status only fill-ins in this status ({@code DRAFT}, {@code SUBMITTED}, {@code RETURNED},
     *        {@code APPROVED}), or null
     * @param size the page size, at most 200; 0 or null is 50
     * @param cursor the cursor of the previous page of the same question, or null
     */
    @Transactional(readOnly = true)
    FillInPage page(UUID templateId, String status, Integer size, String cursor) {
        access.member();
        String wantedStatus = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        if (wantedStatus != null && !STATUSES.contains(wantedStatus)) {
            throw ApiException.validation("status", "Choose DRAFT, SUBMITTED, RETURNED or APPROVED.");
        }
        int limit = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MOST);
        RecordQuery query = new RecordQuery(filter(templateId, wantedStatus),
                List.of(new QuerySort("createdAt", true)), FIELDS, List.of(), limit, cursor);
        QueryResult result = records.query(Fills.OBJECT, query);
        List<UUID> ids = result.records().stream().map(QueriedRecord::record).map(DataRecord::id).toList();
        Map<UUID, FillLifecycle.Summary> summaries = fills.summaries(Fills.OBJECT, ids);
        Set<UUID> templates = new LinkedHashSet<>();
        result.records().forEach(found -> {
            UUID id = Fills.templateOf(found.record().fields().get(Fills.TEMPLATE_ID));
            if (id != null) {
                templates.add(id);
            }
        });
        Map<UUID, SheetLifecycle.Summary> versions = sheets.summaries(Templates.OBJECT, templates);
        Map<UUID, String> people = people(result.records());
        List<FillInRow> rows = new ArrayList<>();
        for (QueriedRecord found : result.records()) {
            DataRecord header = found.record();
            UUID template = Fills.templateOf(header.fields().get(Fills.TEMPLATE_ID));
            FillLifecycle.Summary summary = summaries.get(header.id());
            if (template == null || summary == null) {
                continue; // a header with no fill-in row is not a fill-in a person can open
            }
            SheetLifecycle.Summary inUse = versions.get(template);
            rows.add(new FillInRow(header.id(), template, text(header, Fills.NAME), summary.status().name(),
                    summary.versionNo(), inUse != null && inUse.active() == summary.versionNo(), summary.rowCount(),
                    people.get(header.ownerId()), summary.createdAt(), summary.updatedAt(), summary.submittedAt()));
        }
        return new FillInPage(rows, result.nextCursor());
    }

    // ---- helpers ----

    /** The words that say why a fill-in cannot be started, or null when it can. */
    private String why(Caller caller, DataRecord template, int active) {
        if (!access.has(caller, Ability.SHEETS_FILL)) {
            return "You do not have the right to fill in templates.";
        }
        if (Boolean.TRUE.equals(template.fields().get(Templates.RETIRED))) {
            return "This template is retired, so no new fill-in can be started on it.";
        }
        if (active == 0) {
            return "This template has no version in use, so a fill-in cannot be started on it.";
        }
        return null;
    }

    private int activeVersion(UUID templateId) {
        SheetLifecycle.Summary summary = sheets.summaries(Templates.OBJECT, List.of(templateId)).get(templateId);
        return summary == null ? 0 : summary.active();
    }

    private DataRecord template(UUID templateId) {
        return background.find(Templates.OBJECT, templateId)
                .orElseThrow(() -> ApiException.notFound("This template does not exist."));
    }

    /** The name a new fill-in is shown by: that of the template, or a plain word when the template has none. */
    private static String nameOf(DataRecord template) {
        String name = text(template, Templates.NAME);
        return name == null ? "Fill-in" : name;
    }

    private static QueryFilter filter(UUID templateId, String status) {
        List<QueryFilter> all = new ArrayList<>();
        if (templateId != null) {
            all.add(new QueryFilter(QueryFilter.Op.EQ, null, Fills.TEMPLATE_ID, List.of(templateId.toString())));
        }
        if (status != null) {
            all.add(new QueryFilter(QueryFilter.Op.EQ, null, Fills.STATUS, List.of(status)));
        }
        if (all.isEmpty()) {
            return null;
        }
        return all.size() == 1 ? all.get(0) : new QueryFilter(QueryFilter.Op.AND, all, null, null);
    }

    /** The display names of the people who own the fill-ins on the page; one who has left has none. */
    private Map<UUID, String> people(List<QueriedRecord> found) {
        Set<UUID> owners = new LinkedHashSet<>();
        found.forEach(one -> owners.add(one.record().ownerId()));
        return labels.stream().filter(one -> "User".equals(one.object())).findFirst()
                .map(one -> one.labels(owners)).orElse(Map.of());
    }

    private static String text(DataRecord header, String field) {
        Object value = header.fields().get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private void refuse(String act, String reason) {
        meters.counter("platform.inspection.fill", "act", act, "result", reason).increment();
    }
}
