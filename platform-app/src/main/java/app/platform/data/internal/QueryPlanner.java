package app.platform.data.internal;

import app.platform.data.QueryPlan;
import app.platform.data.QueryPlan.After;
import app.platform.data.QueryPlan.Column;
import app.platform.data.QueryPlan.Compare;
import app.platform.data.QueryPlan.Group;
import app.platform.data.QueryPlan.Payload;
import app.platform.data.QueryPlan.Slot;
import app.platform.data.QueryPlan.SortKey;
import app.platform.data.QueryPlan.Source;
import app.platform.data.QueryPlan.SystemColumn;
import app.platform.data.QueryPlan.Test;
import app.platform.metadata.SlotClass;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The plan stage of a query (Sprint 16, ADR-0100, ADR-0105): decides, for each field, where its value is read from
 * (a complete slot with its index, a column of the record, or the record's values) and says whether the query as a
 * whole can be answered without reading every record of the object.
 *
 * <p>The rule is the same for the filter and for the order, and it is conservative on purpose: a filter is
 * <em>indexed</em> when it has a condition that can use an index in every branch that could match alone (any one
 * condition of an AND, every condition of an OR); an order is indexed when its keys are in complete slots or are the
 * identifier or the creation time. A field counts as indexed only when its slot is complete: flagging it is intent, a
 * partly filled copy would give wrong answers. Anything else is a <em>scan</em>, which is allowed only on an object
 * small enough (the query service asks) and otherwise refused with the fields to flag.
 *
 * <p>The condition that record-level security demands (Sprint 17) is added here, to the filter, so that the list, the
 * query and (later) the single read are all judged by the same stage.
 */
@Component
class QueryPlanner {

    private static final Set<Compare> INDEXABLE = EnumSet.of(Compare.EQ, Compare.GT, Compare.GTE, Compare.LT,
            Compare.LTE, Compare.BETWEEN, Compare.IN, Compare.IS_NOT_EMPTY);

    /**
     * Why a query reads every record.
     *
     * @param field the API name of the field
     * @param flaggable whether flagging the field as indexed would help (it is read from the record's values and its
     *        kind can be indexed)
     * @param inherent whether the comparison can never use an index (a text search, "not equal", "is empty"), so that
     *        another, indexed condition is what the query needs
     */
    record Blame(String field, boolean flaggable, boolean inherent) {
    }

    /**
     * A plan with what the service needs to know about it.
     *
     * @param plan the plan for the storage
     * @param scan whether the query reads every record of the object
     * @param blame the fields that make it a scan
     */
    record Planned(QueryPlan plan, boolean scan, List<Blame> blame) {
    }

    /**
     * Plans a checked query.
     *
     * @param analyzed the query after the first three stages
     * @param complete the fields whose slot is complete, with the slot
     * @param constraint what record-level security adds, or null
     * @param after where the previous page ended, or null
     * @param fetch how many records to ask the storage for
     */
    Planned plan(QueryAnalyzer.Analyzed analyzed, Map<String, SlotColumn> complete, QueryPlan.Node constraint,
            After after, int fetch) {
        QueryPlan.Node filter = analyzed.where() == null ? null : node(analyzed.where(), complete);
        List<SortKey> order = analyzed.keys().stream()
                .map(key -> new SortKey(source(key.ref(), complete), key.descending())).toList();
        Map<String, Blame> blame = new LinkedHashMap<>();
        boolean filterIndexed = filter == null || indexed(filter);
        if (!filterIndexed) {
            collect(analyzed.where(), filter, analyzed, blame);
        }
        boolean orderIndexed = true;
        for (int index = 0; index < order.size(); index++) {
            if (!indexedOrder(order.get(index).source())) {
                orderIndexed = false;
                QueryAnalyzer.Ref ref = analyzed.keys().get(index).ref();
                blame.merge(ref.name(), blameOf(ref, false), QueryPlanner::merge);
            }
        }
        QueryPlan.Node where = filter;
        if (constraint != null) {
            where = filter == null ? constraint : new Group(true, List.of(filter, constraint));
        }
        QueryPlan plan = new QueryPlan(analyzed.object().apiName(), where, order, after, fetch);
        return new Planned(plan, !filterIndexed || !orderIndexed, List.copyOf(blame.values()));
    }

    private QueryPlan.Node node(QueryAnalyzer.Node node, Map<String, SlotColumn> complete) {
        return switch (node) {
            case QueryAnalyzer.Group group -> new Group(group.all(),
                    group.items().stream().map(item -> node(item, complete)).toList());
            case QueryAnalyzer.Cond cond -> new Test(source(cond.ref(), complete), cond.compare(), cond.values());
        };
    }

    private static Source source(QueryAnalyzer.Ref ref, Map<String, SlotColumn> complete) {
        if (ref.system() != null) {
            return new Column(ref.system());
        }
        SlotColumn slot = complete.get(ref.name());
        return slot != null ? new Slot(slot.column(), ref.name(), ref.kind()) : new Payload(ref.name(), ref.kind());
    }

    // ---- is it indexed? ----

    private static boolean indexed(QueryPlan.Node node) {
        return switch (node) {
            case Group group -> group.all() ? group.items().stream().anyMatch(QueryPlanner::indexed)
                    : group.items().stream().allMatch(QueryPlanner::indexed);
            case Test test -> usesIndex(test.source(), test.compare());
        };
    }

    private static boolean usesIndex(Source source, Compare compare) {
        if (!INDEXABLE.contains(compare)) {
            return false;
        }
        return switch (source) {
            case Slot slot -> true;
            case Column column -> column.column() == SystemColumn.ID || column.column() == SystemColumn.CREATED_AT;
            case Payload payload -> false;
        };
    }

    private static boolean indexedOrder(Source source) {
        return switch (source) {
            case Slot slot -> true;
            case Column column -> column.column() == SystemColumn.ID || column.column() == SystemColumn.CREATED_AT;
            case Payload payload -> false;
        };
    }

    /** The conditions that stop the filter from being indexed, by field. */
    private void collect(QueryAnalyzer.Node checked, QueryPlan.Node planned, QueryAnalyzer.Analyzed analyzed,
            Map<String, Blame> blame) {
        switch (checked) {
            case QueryAnalyzer.Group group -> {
                Group planGroup = (Group) planned;
                for (int index = 0; index < group.items().size(); index++) {
                    collect(group.items().get(index), planGroup.items().get(index), analyzed, blame);
                }
            }
            case QueryAnalyzer.Cond cond -> {
                Test test = (Test) planned;
                if (!usesIndex(test.source(), test.compare())) {
                    boolean inherent = !(test.source() instanceof Payload) || !INDEXABLE.contains(test.compare());
                    blame.merge(cond.ref().name(), blameOf(cond.ref(), inherent), QueryPlanner::merge);
                }
            }
        }
    }

    private static Blame blameOf(QueryAnalyzer.Ref ref, boolean inherent) {
        boolean flaggable = ref.system() == null && SlotClass.of(ref.field().type()).isPresent();
        return new Blame(ref.name(), flaggable, inherent);
    }

    private static Blame merge(Blame first, Blame second) {
        return new Blame(first.field(), first.flaggable(), first.inherent() && second.inherent());
    }

}
