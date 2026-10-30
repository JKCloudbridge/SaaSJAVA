package app.platform.inspection.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryResult;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.inspection.internal.HomePeriod.Window;
import app.platform.inspection.internal.QualityHomeStore.Place;
import app.platform.inspection.internal.QualityHomeViews.Activity;
import app.platform.inspection.internal.QualityHomeViews.AppliedView;
import app.platform.inspection.internal.QualityHomeViews.ChartsView;
import app.platform.inspection.internal.QualityHomeViews.DayCount;
import app.platform.inspection.internal.QualityHomeViews.DaysView;
import app.platform.inspection.internal.QualityHomeViews.DepartmentCount;
import app.platform.inspection.internal.QualityHomeViews.DepartmentTemplateCount;
import app.platform.inspection.internal.QualityHomeViews.DepartmentOption;
import app.platform.inspection.internal.QualityHomeViews.FiltersView;
import app.platform.inspection.internal.QualityHomeViews.LevelTypeCount;
import app.platform.inspection.internal.QualityHomeViews.LocationOption;
import app.platform.inspection.internal.QualityHomeViews.MonthCount;
import app.platform.inspection.internal.QualityHomeViews.PeriodView;
import app.platform.inspection.internal.QualityHomeViews.StatusCount;
import app.platform.inspection.internal.QualityHomeViews.StepDays;
import app.platform.inspection.internal.QualityHomeViews.SummaryRequest;
import app.platform.inspection.internal.QualityHomeViews.SummaryView;
import app.platform.inspection.internal.QualityHomeViews.TemplateCount;
import app.platform.inspection.internal.QualityHomeViews.Tile;
import app.platform.inspection.internal.QualityHomeViews.TilesView;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.Metadata;
import app.platform.metadata.PicklistValue;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.approval.ApprovalDecisions;
import app.platform.sharedkernel.approval.ApprovalStarter;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillLifecycle.Submission;
import app.platform.sheets.SheetLifecycle;
import app.platform.sheets.SheetLifecycle.VersionDates;
import app.platform.sheets.SheetStatus;
import app.platformapi.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The numbers and graphs of the home page of the Quality Management System (FA9, ADR-0203).
 *
 * <p><strong>The counting rule.</strong> A number never includes a record the caller may not read, and there is no
 * rule of this class about who may see a record: every count is made over identifiers the record store has already
 * judged (the templates and fill-ins it lists for this caller, the change requests {@link ChangeRequestCounts} lets
 * them see), so the page agrees with the lists. The record store gives no total, so the headers are read page by page,
 * grouped in memory and bounded by {@code platform.inspection.home.max-scanned}; a number that reached the bound is
 * marked "at least". Nothing is cached across people.
 *
 * <p><strong>Not drawn.</strong> A section whose object the caller cannot read at all is null in the answer, never a
 * zero that would hint at something. A section that failed is named in {@code problems}, and the others are still
 * answered.
 *
 * <p>Nothing typed, no name, no template number and no filter value reaches a log, an audit record or a metric; the
 * metric {@code platform.inspection.home} carries the result and the duration.
 */
@Service
@Transactional(readOnly = true)
class QualityHome {

    private static final Logger LOG = LoggerFactory.getLogger(QualityHome.class);
    /** Identifiers sent to one statement. */
    private static final int CHUNK = 500;
    /** Identifiers sent to one record query (the query engine's own limit of one "in" condition is 100). */
    private static final int JUDGE = 100;
    private static final int MOST_EVENTS = 5_000;
    private static final int TREND_MONTHS = 12;
    private static final int TOP_TEMPLATES = 10;
    private static final int TOP_DEPARTMENTS = 30;
    private static final int TOP_TEMPLATES_PER_DEPARTMENT = 30;
    private static final String NOT_RECORDED = "Not recorded";
    /** The dot between a location and a department, as the labels of the notices and the register read. */
    private static final char MIDDLE_DOT = (char) 0xB7;
    private static final List<String> TEMPLATE_FIELDS = List.of(Templates.NAME, Templates.LEVEL,
            Templates.DOCUMENT_TYPE, Templates.RETIRED);
    private static final Set<SheetStatus> APPROVED_LIKE = EnumSet.of(SheetStatus.APPROVED, SheetStatus.ACTIVE,
            SheetStatus.SUPERSEDED, SheetStatus.RETIRED);
    /** The words the list of templates uses for the same states, so the page and the list say one thing. */
    private static final Map<String, String> STATE_WORDS = Map.of("DRAFT", "Draft", "IN_REVIEW", "In review",
            "APPROVED", "Approved, not published", "ACTIVE", "Active", "RETIRED", "Retired", "NONE",
            "No revision yet");
    private static final List<String> STATE_ORDER = List.of("DRAFT", "IN_REVIEW", "APPROVED", "ACTIVE", "RETIRED",
            "NONE");

    private final TemplateAccess access;
    private final Metadata metadata;
    private final Decisions decisions;
    private final Records records;
    private final SheetLifecycle sheets;
    private final FillLifecycle fills;
    private final ChangeRequestCounts requestCounts;
    private final QualityHomeStore store;
    private final DepartmentAudiences departments;
    private final ApprovalStarter starter;
    private final ApprovalDecisions approvalDecisions;
    private final List<ReferenceLabels> labels;
    private final MeterRegistry meters;
    private final QualityHomeProperties properties;
    private final Clock clock;
    private final TransactionTemplate savepoint;

    QualityHome(TemplateAccess access, Metadata metadata, Decisions decisions, Records records, SheetLifecycle sheets,
            FillLifecycle fills, ChangeRequestCounts requestCounts, QualityHomeStore store,
            DepartmentAudiences departments, ApprovalStarter starter, ApprovalDecisions approvalDecisions,
            List<ReferenceLabels> labels, MeterRegistry meters, QualityHomeProperties properties, Clock clock,
            PlatformTransactionManager transactions) {
        this.savepoint = new TransactionTemplate(transactions);
        // A section that fails is rolled back to here and the others go on: a refusal that passes through the
        // record store must not leave the whole request rollback-only.
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.access = access;
        this.metadata = metadata;
        this.decisions = decisions;
        this.records = records;
        this.sheets = sheets;
        this.fills = fills;
        this.requestCounts = requestCounts;
        this.store = store;
        this.departments = departments;
        this.starter = starter;
        this.approvalDecisions = approvalDecisions;
        this.labels = labels;
        this.meters = meters;
        this.properties = properties;
        this.clock = clock;
    }

    /** What the filters chose, once checked against what the organization offers. */
    private record Chosen(String location, String department) {

        /** Whether a thing with these places passes both filters; with no filter everything does. */
        boolean allows(Set<Place> places) {
            if (location == null && department == null) {
                return true;
            }
            return places != null && places.stream().anyMatch(place -> (location == null
                    || location.equals(place.location())) && (department == null
                            || department.equals(place.department())));
        }

        /** The same with the location alone: the department chart shows every department of the place. */
        boolean allowsLocation(Set<Place> places) {
            return location == null || (places != null && places.stream()
                    .anyMatch(place -> location.equals(place.location())));
        }

        boolean any() {
            return location != null || department != null;
        }
    }

    /** What the scan of the templates the caller may see found. */
    private record TemplateFact(String name, String level, String type, boolean retired, String state) {
    }

    /** One thing that happened, before the person is turned into a name. */
    private record Raw(String kind, Instant at, UUID by, String about, UUID id, String name) {
    }

    /** Everything the templates give: tiles, graphs and the part of the activity list that is about them. */
    private record TemplateResult(Tile activeTemplates, Tile waitingApproval, Tile openChangeRequests,
            Tile templatesPublished, Tile averageApprovalDays, Tile rejectedShare, List<StatusCount> byStatus,
            List<LevelTypeCount> byLevelAndType, List<StepDays> stepDays, List<MonthCount> perMonth,
            List<Raw> activity) {
    }

    /** Everything the fill-ins give. */
    private record FillResult(Tile submitted, Tile waiting, DaysView perDay, List<TemplateCount> byTemplate,
            List<DepartmentCount> byDepartment, List<DepartmentTemplateCount> byDepartmentAndTemplate,
            List<Raw> activity) {
    }

    /**
     * Works the page out for the caller.
     *
     * @throws ApiException {@code NOT_FOUND} on the platform host and for an organization without the plan feature,
     *         {@code VALIDATION_ERROR} for a period, location or department that is not offered
     */
    SummaryView summary(SummaryRequest request) {
        long started = System.nanoTime();
        String result = "refused";
        try {
            SummaryView view = work(request);
            result = view.problems().isEmpty() ? "ok" : "partial";
            return view;
        } finally {
            meters.timer("platform.inspection.home", "result", result).record(Duration.ofNanos(System.nanoTime()
                    - started));
        }
    }

    private SummaryView work(SummaryRequest request) {
        Caller caller = access.member();
        if (metadata.object(Templates.OBJECT).isEmpty()) {
            throw ApiException.notFound("This page does not exist.");
        }
        Instant now = clock.instant();
        Window window = HomePeriod.of(HomePeriod.parse(request == null ? null : request.period()), now);
        Map<String, List<String>> offered = departments.offered();
        Chosen chosen = chosen(request, offered);
        List<String> problems = new ArrayList<>();

        boolean readsTemplates = reads(caller, Templates.OBJECT);
        boolean readsFills = reads(caller, Fills.OBJECT);
        boolean readsRequests = reads(caller, RequestRecords.OBJECT);

        TemplateResult templates = readsTemplates ? attempt("templates", problems,
                () -> readTemplates(caller, window, now, chosen, readsRequests)) : null;
        FillResult fillResult = readsFills ? attempt("fill-ins", problems,
                () -> readFills(caller, window, now, chosen)) : null;

        List<Activity> activity = activity(templates, fillResult);
        TilesView tiles = new TilesView(templates == null ? null : templates.activeTemplates(),
                templates == null ? null : templates.waitingApproval(),
                templates == null ? null : templates.openChangeRequests(),
                fillResult == null ? null : fillResult.submitted(), fillResult == null ? null : fillResult.waiting(),
                templates == null ? null : templates.templatesPublished(),
                templates == null ? null : templates.averageApprovalDays(),
                templates == null ? null : templates.rejectedShare());
        ChartsView charts = new ChartsView(fillResult == null ? null : fillResult.perDay(),
                templates == null ? null : templates.byStatus(),
                templates == null ? null : templates.byLevelAndType(),
                fillResult == null ? null : fillResult.byTemplate(),
                fillResult == null ? null : fillResult.byDepartment(),
                templates == null ? null : templates.stepDays(),
                templates == null ? null : templates.perMonth(), activity,
                fillResult == null ? null : fillResult.byDepartmentAndTemplate());
        return new SummaryView(now,
                new PeriodView(window.key().text(), window.from(), window.to(), window.previousFrom(),
                        window.previousTo()),
                filters(offered), new AppliedView(chosen.location(), chosen.department()), tiles, charts, problems);
    }

    // ---- the filters ----

    private Chosen chosen(SummaryRequest request, Map<String, List<String>> offered) {
        String location = request == null ? null : blankToNull(request.location());
        String department = request == null ? null : blankToNull(request.department());
        if (location != null && !offered.containsKey(location)) {
            throw ApiException.validation("location", "Choose a location from the list.");
        }
        if (department != null) {
            boolean known = location != null ? offered.get(location).contains(department)
                    : offered.values().stream().anyMatch(codes -> codes.contains(department));
            if (!known) {
                throw ApiException.validation("department", "Choose a department from the list.");
            }
        }
        return new Chosen(location, department);
    }

    private FiltersView filters(Map<String, List<String>> offered) {
        Map<String, String> places = departments.labels(Templates.LOCATION);
        Map<String, String> parts = departments.labels(Templates.DEPARTMENT);
        List<LocationOption> options = new ArrayList<>();
        offered.forEach((code, codes) -> options.add(new LocationOption(code, places.getOrDefault(code, code),
                codes.stream().map(one -> new DepartmentOption(one, parts.getOrDefault(one, one))).toList())));
        return new FiltersView(options);
    }

    // ---- the templates ----

    private TemplateResult readTemplates(Caller caller, Window window, Instant now, Chosen chosen,
            boolean readsRequests) {
        boolean[] more = new boolean[1];
        Map<UUID, TemplateFact> facts = scanTemplates(more);
        List<UUID> ids = List.copyOf(facts.keySet());
        Instant since = HomePeriod.monthsStart(now, TREND_MONTHS).isBefore(window.previousFrom())
                ? HomePeriod.monthsStart(now, TREND_MONTHS) : window.previousFrom();
        List<VersionDates> dates = new ArrayList<>();
        Map<UUID, Set<Place>> places = new HashMap<>();
        for (List<UUID> chunk : chunks(ids, CHUNK)) {
            dates.addAll(sheets.dates(Templates.OBJECT, chunk, since));
            places.putAll(store.livePlaces(chunk));
        }
        List<QualityHomeStore.Event> events = store.eventsSince(since, MOST_EVENTS).stream()
                .filter(event -> facts.containsKey(event.templateId())).toList();

        // Before this release no event was kept, so a version that went into use is also counted as published on the
        // day its approval ended (the last approval publishes); a template that has both is still counted once.
        List<QualityHomeStore.Event> publishing = new ArrayList<>(events);
        for (VersionDates one : dates) {
            if (one.decidedAt() != null && (one.status() == SheetStatus.ACTIVE || one.status() == SheetStatus.SUPERSEDED
                    || one.status() == SheetStatus.RETIRED) && facts.containsKey(one.ownerId())) {
                publishing.add(new QualityHomeStore.Event(one.ownerId(), "PUBLISHED", one.versionNo(), null,
                        one.decidedAt(), null));
            }
        }

        Tile active = new Tile((double) facts.entrySet().stream().filter(one -> "ACTIVE".equals(one.getValue().state())
                && chosen.allows(places.get(one.getKey()))).count(), null, null, more[0], true, true);

        List<VersionDates> inReview = dates.stream().filter(one -> one.status() == SheetStatus.IN_REVIEW).toList();
        Instant oldest = inReview.stream().map(VersionDates::submittedAt).filter(at -> at != null)
                .min(Comparator.naturalOrder()).orElse(null);
        Tile waiting = new Tile((double) inReview.size(), null, oldest == null ? null
                : (double) HomePeriod.wholeDays(oldest, now), more[0], false, false);

        Tile openRequests = null;
        if (readsRequests) {
            long open = 0;
            for (List<UUID> chunk : chunks(ids, CHUNK)) {
                open += requestCounts.waiting(caller, chunk).values().stream().mapToLong(Integer::longValue).sum();
            }
            openRequests = new Tile((double) open, null, null, more[0], false, false);
        }

        Tile published = new Tile((double) published(publishing, window::contains, places, chosen).size(),
                (double) published(publishing, window::containsPrevious, places, chosen).size(), null, more[0], true,
                true);

        List<VersionDates> decided = dates.stream().filter(one -> one.decidedAt() != null).toList();
        Tile average = new Tile(HomePeriod.averageDays(durations(decided, window::contains)),
                HomePeriod.averageDays(durations(decided, window::containsPrevious)), null, more[0], false, false);
        Tile rejected = new Tile(rejectedShare(decided, window::contains), rejectedShare(decided,
                window::containsPrevious), null, more[0], false, false);

        return new TemplateResult(active, waiting, openRequests, published, average, rejected, byStatus(facts),
                byLevelAndType(facts), stepDays(decided, window), perMonth(publishing, places, chosen, now),
                templateActivity(events, facts));
    }

    private Map<UUID, TemplateFact> scanTemplates(boolean[] more) {
        Map<UUID, TemplateFact> found = new LinkedHashMap<>();
        Map<UUID, DataRecord> headers = new LinkedHashMap<>();
        String cursor = null;
        while (true) {
            QueryResult page = records.query(Templates.OBJECT,
                    new RecordQuery(null, List.of(), TEMPLATE_FIELDS, List.of(), 200, cursor));
            for (QueriedRecord one : page.records()) {
                if (headers.size() >= properties.maxScanned()) {
                    more[0] = true;
                    break;
                }
                headers.put(one.record().id(), one.record());
            }
            cursor = page.nextCursor();
            if (more[0] || cursor == null) {
                break;
            }
        }
        List<UUID> ids = List.copyOf(headers.keySet());
        Map<UUID, SheetLifecycle.Summary> summaries = new HashMap<>();
        for (List<UUID> chunk : chunks(ids, CHUNK)) {
            summaries.putAll(sheets.summaries(Templates.OBJECT, chunk));
        }
        for (DataRecord header : headers.values()) {
            boolean retired = Boolean.TRUE.equals(header.fields().get(Templates.RETIRED));
            SheetLifecycle.Summary summary = summaries.getOrDefault(header.id(),
                    new SheetLifecycle.Summary(0, 0, 0, 0, 0, 0, 0));
            found.put(header.id(), new TemplateFact(text(header, Templates.NAME), text(header, Templates.LEVEL),
                    text(header, Templates.DOCUMENT_TYPE), retired, TemplatePublishing.stateOf(summary, retired)));
        }
        return found;
    }

    /** The templates published or revised in a period that pass the filters: each template once. */
    private static Set<UUID> published(List<QualityHomeStore.Event> events,
            java.util.function.Predicate<Instant> inPeriod, Map<UUID, Set<Place>> places, Chosen chosen) {
        Set<UUID> found = new HashSet<>();
        for (QualityHomeStore.Event event : events) {
            boolean publishing = "PUBLISHED".equals(event.kind()) || "REVISED".equals(event.kind());
            if (publishing && inPeriod.test(event.at()) && chosen.allows(places.get(event.templateId()))) {
                found.add(event.templateId());
            }
        }
        return found;
    }

    private static List<Duration> durations(List<VersionDates> decided,
            java.util.function.Predicate<Instant> inPeriod) {
        return decided.stream().filter(one -> APPROVED_LIKE.contains(one.status()) && one.submittedAt() != null
                && inPeriod.test(one.decidedAt())).map(one -> Duration.between(one.submittedAt(), one.decidedAt()))
                .filter(one -> !one.isNegative()).toList();
    }

    private static Double rejectedShare(List<VersionDates> decided, java.util.function.Predicate<Instant> inPeriod) {
        long rejected = decided.stream().filter(one -> one.status() == SheetStatus.REJECTED
                && inPeriod.test(one.decidedAt())).count();
        long approved = decided.stream().filter(one -> APPROVED_LIKE.contains(one.status())
                && inPeriod.test(one.decidedAt())).count();
        return HomePeriod.percent(rejected, rejected + approved);
    }

    private List<StatusCount> byStatus(Map<UUID, TemplateFact> facts) {
        Map<String, Long> counts = new HashMap<>();
        facts.values().forEach(one -> counts.merge(one.state(), 1L, Long::sum));
        return STATE_ORDER.stream().map(key -> new StatusCount("NONE".equals(key) ? "NO_SHEET" : key,
                STATE_WORDS.get(key), counts.getOrDefault(key, 0L))).toList();
    }

    private List<LevelTypeCount> byLevelAndType(Map<UUID, TemplateFact> facts) {
        Map<String, String> levels = picklistLabels(Templates.LEVEL);
        Map<String, String> types = picklistLabels(Templates.DOCUMENT_TYPE);
        Map<List<String>, Long> counts = new TreeMap<>(Comparator.comparing((List<String> key) -> key.get(0))
                .thenComparing(key -> key.get(1)));
        facts.values().stream().filter(one -> !one.retired())
                .forEach(one -> counts.merge(List.of(one.level(), one.type()), 1L, Long::sum));
        List<LevelTypeCount> result = new ArrayList<>();
        counts.forEach((key, count) -> result.add(new LevelTypeCount(key.get(0),
                key.get(0).isEmpty() ? "Not set" : levels.getOrDefault(key.get(0), key.get(0)), key.get(1),
                key.get(1).isEmpty() ? "Not set" : types.getOrDefault(key.get(1), key.get(1)), count)));
        return result;
    }

    private Map<String, String> picklistLabels(String field) {
        Map<String, String> found = new LinkedHashMap<>();
        metadata.object(Templates.OBJECT).flatMap(object -> object.field(field))
                .filter(one -> one.configuration() instanceof PicklistConfiguration)
                .ifPresent(one -> ((PicklistConfiguration) one.configuration()).values()
                        .forEach((PicklistValue value) -> found.put(value.value(), value.label())));
        return found;
    }

    /** The days an approval spends at each step, on average, over the approvals decided in the period. */
    private List<StepDays> stepDays(List<VersionDates> decided, Window window) {
        List<VersionDates> chosen = decided.stream().filter(one -> APPROVED_LIKE.contains(one.status())
                && one.approvalRequestId() != null && one.submittedAt() != null && window.contains(one.decidedAt()))
                .sorted(Comparator.comparing(VersionDates::decidedAt).reversed())
                .limit(properties.maxApprovals()).toList();
        Map<Integer, String> names = new TreeMap<>();
        Map<Integer, List<Duration>> spent = new TreeMap<>();
        for (VersionDates one : chosen) {
            Instant before = one.submittedAt();
            for (ApprovalDecisions.Approved approved : approvalDecisions.approvalsOf(one.approvalRequestId())) {
                Duration took = Duration.between(before, approved.at());
                if (!took.isNegative()) {
                    spent.computeIfAbsent(approved.stepPosition(), key -> new ArrayList<>()).add(took);
                    names.putIfAbsent(approved.stepPosition(), approved.stepName());
                }
                before = approved.at();
            }
        }
        List<StepDays> result = new ArrayList<>();
        spent.forEach((position, list) -> result.add(new StepDays(position, names.get(position),
                HomePeriod.averageDays(list), (long) list.size())));
        return result;
    }

    private List<MonthCount> perMonth(List<QualityHomeStore.Event> events, Map<UUID, Set<Place>> places,
            Chosen chosen, Instant now) {
        Map<String, Set<UUID>> perMonth = new HashMap<>();
        for (QualityHomeStore.Event event : events) {
            boolean publishing = "PUBLISHED".equals(event.kind()) || "REVISED".equals(event.kind());
            if (publishing && chosen.allows(places.get(event.templateId()))) {
                perMonth.computeIfAbsent(HomePeriod.monthOf(event.at()), key -> new HashSet<>())
                        .add(event.templateId());
            }
        }
        return HomePeriod.months(now, TREND_MONTHS).stream().map(month -> new MonthCount(month,
                (long) perMonth.getOrDefault(month, Set.of()).size())).toList();
    }

    private List<Raw> templateActivity(List<QualityHomeStore.Event> events, Map<UUID, TemplateFact> facts) {
        return events.stream().limit(properties.recentActivity()).map(event -> new Raw(event.kind(), event.at(),
                event.by(), "template", event.templateId(), facts.get(event.templateId()).name())).toList();
    }

    // ---- the fill-ins ----

    private FillResult readFills(Caller caller, Window window, Instant now, Chosen chosen) {
        int max = properties.maxScanned();
        List<Submission> sent = fills.submittedBetween(Fills.OBJECT, window.previousFrom(), window.to(), max + 1);
        boolean more = sent.size() > max;
        if (more) {
            sent = sent.subList(0, max);
        }
        Map<UUID, String> names = judge(sent.stream().map(Submission::fillId).toList());
        List<Submission> visible = sent.stream().filter(one -> names.containsKey(one.fillId())).toList();
        Map<UUID, Set<Place>> places = new HashMap<>();
        for (List<UUID> chunk : chunks(visible.stream().map(Submission::fillId).toList(), CHUNK)) {
            places.putAll(store.fillPlaces(chunk));
        }
        List<Submission> now30 = visible.stream().filter(one -> window.contains(one.submittedAt())
                && chosen.allows(places.get(one.fillId()))).toList();
        long before = visible.stream().filter(one -> window.containsPrevious(one.submittedAt())
                && chosen.allows(places.get(one.fillId()))).count();
        Tile submitted = new Tile((double) now30.size(), (double) before, null, more, true, true);

        Map<String, Long> perDay = new HashMap<>();
        now30.forEach(one -> perDay.merge(HomePeriod.dayOf(one.submittedAt()), 1L, Long::sum));
        List<DayCount> days = HomePeriod.days(window).stream().map(day -> new DayCount(day,
                perDay.getOrDefault(day, 0L))).toList();
        DaysView perDayView = new DaysView((long) now30.size(), days, more);

        Map<UUID, Long> perTemplate = new HashMap<>();
        Map<UUID, String> templateNames = new HashMap<>();
        for (Submission one : now30) {
            perTemplate.merge(one.ownerId(), 1L, Long::sum);
            templateNames.putIfAbsent(one.ownerId(), names.get(one.fillId()));
        }
        List<TemplateCount> byTemplate = perTemplate.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()
                        .thenComparing(entry -> templateNames.get(entry.getKey())))
                .limit(TOP_TEMPLATES).map(entry -> new TemplateCount(entry.getKey(),
                        templateNames.get(entry.getKey()), entry.getValue())).toList();

        List<DepartmentCount> byDepartment = byDepartment(visible, window, chosen, places);
        List<DepartmentTemplateCount> byDepartmentAndTemplate = byDepartmentAndTemplate(visible, window, chosen, places,
                names);

        Tile waiting = starter.available(Fills.OBJECT) ? waiting(chosen, max) : null;

        List<Raw> activity = visible.stream()
                .sorted(Comparator.comparing(Submission::submittedAt).reversed())
                .limit(properties.recentActivity())
                .map(one -> new Raw("SUBMITTED", one.submittedAt(), one.startedBy(), "fill-in", one.fillId(),
                        names.get(one.fillId()))).toList();
        return new FillResult(submitted, waiting, perDayView, byTemplate, byDepartment, byDepartmentAndTemplate,
                activity);
    }

    /** The fill-ins that wait for a review, as many as the caller may see and the filters let through. */
    private Tile waiting(Chosen chosen, int max) {
        List<Submission> rows = fills.awaitingReview(Fills.OBJECT, max + 1);
        boolean more = rows.size() > max;
        List<Submission> bounded = more ? rows.subList(0, max) : rows;
        Map<UUID, String> names = judge(bounded.stream().map(Submission::fillId).toList());
        Map<UUID, Set<Place>> places = new HashMap<>();
        for (List<UUID> chunk : chunks(names.keySet().stream().toList(), CHUNK)) {
            places.putAll(store.fillPlaces(chunk));
        }
        long count = bounded.stream().filter(one -> names.containsKey(one.fillId())
                && chosen.allows(places.get(one.fillId()))).count();
        return new Tile((double) count, null, null, more, true, true);
    }

    /**
     * The fill-ins of the period by department and then by template: the thirty departments with the most fill-ins,
     * and under each the thirty templates filled most there. A fill-in without a recorded department is kept under
     * "not recorded" when no filter is chosen. The names are those the fill-ins carry; the same rules of who counts as
     * in {@link #byDepartment}.
     */
    private List<DepartmentTemplateCount> byDepartmentAndTemplate(List<Submission> visible, Window window,
            Chosen chosen, Map<UUID, Set<Place>> places, Map<UUID, String> names) {
        Map<Place, Map<UUID, Long>> counts = new LinkedHashMap<>();
        Map<UUID, Long> unrecorded = new HashMap<>();
        Map<UUID, String> templateNames = new HashMap<>();
        for (Submission one : visible) {
            if (!window.contains(one.submittedAt())) {
                continue;
            }
            templateNames.putIfAbsent(one.ownerId(), names.get(one.fillId()));
            Set<Place> recorded = places.get(one.fillId());
            if (recorded == null || recorded.isEmpty()) {
                if (!chosen.any()) {
                    unrecorded.merge(one.ownerId(), 1L, Long::sum);
                }
                continue;
            }
            if (!chosen.allowsLocation(recorded)) {
                continue;
            }
            for (Place place : recorded) {
                if (chosen.location() == null || chosen.location().equals(place.location())) {
                    counts.computeIfAbsent(place, key -> new HashMap<>()).merge(one.ownerId(), 1L, Long::sum);
                }
            }
        }
        Map<String, String> locationLabels = departments.labels(Templates.LOCATION);
        Map<String, String> departmentLabels = departments.labels(Templates.DEPARTMENT);
        List<DepartmentTemplateCount> result = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Place, Map<UUID, Long>> entry) ->
                        entry.getValue().values().stream().mapToLong(Long::longValue).sum()).reversed())
                .limit(TOP_DEPARTMENTS).forEach(entry -> {
                    Place place = entry.getKey();
                    String label = locationLabels.getOrDefault(place.location(), place.location()) + " " + MIDDLE_DOT
                            + " " + departmentLabels.getOrDefault(place.department(), place.department());
                    topTemplates(entry.getValue(), templateNames).forEach(row -> result.add(
                            new DepartmentTemplateCount(place.location(), place.department(), label, row.getKey(),
                                    templateNames.get(row.getKey()), row.getValue())));
                });
        topTemplates(unrecorded, templateNames).forEach(row -> result.add(new DepartmentTemplateCount(null, null,
                NOT_RECORDED, row.getKey(), templateNames.get(row.getKey()), row.getValue())));
        return result;
    }

    private static List<Map.Entry<UUID, Long>> topTemplates(Map<UUID, Long> counts, Map<UUID, String> names) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()
                        .thenComparing(entry -> String.valueOf(names.get(entry.getKey()))))
                .limit(TOP_TEMPLATES_PER_DEPARTMENT).toList();
    }

    private List<DepartmentCount> byDepartment(List<Submission> visible, Window window, Chosen chosen,
            Map<UUID, Set<Place>> places) {
        Map<Place, Long> counts = new LinkedHashMap<>();
        long unrecorded = 0;
        for (Submission one : visible) {
            if (!window.contains(one.submittedAt())) {
                continue;
            }
            Set<Place> recorded = places.get(one.fillId());
            if (recorded == null || recorded.isEmpty()) {
                if (!chosen.any()) {
                    unrecorded++;
                }
                continue;
            }
            if (!chosen.allowsLocation(recorded)) {
                continue;
            }
            for (Place place : recorded) {
                if (chosen.location() == null || chosen.location().equals(place.location())) {
                    counts.merge(place, 1L, Long::sum);
                }
            }
        }
        Map<String, String> locationLabels = departments.labels(Templates.LOCATION);
        Map<String, String> departmentLabels = departments.labels(Templates.DEPARTMENT);
        List<DepartmentCount> result = new ArrayList<>();
        counts.entrySet().stream().sorted(Map.Entry.<Place, Long>comparingByValue().reversed())
                .forEach(entry -> result.add(new DepartmentCount(entry.getKey().location(),
                        entry.getKey().department(),
                        locationLabels.getOrDefault(entry.getKey().location(), entry.getKey().location())
                                + " " + MIDDLE_DOT + " " + departmentLabels.getOrDefault(entry.getKey().department(),
                                        entry.getKey().department()), entry.getValue())));
        if (unrecorded > 0) {
            result.add(new DepartmentCount(null, null, NOT_RECORDED, unrecorded));
        }
        return result;
    }

    /**
     * The fill-ins among these identifiers that the record store lets the caller see, with their names. This is where
     * "a number never includes a record the person may not read" is kept: the same query, the same stages.
     */
    private Map<UUID, String> judge(Collection<UUID> ids) {
        Map<UUID, String> visible = new HashMap<>();
        for (List<UUID> chunk : chunks(List.copyOf(ids), JUDGE)) {
            QueryFilter filter = QueryFilter.on("id", QueryFilter.Op.IN,
                    chunk.stream().map(UUID::toString).toArray());
            QueryResult page = records.query(Fills.OBJECT,
                    new RecordQuery(filter, List.of(), List.of(Fills.NAME), List.of(), JUDGE, null));
            page.records().forEach(one -> visible.put(one.record().id(), text(one.record(), Fills.NAME)));
        }
        return visible;
    }

    // ---- the activity list and the names ----

    private List<Activity> activity(TemplateResult templates, FillResult fillResult) {
        List<Raw> all = new ArrayList<>();
        if (templates != null) {
            all.addAll(templates.activity());
        }
        if (fillResult != null) {
            all.addAll(fillResult.activity());
        }
        List<Raw> latest = all.stream().sorted(Comparator.comparing(Raw::at).reversed())
                .limit(properties.recentActivity()).toList();
        Map<UUID, String> who = names(latest.stream().map(Raw::by).filter(by -> by != null)
                .collect(java.util.stream.Collectors.toSet()));
        return latest.stream().map(one -> new Activity(one.kind(), one.at(), one.by() == null ? null
                : who.get(one.by()), one.about(), one.id(), one.name())).toList();
    }

    /** The display names of active members, through the contract the owner of users implements. */
    private Map<UUID, String> names(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return labels.stream().filter(one -> "User".equals(one.object())).findFirst()
                .map(one -> one.labels(ids)).orElse(Map.of());
    }

    // ---- small helpers ----

    private boolean reads(Caller caller, String object) {
        return metadata.object(object).isPresent()
                && decisions.can(caller.membershipId(), object, ObjectAction.READ).allowed();
    }

    /** Works one section out; a section that fails is named, not explained, and the others still answer. */
    private <T> T attempt(String section, List<String> problems, Supplier<T> work) {
        try {
            return savepoint.execute(status -> work.get());
        } catch (ApiException refused) {
            // The record store answers "not found" for an object the caller cannot read: the section is not drawn.
            LOG.debug("Home section {} refused ({})", section, refused.getClass().getSimpleName());
            return null;
        } catch (RuntimeException failed) {
            LOG.warn("Home section {} could not be worked out ({})", section, failed.getClass().getSimpleName());
            problems.add(section);
            return null;
        }
    }

    private static <T> List<List<T>> chunks(List<T> items, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int at = 0; at < items.size(); at += size) {
            parts.add(items.subList(at, Math.min(items.size(), at + size)));
        }
        return parts;
    }

    private static String text(DataRecord record, String field) {
        return record.fields().get(field) instanceof String value ? value : "";
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
