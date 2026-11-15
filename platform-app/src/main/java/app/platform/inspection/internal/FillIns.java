package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryFilterNodes;
import app.platform.data.QueryResult;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.data.RecordShares;
import app.platform.data.Records;
import app.platform.inspection.internal.ActionPlanViews.ActionPlanStateView;
import app.platform.inspection.internal.FillInViews.FillInOptionsView;
import app.platform.inspection.internal.FillInViews.FillInPlantChoice;
import app.platform.inspection.internal.FillInViews.StartFillInRequest;
import app.platform.inspection.internal.FillInViews.FillInPage;
import app.platform.inspection.internal.FillInViews.FillInRow;
import app.platform.inspection.internal.FillInViews.FillInStartedView;
import app.platform.inspection.internal.FillInViews.FillInSubmittedView;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.metadata.Metadata;
import app.platform.security.Ability;
import app.platform.security.Grantee;
import app.platform.sharedkernel.approval.ApprovalApplicability;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillRefusal;
import app.platform.sheets.FillStatus;
import app.platform.sheets.FillSubmission;
import app.platform.sheets.SheetLifecycle;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
    /** The words of the refusal to start a fill-in on a template that is only a reference (FA7, ADR-0201). */
    static final String VIEW_ONLY = "This template is view only, so no fill-in can be started from it.";
    private static final String CHANGED = "The template changed while you started. Try again.";
    private static final String NO_PLANT = "This document is not published to a plant you are plant head of.";
    private static final String NO_PLANT_HEAD = "Only a plant head of this fill-in's plant can start its Action plan.";
    private static final String ACTION_PLAN_STARTED = "An Action plan was started for this fill-in already.";
    private static final String ACTION_FORM = "This form is started from the fill-in that needs it, not from the "
            + "document.";
    private static final String MONTH_TAKEN = "A fill-in for this document, this plant and this month exists already. "
            + "Open it from the list.";

    private final TemplateAccess access;
    private final BackgroundRecords background;
    private final Records records;
    private final SheetLifecycle sheets;
    private final FillLifecycle fills;
    private final FillHeaders headers;
    private final FillSubmission submission;
    private final ApprovalApplicability approvals;
    private final List<ReferenceLabels> labels;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;
    private final TemplateNumbers templateNumbers;
    private final TemplateOptionStore templateOptions;
    private final DepartmentAudiences departments;
    private final QualityHomeStore home;
    private final RecordSets sets;
    private final PlantPublicationStore plants;
    private final FillPeriodStore periods;
    private final FillPeriods clock;
    private final RecordShares shares;
    private final Metadata metadata;
    private final ActionPlanLinkStore actionForms;
    private final FillScores scores;

    FillIns(TemplateAccess access, BackgroundRecords background, Records records, SheetLifecycle sheets,
            FillLifecycle fills, FillHeaders headers, FillSubmission submission, ApprovalApplicability approvals,
            List<ReferenceLabels> labels, AuditRecorder audit, TenantContexts contexts, MeterRegistry meters,
            TemplateNumbers templateNumbers, TemplateOptionStore templateOptions, DepartmentAudiences departments,
            QualityHomeStore home, RecordSets sets, PlantPublicationStore plants, FillPeriodStore periods,
            FillPeriods clock, RecordShares shares, Metadata metadata, ActionPlanLinkStore actionForms,
            FillScores scores) {
        this.actionForms = actionForms;
        this.scores = scores;
        this.sets = sets;
        this.plants = plants;
        this.periods = periods;
        this.clock = clock;
        this.shares = shares;
        this.metadata = metadata;
        this.departments = departments;
        this.home = home;
        this.templateOptions = templateOptions;
        this.access = access;
        this.background = background;
        this.records = records;
        this.sheets = sheets;
        this.fills = fills;
        this.headers = headers;
        this.submission = submission;
        this.approvals = approvals;
        this.labels = labels;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
        this.templateNumbers = templateNumbers;
    }

    /** Whether the caller may start a fill-in on this template, for a screen to offer or hide the button. */
    @Transactional(readOnly = true)
    FillInOptionsView options(RecordSet set, UUID templateId) {
        Caller caller = access.visible(set, templateId);
        DataRecord template = template(set, templateId);
        int active = activeVersion(set, templateId);
        String reason = why(caller, template, templateId, active);
        if (!sets.monthlyFillIn(template.fields().get(Templates.APPLICATION))) {
            return new FillInOptionsView(reason == null, active, reason, false, List.of(), null);
        }
        List<FillInPlantChoice> offered = plantChoices(template, templateId, caller);
        if (reason == null && offered.isEmpty()) {
            reason = NO_PLANT;
        }
        return new FillInOptionsView(reason == null, active, reason, true, offered, clock.current());
    }

    /**
     * The plants a plant head may start a fill-in for: the plants they are plant head of (their groups, through the
     * mapping an administrator keeps) that the document is published to now.
     */
    private List<FillInPlantChoice> plantChoices(DataRecord template, UUID templateId, Caller caller) {
        RecordSet plantSet = sets.plantSetOf(template.fields().get(Templates.APPLICATION)).orElse(null);
        if (plantSet == null) {
            return List.of();
        }
        List<String> mine = headOf(plantSet, caller);
        Map<String, String> places = departments.labels(Templates.LOCATION);
        return plants.livePlants(templateId).stream().map(PlantPublicationStore.Plant::plant)
                .filter(mine::contains).map(code -> new FillInPlantChoice(code, places.getOrDefault(code, code)))
                .toList();
    }

    /** The plants whose group of plant heads the caller belongs to (the codes, each once). */
    private List<String> headOf(RecordSet plantSet, Caller caller) {
        return departments.placesOf(caller.membershipId()).stream()
                .filter(place -> place.department().equals(plantSet.placeDepartment()))
                .map(QualityHomeStore.Place::location).distinct().toList();
    }

    /** What a monthly fill-in is started with; both parts are null for a fill-in that has no plant. */
    private record Monthly(String plant, String period, UUID group) {
    }

    /**
     * Judges the plant and the month a person asks for. A document whose application does not ask for monthly fill-ins
     * takes neither; one that does needs a plant the person is plant head of and the document is published to, and a
     * month that has begun that has no fill-in yet.
     */
    private Monthly monthly(DataRecord template, UUID templateId, Caller caller, StartFillInRequest asked) {
        boolean monthly = sets.monthlyFillIn(template.fields().get(Templates.APPLICATION));
        String plant = asked == null ? null : asked.plant();
        String period = asked == null ? null : asked.period();
        if (!monthly) {
            if (plant != null) {
                throw ApiException.validation("plant", "This document does not ask for a plant.");
            }
            if (period != null) {
                throw ApiException.validation("period", "This document does not ask for a month.");
            }
            return new Monthly(null, null, null);
        }
        List<FillInPlantChoice> offered = plantChoices(template, templateId, caller);
        if (offered.isEmpty()) {
            refuse("start", "no_plant");
            throw new ApiException(ErrorCode.CONFLICT, NO_PLANT);
        }
        String chosen = plant != null ? plant : offered.size() == 1 ? offered.get(0).plant() : null;
        if (chosen == null) {
            throw ApiException.validation("plant", "Choose the plant this fill-in is for.");
        }
        if (offered.stream().noneMatch(one -> one.plant().equals(chosen))) {
            throw ApiException.validation("plant", "You can start a fill-in only for a plant you are plant head of "
                    + "and this document is published to.");
        }
        String month = period != null ? period : clock.current();
        YearMonth parsed = FillPeriods.parse(month).orElseThrow(() -> ApiException.validation("period",
                "Give the month as year and month, for example 2026-05."));
        if (clock.inTheFuture(parsed)) {
            throw ApiException.validation("period", "A month that has not begun cannot be started.");
        }
        if (periods.taken(templateId, chosen, month)) {
            refuse("start", "month_taken");
            throw new ApiException(ErrorCode.CONFLICT, MONTH_TAKEN);
        }
        UUID group = plants.livePlants(templateId).stream().filter(row -> row.plant().equals(chosen)).findFirst()
                .map(PlantPublicationStore.Plant::groupId).orElse(null);
        return new Monthly(chosen, month, group);
    }

    /**
     * Starts a fill-in on the version of the template that is in use.
     *
     * @throws ApiException {@code NOT_FOUND} (a template the caller cannot see), {@code FORBIDDEN} (no right to start
     *         fill-ins), {@code CONFLICT} (retired, no version in use, or the version moved while this ran)
     */
    FillInStartedView start(RecordSet set, UUID templateId, StartFillInRequest request) {
        Caller caller = access.visible(set, templateId);
        DataRecord template = template(set, templateId);
        if (!access.has(caller, Ability.SHEETS_FILL)) {
            refuse("start", "forbidden");
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        int active = activeVersion(set, templateId);
        String reason = why(caller, template, templateId, active);
        if (reason != null) {
            refuse("start", Boolean.TRUE.equals(template.fields().get(Templates.RETIRED)) ? "retired"
                    : templateOptions.find(templateId).viewOnly() ? "view_only" : "not_in_use");
            throw new ApiException(ErrorCode.CONFLICT, reason);
        }
        Monthly monthly = monthly(template, templateId, caller, request);
        DataRecord header;
        try {
            header = headers.create(set, caller.userId(), templateId, nameOf(template),
                    text(template, Templates.APPLICATION), monthly.plant(), monthly.period());
        } catch (BackgroundWriteException refused) {
            refuse("start", refused.reason().key());
            throw new ApiException(ErrorCode.CONFLICT, "The fill-in could not be started right now.");
        }
        try {
            fills.start(set.fill(), header.id(), set.template(), templateId, active, caller.userId());
        } catch (FillRefusal refusal) {
            // The version in use moved between the read above and the insert; the header made above is undone with it.
            refuse("start", refusal.reason().name().toLowerCase(Locale.ROOT));
            throw new ApiException(ErrorCode.CONFLICT, refusal.reason() == FillRefusal.Reason.NOT_IN_USE ? CHANGED
                    : refusal.getMessage());
        }
        if (monthly.plant() != null) {
            claimMonth(set, header.id(), templateId, monthly, caller);
        }
        // The departments of the filler are kept as codes, once, so the home page can count fill-ins by department.
        home.recordFillPlaces(header.id(), departments.placesOf(caller.membershipId()), caller.userId());
        audit.record(AuditRecord.of("inspection.fill.started", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(contexts.require().tenantId()).onObject(set.fill(), header.id().toString())
                .with("template", templateId.toString()).with("version_no", Integer.toString(active))
                .with("monthly", Boolean.toString(monthly.plant() != null)));
        meters.counter("platform.inspection.fill", "act", "start", "result", "ok").increment();
        return new FillInStartedView(header.id(), templateId, active);
    }

    /**
     * Sends the caller's own fill-in (FA6A, ADR-0196). When an approval process applies to it nothing is changed and
     * the
     * answer says a review is needed, so the way without a review is never a way round a process. Otherwise the cells
     * are checked as they are for a review, the fill-in becomes {@code SUBMITTED} for good and its header follows.
     *
     * @throws ApiException {@code NOT_FOUND} (a fill-in the caller cannot see), {@code FORBIDDEN} (they may see it but
     *         not change it), {@code CONFLICT} with the fixed words of the check that failed
     */
    FillInSubmittedView submit(RecordSet set, UUID fillId) {
        records.get(set.fill(), fillId);
        if (approvals.applies(set.fill(), fillId)) {
            return new FillInSubmittedView("REVIEW_NEEDED");
        }
        try {
            submission.submitWithoutReview(set.fill(), fillId);
        } catch (FillRefusal refusal) {
            refuse("submit", refusal.reason().name().toLowerCase(Locale.ROOT));
            throw new ApiException(ErrorCode.CONFLICT, Templates.fit(refusal.getMessage()));
        }
        try {
            headers.status(set.fill(), fillId, FillStatus.SUBMITTED);
        } catch (BackgroundWriteException refused) {
            // The header is the platform's own record: a failure here undoes the submit with this transaction.
            throw new IllegalStateException("The status of a fill-in could not follow its submit ("
                    + refused.reason().key() + ")");
        }
        meters.counter("platform.inspection.fill", "act", "submit", "result", "ok").increment();
        return new FillInSubmittedView("SUBMITTED");
    }

    // ---- the Action plan of a fill-in that scored below the limit (MC2B, ADR-0261) ----

    /**
     * Whether a fill-in needs an Action plan, whether the caller may start it and which one answers it. The caller
     * must be able to read the fill-in (the record store says so); what is told about an Action plan the caller cannot
     * read is only that one was started.
     *
     * @throws ApiException {@code NOT_FOUND} for a fill-in the caller cannot see
     */
    @Transactional(readOnly = true)
    ActionPlanStateView actionPlan(RecordSet set, UUID fillId) {
        Caller caller = access.member();
        records.get(set.fill(), fillId);
        DataRecord parent = background.find(set.fill(), fillId)
                .orElseThrow(() -> ApiException.notFound("This fill-in does not exist."));
        if (!Fills.ACTION_PLAN.equals(text(parent, Fills.RESULT))) {
            return new ActionPlanStateView(false, false, null, null, false);
        }
        UUID started = scores.actionPlanOf(set.fill(), fillId);
        if (started != null) {
            return new ActionPlanStateView(true, false, "An Action plan was started for this fill-in.",
                    visibleActionPlan(set, fillId), true);
        }
        String reason = startRefusal(set, caller, parent);
        return new ActionPlanStateView(true, reason == null, reason, null, false);
    }

    /**
     * Starts the Action plan of a fill-in that scored below the limit, for the same plant and month, on the Action plan
     * form its document names. The person who starts it must be a plant head of the plant, whether or not the form was
     * published to them: the form is read here as the platform, and the Action plan is shared with the plant's plant
     * heads read only, as a monthly fill-in is. One Action plan exists for each fill-in (the link is unique), and the
     * reviewers of the Plant activity of that plant review it.
     *
     * @throws ApiException {@code NOT_FOUND} (a fill-in the caller cannot see), {@code FORBIDDEN} (they may not fill
     *         in), {@code CONFLICT} with fixed words
     */
    FillInStartedView startActionPlan(RecordSet set, UUID fillId) {
        Caller caller = access.member();
        records.get(set.fill(), fillId);
        DataRecord parent = background.find(set.fill(), fillId)
                .orElseThrow(() -> ApiException.notFound("This fill-in does not exist."));
        if (!Fills.ACTION_PLAN.equals(text(parent, Fills.RESULT))) {
            refuse("action_plan", "not_needed");
            throw new ApiException(ErrorCode.CONFLICT, "This fill-in needs no Action plan.");
        }
        if (!access.has(caller, Ability.SHEETS_FILL)) {
            refuse("action_plan", "forbidden");
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (scores.actionPlanOf(set.fill(), fillId) != null) {
            refuse("action_plan", "started");
            throw new ApiException(ErrorCode.CONFLICT, ACTION_PLAN_STARTED);
        }
        String reason = startRefusal(set, caller, parent);
        if (reason != null) {
            refuse("action_plan", "refused");
            throw new ApiException(ErrorCode.CONFLICT, reason);
        }
        UUID parentTemplate = Fills.templateOf(parent.fields().get(Fills.TEMPLATE_ID));
        UUID formId = actionForms.actionFormOf(parentTemplate).orElseThrow();
        DataRecord form = template(set, formId);
        int active = activeVersion(set, formId);
        String plant = text(parent, Fills.PLANT);
        DataRecord header;
        try {
            header = headers.create(set, caller.userId(), formId, nameOf(form), text(form, Templates.APPLICATION),
                    plant, text(parent, Fills.PERIOD), fillId);
        } catch (BackgroundWriteException refused) {
            // The one Action plan of this fill-in was made by someone else a moment ago (the link is unique), or the
            // header could not be made; either way the start is undone and no value is named.
            refuse("action_plan", refused.reason().key());
            throw new ApiException(ErrorCode.CONFLICT, scores.actionPlanOf(set.fill(), fillId) != null
                    ? ACTION_PLAN_STARTED : "The Action plan could not be started right now.");
        }
        try {
            fills.start(set.fill(), header.id(), set.template(), formId, active, caller.userId());
        } catch (FillRefusal refusal) {
            refuse("action_plan", refusal.reason().name().toLowerCase(Locale.ROOT));
            throw new ApiException(ErrorCode.CONFLICT, refusal.reason() == FillRefusal.Reason.NOT_IN_USE ? CHANGED
                    : refusal.getMessage());
        }
        plants.livePlants(parentTemplate).stream().filter(row -> row.plant().equals(plant)).findFirst()
                .map(PlantPublicationStore.Plant::groupId).ifPresent(group -> shares.grantRead(set.fill(),
                        header.id(), new Grantee(Grantee.Kind.GROUP, group), caller.userId()));
        home.recordFillPlaces(header.id(), departments.placesOf(caller.membershipId()), caller.userId());
        audit.record(AuditRecord.of("inspection.fill.actionplan.started", AuditOutcome.SUCCESS)
                .forUser(caller.userId()).inTenant(contexts.require().tenantId())
                .onObject(set.fill(), header.id().toString()).with("answers", fillId.toString())
                .with("version_no", Integer.toString(active)));
        meters.counter("platform.inspection.fill", "act", "action_plan", "result", "ok").increment();
        return new FillInStartedView(header.id(), formId, active);
    }

    /**
     * The words that say why the caller cannot start the Action plan of this fill-in, or null when they can: they must
     * be a plant head of its plant, the fill-in must be a plain one (an Action plan has none of its own), and the form
     * its document names must be there, not retired and in use.
     */
    private String startRefusal(RecordSet set, Caller caller, DataRecord parent) {
        if (text(parent, Fills.PARENT_FILL) != null) {
            return "An Action plan needs no Action plan of its own.";
        }
        if (!access.has(caller, Ability.SHEETS_FILL)) {
            return "You do not have the right to fill in templates.";
        }
        UUID parentTemplate = Fills.templateOf(parent.fields().get(Fills.TEMPLATE_ID));
        DataRecord document = parentTemplate == null ? null
                : background.find(set.template(), parentTemplate).orElse(null);
        RecordSet plantSet = document == null ? null
                : sets.plantSetOf(document.fields().get(Templates.APPLICATION)).orElse(null);
        String plant = text(parent, Fills.PLANT);
        if (plantSet == null || plant == null || !headOf(plantSet, caller).contains(plant)) {
            return NO_PLANT_HEAD;
        }
        UUID formId = actionForms.actionFormOf(parentTemplate).orElse(null);
        if (formId == null) {
            return "This document names no Action plan form yet. Ask the person who published it.";
        }
        DataRecord form = background.find(set.template(), formId).orElse(null);
        if (form == null || !plantSet.admits(form.fields()) || Boolean.TRUE.equals(form.fields().get(Templates.RETIRED))
                || activeVersion(set, formId) == 0) {
            return "The Action plan form is not ready to be filled in. Ask the person who published the document.";
        }
        return null;
    }

    /** The Action plan that answers a fill-in when the caller can read it: the record store's own question. */
    private UUID visibleActionPlan(RecordSet set, UUID fillId) {
        QueryResult found = records.query(set.fill(), new RecordQuery(new QueryFilter(QueryFilter.Op.EQ, null,
                Fills.PARENT_FILL, List.of(fillId.toString())), List.of(), List.of(Fills.NAME), List.of(), 1, null));
        return found.records().isEmpty() ? null : found.records().get(0).record().id();
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
    FillInPage page(RecordSet set, UUID templateId, String status, Integer size, String cursor) {
        return run(set, null, null, templateId, status, List.of(new QuerySort("createdAt", true)), size, cursor);
    }

    /**
     * One page for a question a person built (UI3A): a search text, the filter and the order of a list view, and the
     * narrowing to one template or status. It goes through the same record store query as {@link #page}, so the person
     * sees exactly the fill-ins they may see.
     *
     * @param query the question; everything in it may be null
     */
    @Transactional(readOnly = true)
    FillInPage query(RecordSet set, FillInViews.FillInQuery query) {
        QueryFilter filter = query.filter() == null ? null : QueryFilterNodes.toFilter(query.filter(), "filter");
        List<QuerySort> sort = query.sort() == null || query.sort().isEmpty()
                ? List.of(new QuerySort("createdAt", true))
                : query.sort().stream().map(key -> new QuerySort(key.field(), Boolean.TRUE.equals(key.descending())))
                        .toList();
        return run(set, query.search(), filter, query.templateId(), query.status(), sort, query.size(),
                query.cursor());
    }

    private FillInPage run(RecordSet set, String search, QueryFilter extra, UUID templateId, String status,
            List<QuerySort> sort, Integer size, String cursor) {
        access.member();
        String wantedStatus = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        if (wantedStatus != null && !STATUSES.contains(wantedStatus)) {
            throw ApiException.validation("status", "Choose DRAFT, SUBMITTED, RETURNED or APPROVED.");
        }
        int limit = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MOST);
        RecordQuery query = new RecordQuery(set.narrow(filter(templateId, wantedStatus, extra, searchFilter(search))),
                sort, projection(set), List.of(), limit, cursor);
        QueryResult result = records.query(set.fill(), query);
        return new FillInPage(rowsOf(set, result.records()), result.nextCursor());
    }

    /** The search text as a condition: part of the name, or the whole word of a status. */
    private static QueryFilter searchFilter(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String text = search.strip();
        if (text.length() > 80) {
            throw ApiException.validation("search", "Is too long.");
        }
        List<QueryFilter> any = new ArrayList<>(List.of(
                new QueryFilter(QueryFilter.Op.CONTAINS, null, Fills.NAME, List.of(text))));
        String upper = text.toUpperCase(Locale.ROOT);
        if (STATUSES.contains(upper)) {
            any.add(new QueryFilter(QueryFilter.Op.EQ, null, Fills.STATUS, List.of(upper)));
        }
        return new QueryFilter(QueryFilter.Op.OR, any, null, null);
    }

    /**
     * The rows of a list for headers the caller has already been allowed to see (they come out of the record store's
     * own query): one statement over the fill-in rows of exactly these headers and one over the versions of their
     * templates, no cells. A header with no fill-in row is left out. Used by the list and by the report.
     */
    @Transactional(readOnly = true)
    List<FillInRow> rowsOf(RecordSet set, List<QueriedRecord> visible) {
        Map<String, String> places = new java.util.HashMap<>();
        if (visible.stream().anyMatch(found -> text(found.record(), Fills.PLANT) != null)) {
            places.putAll(departments.labels(Templates.LOCATION));
        }
        List<UUID> ids = visible.stream().map(QueriedRecord::record).map(DataRecord::id).toList();
        Map<UUID, FillLifecycle.Summary> summaries = fills.summaries(set.fill(), ids);
        Set<UUID> templates = new LinkedHashSet<>();
        visible.forEach(found -> {
            UUID id = Fills.templateOf(found.record().fields().get(Fills.TEMPLATE_ID));
            if (id != null) {
                templates.add(id);
            }
        });
        Map<UUID, SheetLifecycle.Summary> versions = sheets.summaries(set.template(), templates);
        Map<UUID, String> numbers = templateNumbers.of(set, templates);
        Map<UUID, String> people = people(visible);
        List<FillInRow> rows = new ArrayList<>();
        for (QueriedRecord found : visible) {
            DataRecord header = found.record();
            UUID template = Fills.templateOf(header.fields().get(Fills.TEMPLATE_ID));
            FillLifecycle.Summary summary = summaries.get(header.id());
            if (template == null || summary == null) {
                continue; // a header with no fill-in row is not a fill-in a person can open
            }
            SheetLifecycle.Summary inUse = versions.get(template);
            rows.add(new FillInRow(header.id(), template, text(header, Fills.NAME), summary.status().name(),
                    summary.versionNo(), inUse != null && inUse.active() == summary.versionNo(), summary.rowCount(),
                    people.get(header.ownerId()), summary.createdAt(), summary.updatedAt(), summary.submittedAt(),
                    summary.archivedAt(), numbers.get(template), text(header, Fills.PLANT),
                    plantLabel(places, text(header, Fills.PLANT)), text(header, Fills.PERIOD),
                    plain(header.fields().get(Fills.SCORE)), text(header, Fills.RESULT),
                    Fills.templateOf(header.fields().get(Fills.PARENT_FILL))));
        }
        return rows;
    }

    // ---- helpers ----

    /**
     * The fields a list of fill-ins reads: those of every fill-in, and the plant and the month when the fill-in object
     * has them (MC2A, ADR-0260). Only the fill-in object of an application that asks for monthly fill-ins has them, so
     * the code shared by every application never asks an object for a field it does not have.
     */
    List<String> projection(RecordSet set) {
        List<String> fields = new ArrayList<>(FIELDS);
        metadata.object(set.fill()).ifPresent(object -> {
            for (String optional : List.of(Fills.PLANT, Fills.PERIOD, Fills.SCORE, Fills.RESULT, Fills.PARENT_FILL)) {
                if (object.field(optional).isPresent()) {
                    fields.add(optional);
                }
            }
        });
        return fields;
    }

    /** A stored number as plain digits, or null when there is none. */
    private static String plain(Object stored) {
        if (stored == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(stored)).toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String plantLabel(Map<String, String> places, String plant) {
        return plant == null ? null : places.getOrDefault(plant, plant);
    }

    /** Makes the claim of the month and shares the new fill-in with the plant's plant heads, read only. */
    private void claimMonth(RecordSet set, UUID fillId, UUID templateId, Monthly monthly, Caller caller) {
        try {
            periods.claim(templateId, fillId, monthly.plant(), monthly.period(), caller.userId());
        } catch (DuplicateKeyException taken) {
            // Two plant heads starting the same month at the same moment: the database let one of them in. The whole
            // start of this one is undone with the exception, header and all.
            refuse("start", "month_taken");
            throw new ApiException(ErrorCode.CONFLICT, MONTH_TAKEN);
        }
        if (monthly.group() != null) {
            shares.grantRead(set.fill(), fillId, new Grantee(Grantee.Kind.GROUP, monthly.group()), caller.userId());
        }
    }

    /** The words that say why a fill-in cannot be started, or null when it can. */
    private String why(Caller caller, DataRecord template, UUID templateId, int active) {
        if (!access.has(caller, Ability.SHEETS_FILL)) {
            return "You do not have the right to fill in templates.";
        }
        if (Boolean.TRUE.equals(template.fields().get(Templates.RETIRED))) {
            return "This template is retired, so no new fill-in can be started on it.";
        }
        if (templateOptions.find(templateId).viewOnly()) {
            return VIEW_ONLY;
        }
        if (actionForms.isActionForm(templateId)) {
            return ACTION_FORM;
        }
        if (active == 0) {
            return "This template has no revision in use, so a fill-in cannot be started on it.";
        }
        return null;
    }

    private int activeVersion(RecordSet set, UUID templateId) {
        SheetLifecycle.Summary summary = sheets.summaries(set.template(), List.of(templateId)).get(templateId);
        return summary == null ? 0 : summary.active();
    }

    private DataRecord template(RecordSet set, UUID templateId) {
        return background.find(set.template(), templateId)
                .orElseThrow(() -> ApiException.notFound("This template does not exist."));
    }

    /** The name a new fill-in is shown by: that of the template, or a plain word when the template has none. */
    private static String nameOf(DataRecord template) {
        String name = text(template, Templates.NAME);
        return name == null ? "Fill-in" : name;
    }

    private static QueryFilter filter(UUID templateId, String status, QueryFilter... more) {
        List<QueryFilter> all = new ArrayList<>();
        for (QueryFilter one : more) {
            if (one != null) {
                all.add(one);
            }
        }
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
