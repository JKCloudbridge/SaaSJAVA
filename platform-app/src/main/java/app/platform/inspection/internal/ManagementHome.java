package app.platform.inspection.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryResult;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.inspection.internal.ManagementHomeViews.FormOption;
import app.platform.inspection.internal.ManagementHomeViews.LegendView;
import app.platform.inspection.internal.ManagementHomeViews.ManagementAppliedView;
import app.platform.inspection.internal.ManagementHomeViews.ManagementChartsView;
import app.platform.inspection.internal.ManagementHomeViews.ManagementFiltersView;
import app.platform.inspection.internal.ManagementHomeViews.ManagementSummaryRequest;
import app.platform.inspection.internal.ManagementHomeViews.ManagementSummaryView;
import app.platform.inspection.internal.ManagementHomeViews.ManagementTile;
import app.platform.inspection.internal.ManagementHomeViews.ManagementTilesView;
import app.platform.inspection.internal.ManagementHomeViews.PlantOption;
import app.platform.inspection.internal.ManagementNumbers.Bands;
import app.platform.inspection.internal.ManagementNumbers.Row;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.metadata.Metadata;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.approval.ApprovalDecisions;
import app.platform.sharedkernel.approval.ApprovalDecisions.PendingStep;
import app.platform.sharedkernel.approval.ApprovalStarter;
import app.platform.sharedkernel.solution.ApplicationSettings;
import app.platformapi.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
 * The Home page that compares the plants month by month (MC3, ADR-0262): how each plant did in a month, how it moved
 * over the months, who needs an Action plan and what waits for a decision.
 *
 * <p><strong>The counting rule is that of the Quality Management System page</strong> (ADR-0203): there is no rule of
 * this class about who may see a record. Every number is made over header facts the record store itself returned to
 * this caller, through the set's own narrowing, so the page agrees with the lists it opens and a plant head sees one
 * plant. The record store gives no total, so the headers are read page by page, grouped in memory and bounded by
 * {@code platform.inspection.home.max-scanned}; a number that reached the bound says "at least". Nothing is cached
 * across people. A score is read from the header, never calculated again.
 *
 * <p><strong>What a person is not told.</strong> The plants a number is "of" are the plants this person may see: all
 * the plants a document is published to for a person who may view every fill-in, else the plants they head and the
 * plants of the fill-ins they read; otherwise "1 of 2" would say what another plant did. A section whose object the
 * caller cannot read is null, never a zero; a section that failed is named in {@code problems} and the rest still
 * answers. Months are months in UTC. Nothing typed, no score, limit, plant, month or filter value reaches a log or a
 * metric; the metric carries the result and the duration only.
 */
@Service
@Transactional(readOnly = true)
class ManagementHome {

    private static final Logger LOG = LoggerFactory.getLogger(ManagementHome.class);
    private static final int DEFAULT_SPAN = 6;
    private static final Set<Integer> SPANS = Set.of(3, 6, 12);
    private static final int MONTHS_BACK = 60;
    private static final int CHOICES = 24;
    private static final int PAGE = 200;
    private static final int CHUNK = 500;
    private static final String NOT_FOUND = "This page does not exist.";
    private static final String NOT_RECORDED = "Not recorded";
    private static final String LIMIT = "scoreLimit";
    private static final String EXCELLENT = "excellentAbove";
    private static final String GOOD = "goodAbove";
    private static final List<String> HEADER_FIELDS = List.of(Fills.TEMPLATE_ID, Fills.NAME, Fills.PLANT,
            Fills.PERIOD, Fills.SCORE, Fills.RESULT, Fills.STATUS, Fills.PARENT_FILL);

    private final TemplateAccess access;
    private final Metadata metadata;
    private final Decisions decisions;
    private final Records records;
    private final DepartmentAudiences departments;
    private final PlantPublicationStore plants;
    private final ApprovalStarter starter;
    private final ApprovalDecisions approvals;
    private final ApplicationSettings settings;
    private final MeterRegistry meters;
    private final QualityHomeProperties properties;
    private final Clock clock;
    private final TransactionTemplate savepoint;

    ManagementHome(TemplateAccess access, Metadata metadata, Decisions decisions, Records records,
            DepartmentAudiences departments, PlantPublicationStore plants, ApprovalStarter starter,
            ApprovalDecisions approvals, ApplicationSettings settings, MeterRegistry meters,
            QualityHomeProperties properties, Clock clock, PlatformTransactionManager transactions) {
        this.savepoint = new TransactionTemplate(transactions);
        // A section that fails is rolled back to here and the others go on: a refusal that passes through the record
        // store must not leave the whole request rollback-only.
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.access = access;
        this.metadata = metadata;
        this.decisions = decisions;
        this.records = records;
        this.departments = departments;
        this.plants = plants;
        this.starter = starter;
        this.approvals = approvals;
        this.settings = settings;
        this.meters = meters;
        this.properties = properties;
        this.clock = clock;
    }

    /** What a scan of header facts found, and whether more existed than one request may read. */
    private record Scan(List<Row> rows, boolean more) {
    }

    /** What the filters chose, once checked against what the caller may see. */
    private record Chosen(String plant, UUID form) {

        boolean plantAllows(Row row) {
            return plant == null || plant.equals(row.plant());
        }

        boolean allows(Row row) {
            return plantAllows(row) && (form == null || form.toString().equals(row.templateId()));
        }
    }

    /**
     * Works the page out for the caller.
     *
     * @throws ApiException {@code NOT_FOUND} on the platform host, for an application that has no such page and for an
     *         organization without the plan feature, {@code VALIDATION_ERROR} for a month, span, plant or form that is
     *         not offered
     */
    ManagementSummaryView summary(RecordSet set, ManagementSummaryRequest request) {
        long started = System.nanoTime();
        String result = "refused";
        try {
            ManagementSummaryView view = work(set, request);
            result = view.problems().isEmpty() ? "ok" : "partial";
            return view;
        } finally {
            meters.timer("platform.inspection.management.home", "result", result)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private ManagementSummaryView work(RecordSet set, ManagementSummaryRequest request) {
        if (!set.managementDashboard() || metadata.object(set.fill()).isEmpty()) {
            throw ApiException.notFound(NOT_FOUND);
        }
        Caller caller = access.member();
        Instant now = clock.instant();
        YearMonth current = YearMonth.from(now.atZone(ZoneOffset.UTC));
        YearMonth focus = focus(request == null ? null : request.month(), current);
        int span = span(request == null ? null : request.span());
        List<String> months = ManagementNumbers.monthsEnding(focus, span);
        String previous = focus.minusMonths(1).toString();
        Bands bands = bands(set);
        List<String> problems = new ArrayList<>();
        boolean readsFills = reads(caller, set.fill());

        Scan monthScan = readsFills ? attempt("fill-ins", problems, () -> scanMonths(set, months)) : null;
        Scan planScan = readsFills ? attempt("action-plans", problems, () -> scanPlans(set)) : null;
        boolean processes = starter.available(set.fill());
        Scan waited = readsFills && processes ? attempt("waiting", problems, () -> scanWaiting(set)) : null;
        Map<UUID, PendingStep> steps = waited == null ? null
                : attempt("waiting", problems, () -> pendingSteps(set, waited.rows()));
        // Without the step of each request the two waiting tiles cannot be told apart, so neither is drawn.
        Scan waitScan = steps == null ? null : waited;

        Map<String, String> labels = departments.labels(Templates.LOCATION);
        Set<String> seen = new HashSet<>();
        for (Scan scan : new Scan[] {monthScan, planScan, waitScan}) {
            if (scan != null) {
                scan.rows().stream().filter(row -> row.plant() != null).forEach(row -> seen.add(row.plant()));
            }
        }
        boolean[] plantsMore = new boolean[1];
        Set<String> visible = readsFills ? attempt("plants", problems, () -> visiblePlants(set, caller, seen,
                plantsMore)) : null;
        if (visible == null) {
            visible = seen;
        }
        Chosen chosen = chosen(request, visible, monthScan);

        Map<String, String> plantLabels = new LinkedHashMap<>();
        visible.stream().sorted(Comparator.comparing((String code) -> labels.getOrDefault(code, code)))
                .forEach(code -> plantLabels.put(code, labels.getOrDefault(code, code)));

        List<Row> monthRows = monthScan == null ? List.of() : monthScan.rows().stream().filter(chosen::allows)
                .toList();
        List<Row> planRows = planScan == null ? List.of() : planScan.rows().stream().filter(chosen::plantAllows)
                .toList();
        List<Row> waitRows = waitScan == null ? List.of() : waitScan.rows().stream().filter(chosen::plantAllows)
                .toList();
        Set<UUID> answered = ManagementNumbers.answered(planScan == null ? List.of() : planScan.rows());
        boolean monthMore = monthScan != null && monthScan.more();
        boolean planMore = planScan != null && planScan.more();
        boolean waitMore = waitScan != null && waitScan.more();

        ManagementTilesView tiles = new ManagementTilesView(
                monthScan == null ? null : submittedTile(monthRows, focus.toString(), previous,
                        plantsFor(chosen, plantLabels), monthMore || plantsMore[0]),
                monthScan == null ? null : averageTile(monthRows, focus.toString(), previous, monthMore),
                monthScan == null ? null : belowTile(monthRows, focus.toString(), previous, answered, monthMore),
                waitScan == null ? null : waitingTile(waitRows, steps, false, waitMore),
                waitScan == null ? null : waitingTile(waitRows, steps, true, waitMore),
                planScan == null ? null : new ManagementTile((double) ManagementNumbers.open(planRows), null, null,
                        null, planMore));
        ManagementChartsView charts = new ManagementChartsView(
                monthScan == null ? null : ManagementNumbers.scoresByPlant(monthRows, focus.toString(),
                        plantsFor(chosen, plantLabels), bands),
                monthScan == null ? null : ManagementNumbers.plantsOverMonths(monthRows, months, labels),
                monthScan == null ? null : ManagementNumbers.averageByMonth(monthRows, months),
                planScan == null ? null : ManagementNumbers.plansByPlant(planRows, labels, NOT_RECORDED),
                monthScan == null ? null : ManagementNumbers.fillsByMonth(monthRows, months));
        List<PlantOption> plantOptions = plantLabels.entrySet().stream()
                .map(entry -> new PlantOption(entry.getKey(), entry.getValue())).toList();
        List<FormOption> forms = forms(monthScan);
        List<String> choices = new ArrayList<>(ManagementNumbers.monthsEnding(current, CHOICES));
        java.util.Collections.reverse(choices);
        return new ManagementSummaryView(now, focus.toString(), previous, months, choices,
                new ManagementFiltersView(plantOptions, forms),
                new ManagementAppliedView(chosen.plant(), chosen.form()),
                new LegendView(bands.limit(), bands.excellentAbove(), bands.goodAbove()), tiles, charts, problems);
    }

    // ---- the filters ----

    private static YearMonth focus(String text, YearMonth current) {
        if (text == null || text.isBlank() || "this".equals(text.trim())) {
            return current;
        }
        if ("last".equals(text.trim())) {
            return current.minusMonths(1);
        }
        try {
            YearMonth month = YearMonth.parse(text.trim());
            if (month.isAfter(current) || month.isBefore(current.minusMonths(MONTHS_BACK))) {
                throw ApiException.validation("month", "Choose a month that is not in the future.");
            }
            return month;
        } catch (DateTimeException notAMonth) {
            throw ApiException.validation("month", "Choose this month, last month or a month as year-month.");
        }
    }

    private static int span(Integer asked) {
        if (asked == null) {
            return DEFAULT_SPAN;
        }
        if (!SPANS.contains(asked)) {
            throw ApiException.validation("span", "Choose 3, 6 or 12 months.");
        }
        return asked;
    }

    private Chosen chosen(ManagementSummaryRequest request, Set<String> visible, Scan monthScan) {
        String plant = request == null || request.plant() == null || request.plant().isBlank() ? null
                : request.plant().trim();
        if (plant != null && !visible.contains(plant)) {
            throw ApiException.validation("plant", "Choose a plant from the list.");
        }
        UUID form = request == null ? null : request.form();
        if (form != null && (monthScan == null || monthScan.rows().stream()
                .noneMatch(row -> !row.isPlan() && form.toString().equals(row.templateId())))) {
            throw ApiException.validation("form", "Choose a form from the list.");
        }
        return new Chosen(plant, form);
    }

    /** The plants a number is "of": all the plants the caller may see, or the one the filter chose. */
    private static Map<String, String> plantsFor(Chosen chosen, Map<String, String> labels) {
        if (chosen.plant() == null) {
            return labels;
        }
        Map<String, String> one = new LinkedHashMap<>();
        one.put(chosen.plant(), labels.getOrDefault(chosen.plant(), chosen.plant()));
        return one;
    }

    private static List<FormOption> forms(Scan monthScan) {
        if (monthScan == null) {
            return List.of();
        }
        Map<UUID, String> names = new TreeMap<>();
        for (Row row : monthScan.rows()) {
            UUID id = Fills.templateOf(row.templateId());
            if (!row.isPlan() && id != null) {
                names.putIfAbsent(id, row.name() == null ? "" : row.name());
            }
        }
        return names.entrySet().stream().map(entry -> new FormOption(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(FormOption::name).thenComparing(FormOption::id)).toList();
    }

    // ---- the tiles ----

    private static ManagementTile submittedTile(List<Row> rows, String month, String previous,
            Map<String, String> whole, boolean more) {
        double now = ManagementNumbers.plantsSubmitted(rows, month).size();
        double before = ManagementNumbers.plantsSubmitted(rows, previous).size();
        return new ManagementTile(now, before, (double) whole.size(), null, more);
    }

    private static ManagementTile averageTile(List<Row> rows, String month, String previous, boolean more) {
        return new ManagementTile(ManagementNumbers.averageOfPlants(rows, month),
                ManagementNumbers.averageOfPlants(rows, previous), null, null, more);
    }

    private static ManagementTile belowTile(List<Row> rows, String month, String previous, Set<UUID> answered,
            boolean more) {
        List<Row> needing = ManagementNumbers.needingPlan(rows, month);
        double plants = needing.stream().map(Row::plant).distinct().count();
        double before = ManagementNumbers.needingPlan(rows, previous).stream().map(Row::plant).distinct().count();
        return new ManagementTile(plants, before, null, (double) ManagementNumbers.withoutPlan(needing, answered),
                more);
    }

    /** The fill-ins that wait at the last step ({@code top}) or before it; one with no pending request is not one. */
    private static ManagementTile waitingTile(List<Row> rows, Map<UUID, PendingStep> steps, boolean top, boolean more) {
        long count = rows.stream().filter(row -> {
            PendingStep step = steps.get(row.id());
            return step != null && step.last() == top;
        }).count();
        return new ManagementTile((double) count, null, null, null, more);
    }

    // ---- reading the headers ----

    /** The fill-ins (and Action plans) of the months, as the record store lists them for this caller. */
    private Scan scanMonths(RecordSet set, List<String> months) {
        QueryFilter filter = QueryFilter.on(Fills.PERIOD, QueryFilter.Op.IN, months.toArray());
        return scan(set, filter);
    }

    /** Every Action plan the caller may see, in every month. */
    private Scan scanPlans(RecordSet set) {
        return scan(set, QueryFilter.on(Fills.PARENT_FILL, QueryFilter.Op.IS_NOT_EMPTY));
    }

    /** Every fill-in that waits for a decision, in every month. */
    private Scan scanWaiting(RecordSet set) {
        return scan(set, QueryFilter.on(Fills.STATUS, QueryFilter.Op.EQ, "SUBMITTED"));
    }

    private Scan scan(RecordSet set, QueryFilter filter) {
        List<Row> rows = new ArrayList<>();
        boolean more = false;
        String cursor = null;
        List<String> fields = new ArrayList<>(HEADER_FIELDS);
        while (true) {
            QueryResult page = records.query(set.fill(), new RecordQuery(set.narrow(filter), List.of(), fields,
                    List.of(), PAGE, cursor));
            for (QueriedRecord one : page.records()) {
                if (rows.size() >= properties.maxScanned()) {
                    more = true;
                    break;
                }
                rows.add(row(one.record()));
            }
            cursor = page.nextCursor();
            if (more || cursor == null) {
                break;
            }
        }
        return new Scan(rows, more);
    }

    private static Row row(DataRecord record) {
        Map<String, Object> fields = record.fields();
        return new Row(record.id(), text(fields.get(Fills.TEMPLATE_ID)), text(fields.get(Fills.NAME)),
                blankToNull(text(fields.get(Fills.PLANT))), blankToNull(text(fields.get(Fills.PERIOD))),
                number(fields.get(Fills.SCORE)), blankToNull(text(fields.get(Fills.RESULT))),
                text(fields.get(Fills.STATUS)), Fills.templateOf(fields.get(Fills.PARENT_FILL)));
    }

    private Map<UUID, PendingStep> pendingSteps(RecordSet set, List<Row> rows) {
        Map<UUID, PendingStep> found = new LinkedHashMap<>();
        List<UUID> ids = rows.stream().map(Row::id).toList();
        for (int at = 0; at < ids.size(); at += CHUNK) {
            found.putAll(approvals.pendingSteps(set.fill(), ids.subList(at, Math.min(ids.size(), at + CHUNK))));
        }
        return found;
    }

    /**
     * The plants this caller may see (see the class note): the plants of the documents they may read that are
     * published, all of them for a person who may view every fill-in, else those they head; and the plants of the
     * fill-ins they read, whatever.
     */
    private Set<String> visiblePlants(RecordSet set, Caller caller, Set<String> seen, boolean[] more) {
        Set<String> visible = new HashSet<>(seen);
        if (!reads(caller, set.template())) {
            return visible;
        }
        List<UUID> templates = new ArrayList<>();
        String cursor = null;
        while (true) {
            QueryResult page = records.query(set.template(), new RecordQuery(set.narrow(null), List.of(), List.of(),
                    List.of(), PAGE, cursor));
            for (QueriedRecord one : page.records()) {
                if (templates.size() >= properties.maxScanned()) {
                    more[0] = true;
                    break;
                }
                templates.add(one.record().id());
            }
            cursor = page.nextCursor();
            if (more[0] || cursor == null) {
                break;
            }
        }
        Set<String> published = new HashSet<>();
        for (int at = 0; at < templates.size(); at += CHUNK) {
            published.addAll(plants.livePlantCodes(templates.subList(at, Math.min(templates.size(), at + CHUNK))));
        }
        if (decisions.can(caller.membershipId(), set.fill(), ObjectAction.VIEW_ALL).allowed()) {
            visible.addAll(published);
        } else {
            String department = set.placeDepartment();
            departments.placesOf(caller.membershipId()).stream()
                    .filter(place -> place.department().equals(department)).map(QualityHomeStore.Place::location)
                    .filter(published::contains).forEach(visible::add);
        }
        return visible;
    }

    // ---- the numbers in force ----

    private Bands bands(RecordSet set) {
        return new Bands(setting(set, LIMIT), setting(set, EXCELLENT), setting(set, GOOD));
    }

    private Integer setting(RecordSet set, String key) {
        java.util.OptionalInt value = settings.value(set.application(), key);
        return value.isPresent() ? value.getAsInt() : null;
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
            LOG.debug("Management home section {} refused ({})", section, refused.getClass().getSimpleName());
            return null;
        } catch (RuntimeException failed) {
            LOG.warn("Management home section {} could not be worked out ({})", section,
                    failed.getClass().getSimpleName());
            if (!problems.contains(section)) {
                problems.add(section);
            }
            return null;
        }
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static BigDecimal number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
