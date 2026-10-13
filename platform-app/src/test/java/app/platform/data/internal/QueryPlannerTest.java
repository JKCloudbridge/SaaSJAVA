package app.platform.data.internal;

import static app.platform.data.internal.QueryTestSupport.SETTINGS;
import static app.platform.data.internal.QueryTestSupport.access;
import static app.platform.data.internal.QueryTestSupport.thing;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.QueryFilter;
import app.platform.data.QueryFilter.Op;
import app.platform.data.QueryPlan;
import app.platform.data.QueryPlan.Column;
import app.platform.data.QueryPlan.Group;
import app.platform.data.QueryPlan.Payload;
import app.platform.data.QueryPlan.Slot;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The plan stage (Sprint 16, ADR-0100, ADR-0105): where each value is read from, and whether the question reads every
 * record of the object. A field counts as indexed only when its slot is complete.
 */
class QueryPlannerTest {

    private static final Map<String, SlotColumn> COMPLETE = Map.of("code__c", SlotColumn.TEXT_1,
            "qty__c", SlotColumn.NUM_1);

    private final QueryAnalyzer analyzer = new QueryAnalyzer(SETTINGS);
    private final QueryPlanner planner = new QueryPlanner();

    private QueryPlanner.Planned plan(QueryFilter filter, List<QuerySort> sort, Map<String, SlotColumn> complete) {
        QueryAnalyzer.Analyzed analyzed = analyzer.analyze(thing(), access(),
                new RecordQuery(filter, sort, null, List.of(), 10, null));
        return planner.plan(analyzed, complete, null, null, 11);
    }

    @Test
    void aFieldWithACompleteSlotIsReadFromTheSlotAndOthersFromTheRecord() {
        QueryPlanner.Planned planned = plan(QueryFilter.and(List.of(QueryFilter.on("code__c", Op.EQ, "a"),
                QueryFilter.on("name__c", Op.EQ, "b"))), List.of(), COMPLETE);

        Group group = (Group) planned.plan().where();
        assertThat(((QueryPlan.Test) group.items().get(0)).source()).isInstanceOf(Slot.class);
        assertThat(((QueryPlan.Test) group.items().get(1)).source()).isInstanceOf(Payload.class);
    }

    @Test
    void theSystemFieldsAreReadFromTheirColumns() {
        QueryPlanner.Planned planned = plan(QueryFilter.on("createdAt", Op.GTE, "2026-01-01T00:00:00Z"),
                List.of(), Map.of());

        assertThat(((QueryPlan.Test) planned.plan().where()).source()).isInstanceOf(Column.class);
        assertThat(planned.scan()).as("the creation time has an index").isFalse();
    }

    @Test
    void anAndWithOneIndexedConditionIsNotAScanButAnOrWithOneUnindexedBranchIs() {
        QueryFilter indexed = QueryFilter.on("code__c", Op.EQ, "a");
        QueryFilter plain = QueryFilter.on("name__c", Op.EQ, "b");

        assertThat(plan(QueryFilter.and(List.of(indexed, plain)), List.of(), COMPLETE).scan()).isFalse();
        assertThat(plan(QueryFilter.or(List.of(indexed, plain)), List.of(), COMPLETE).scan()).isTrue();
        assertThat(plan(QueryFilter.or(List.of(indexed, QueryFilter.on("qty__c", Op.GT, 5))), List.of(), COMPLETE)
                .scan()).isFalse();
        assertThat(plan(plain, List.of(), COMPLETE).scan()).isTrue();
    }

    @Test
    void noFilterAndNoOrderIsNeverAScan() {
        assertThat(plan(null, List.of(), Map.of()).scan()).isFalse();
    }

    @Test
    void comparisonsThatNoIndexServesNeverMakeAConditionIndexed() {
        for (Op op : List.of(Op.NE, Op.IS_EMPTY, Op.STARTS_WITH, Op.CONTAINS)) {
            Object[] values = op == Op.IS_EMPTY ? new Object[0] : new Object[] {"a"};
            QueryPlanner.Planned planned = plan(QueryFilter.on("code__c", op, values), List.of(), COMPLETE);
            assertThat(planned.scan()).as(op.name() + " on an indexed field").isTrue();
            assertThat(planned.blame().get(0).inherent()).isTrue();
        }
        assertThat(plan(QueryFilter.on("code__c", Op.IS_NOT_EMPTY), List.of(), COMPLETE).scan()).isFalse();
    }

    @Test
    void anOrderIsIndexedOnlyForACompleteSlotTheIdentifierOrTheCreationTime() {
        assertThat(plan(null, List.of(new QuerySort("code__c", false)), COMPLETE).scan()).isFalse();
        assertThat(plan(null, List.of(new QuerySort("createdAt", true)), Map.of()).scan()).isFalse();
        assertThat(plan(null, List.of(new QuerySort("id", false)), Map.of()).scan()).isFalse();
        QueryPlanner.Planned plain = plan(null, List.of(new QuerySort("name__c", false)), COMPLETE);
        assertThat(plain.scan()).isTrue();
        assertThat(plain.blame()).extracting(QueryPlanner.Blame::field).containsExactly("name__c");
        assertThat(plan(null, List.of(new QuerySort("updatedAt", false)), COMPLETE).scan())
                .as("the time of last change has no index").isTrue();
    }

    @Test
    void aFlaggedFieldWhoseSlotIsNotCompleteYetIsStillAScan() {
        QueryPlanner.Planned planned = plan(QueryFilter.on("code__c", Op.EQ, "a"), List.of(), Map.of());

        assertThat(planned.scan()).isTrue();
        assertThat(planned.blame()).hasSize(1);
        assertThat(planned.blame().get(0).flaggable()).isTrue();
        assertThat(planned.blame().get(0).inherent()).isFalse();
    }

    @Test
    void whatRecordLevelSecurityAddsIsCombinedWithTheFilter() {
        QueryAnalyzer.Analyzed analyzed = analyzer.analyze(thing(), access(), new RecordQuery(
                QueryFilter.on("code__c", Op.EQ, "a"), List.of(), null, List.of(), 10, null));
        QueryPlan.Node constraint = new QueryPlan.Test(new Column(QueryPlan.SystemColumn.OWNER),
                QueryPlan.Compare.EQ, List.of("01a1311b-3b3f-79d7-8c8e-effddcd1239e"));

        QueryPlan plan = planner.plan(analyzed, COMPLETE, constraint, null, 11).plan();

        Group group = (Group) plan.where();
        assertThat(group.all()).isTrue();
        assertThat(group.items()).hasSize(2).contains(constraint);
    }

    @Test
    void theStorageIsAskedForOneMoreRecordThanThePage() {
        assertThat(plan(null, List.of(), Map.of()).plan().limit()).isEqualTo(11);
    }
}
