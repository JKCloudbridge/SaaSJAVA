package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.LinkedRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryPlan;
import app.platform.data.QueryPlan.After;
import app.platform.data.QueryResult;
import app.platform.data.RecordQuery;
import app.platform.data.RecordSlice;
import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Runs a query and the list through the same stages, in this order (Sprint 16, ADR-0100, {@link
 * QueryAnalyzer#STAGES}): the <strong>shape</strong>, the <strong>fields</strong> (metadata and field permissions
 * together) and the <strong>operators and values</strong> of the request; <strong>record security</strong> (the seam,
 * {@link RecordVisibility}); the <strong>plan</strong> (where each value is read from, whether the query reads every
 * record); the parameterized <strong>statement</strong> built by the storage; and the <strong>execution</strong>. The
 * answer leaves out every field the member may not read, with the same filter as a single read.
 *
 * <p>A query that would read every record of a large object is refused with the fields to flag as indexed (the
 * decision of ADR-0105: an object with at most {@code unindexed-scan-limit} live records may be scanned, a larger one
 * may not), so a missing index shows up as an answer a person can act on and never as a slow request; the statement
 * time limit of the organization (ADR-0108) is the second line of defence.
 *
 * <p>No total is counted (it would be a scan): a page says whether another follows.
 */
@Component
class QueryService {

    private final QueryAnalyzer analyzer;
    private final QueryPlanner planner;
    private final QueryCursor cursors;
    private final RecordVisibility visibility;
    private final IndexPolicy policy;
    private final StorageStrategy storage;
    private final LookupResolver resolver;
    private final FormulaCurrency currency;
    private final RecordProperties.Query limits;

    QueryService(QueryAnalyzer analyzer, QueryPlanner planner, QueryCursor cursors, RecordVisibility visibility,
            IndexPolicy policy, StorageStrategy storage, LookupResolver resolver, FormulaCurrency currency,
            RecordProperties properties) {
        this.analyzer = analyzer;
        this.planner = planner;
        this.cursors = cursors;
        this.visibility = visibility;
        this.policy = policy;
        this.storage = storage;
        this.resolver = resolver;
        this.currency = currency;
        this.limits = properties.query();
    }

    /** Runs a query for a member who passed the gate. */
    QueryResult run(RecordGate.Passed passed, ObjectAccess access, RecordQuery query) {
        QueryAnalyzer.Analyzed analyzed = analyzer.analyze(passed.object(), access, query,
                () -> currency.isCurrent(passed.object()));
        List<QueryPlan.Node> constraint = visibility.constraintFor(passed, access).stream().toList();
        After after = analyzed.cursor() == null ? null
                : cursors.decode(analyzed.cursor(), analyzed.signature(), analyzed.keys().size());
        List<StorageStrategy.StoredRow> rows = fetch(passed.object(), analyzed, constraint, after);

        boolean more = rows.size() > analyzed.limit();
        List<StorageStrategy.StoredRow> page = more ? rows.subList(0, analyzed.limit()) : rows;
        String next = null;
        if (more) {
            StorageStrategy.StoredRow last = page.get(page.size() - 1);
            next = cursors.encode(analyzed.signature(), last.sortValues(), last.record().id());
        }
        List<DataRecord> visible = page.stream().map(row -> FieldSecurity.visible(row.record(), access)).toList();
        List<Map<String, LinkedRecord>> linked = analyzed.follows().isEmpty()
                ? List.of() : resolver.resolve(analyzed.follows(), visible, passed.membershipId());
        List<QueriedRecord> records = new ArrayList<>();
        for (int index = 0; index < visible.size(); index++) {
            records.add(new QueriedRecord(project(visible.get(index), analyzed.projection()),
                    linked.isEmpty() ? Map.of() : linked.get(index)));
        }
        return new QueryResult(records, next, analyzed.limit());
    }

    /**
     * The list of Sprint 14 (every live record, in identifier order, after an identifier): the same stages, with no
     * filter and no order. A thin use of the code above.
     */
    RecordSlice list(RecordGate.Passed passed, ObjectAccess access, UUID afterId, int limit) {
        QueryAnalyzer.Analyzed analyzed = analyzer.analyze(passed.object(), access, RecordQuery.everything(0, null));
        List<QueryPlan.Node> constraint = visibility.constraintFor(passed, access).stream().toList();
        QueryAnalyzer.Analyzed sized = new QueryAnalyzer.Analyzed(analyzed.object(), analyzed.where(),
                analyzed.keys(), analyzed.projection(), analyzed.follows(), limit, null, analyzed.signature());
        List<StorageStrategy.StoredRow> rows = fetch(passed.object(), sized, constraint,
                afterId == null ? null : new After(List.of(), afterId));
        boolean more = rows.size() > limit;
        List<DataRecord> page = (more ? rows.subList(0, limit) : rows).stream()
                .map(row -> FieldSecurity.visible(row.record(), access)).toList();
        return new RecordSlice(page, more);
    }

    /**
     * One page of the live records of the object after an identifier, for the platform's own background work (the
     * recalculation of stored formula results, ADR-0113): the same stages and the same statement as a member's query,
     * with a member who may read every field and no record security, and without the refusal of a scan of a large
     * object (the work is bounded by its batch, not by what a person's request may cost). Never reachable from a
     * request.
     *
     * @param filter a condition, or null for every record
     * @param afterId the identifier the page continues after, or null for the first page
     * @param limit the page size
     */
    List<DataRecord> scan(ObjectDefinition object, QueryFilter filter, UUID afterId, int limit) {
        ObjectAccess everything = new ObjectAccess(object.apiName(), Set.of(), object.fields().stream()
                .map(FieldDefinition::apiName).collect(Collectors.toSet()), Set.of());
        QueryAnalyzer.Analyzed analyzed = analyzer.analyze(object, everything,
                new RecordQuery(filter, List.of(), null, List.of(), 0, null));
        QueryAnalyzer.Analyzed sized = new QueryAnalyzer.Analyzed(analyzed.object(), analyzed.where(),
                analyzed.keys(), null, List.of(), limit, null, analyzed.signature());
        QueryPlanner.Planned planned = planner.plan(sized, policy.completeSlots(object.apiName()), null,
                afterId == null ? null : new After(List.of(), afterId), limit);
        return storage.query(planned.plan()).stream().map(StorageStrategy.StoredRow::record).toList();
    }

    private List<StorageStrategy.StoredRow> fetch(ObjectDefinition object, QueryAnalyzer.Analyzed analyzed,
            List<QueryPlan.Node> constraint, After after) {
        Map<String, SlotColumn> complete = policy.completeSlots(object.apiName());
        QueryPlan.Node extra = constraint.isEmpty() ? null : constraint.get(0);
        QueryPlanner.Planned planned = planner.plan(analyzed, complete, extra, after, analyzed.limit() + 1);
        if (planned.scan()) {
            refuseIfTooLarge(object, planned);
        }
        return storage.query(planned.plan());
    }

    private void refuseIfTooLarge(ObjectDefinition object, QueryPlanner.Planned planned) {
        int allowed = limits.unindexedScanLimit();
        if (storage.liveCountUpTo(object.apiName(), allowed + 1) <= allowed) {
            return;
        }
        throw ApiException.validation("query", refusal(object, planned.blame()));
    }

    /** The words that tell a person what to do: which fields to flag, what to wait for, what to add. */
    private static String refusal(ObjectDefinition object, List<QueryPlanner.Blame> blame) {
        List<String> advice = new ArrayList<>();
        for (QueryPlanner.Blame one : blame) {
            FieldDefinition field = object.field(one.field()).orElse(null);
            if (field == null) {
                continue;
            }
            if (one.inherent()) {
                advice.add(one.field() + " cannot use an index in this comparison; add a condition on an indexed "
                        + "field to narrow the records first");
            } else if (one.flaggable() && field.indexed()) {
                advice.add(one.field() + " is flagged as indexed but its index is not complete yet; try again in a "
                        + "few minutes");
            } else if (one.flaggable()) {
                advice.add("ask an administrator to flag " + one.field() + " as indexed");
            } else {
                advice.add(one.field() + " cannot be indexed; add a condition on an indexed field");
            }
        }
        String text = advice.isEmpty() ? "add a condition on an indexed field" : String.join("; ", advice);
        return "This object has too many records to read all of them for this question. "
                + Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
    }

    /** The record with only the fields asked for (it already holds only the fields the member may read). */
    private static DataRecord project(DataRecord visible, Set<String> projection) {
        if (projection == null) {
            return visible;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        visible.fields().forEach((name, value) -> {
            if (projection.contains(name)) {
                kept.put(name, value);
            }
        });
        return visible.withFields(kept);
    }

}
