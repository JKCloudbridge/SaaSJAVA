package app.platform.data.internal;

import static app.platform.data.internal.QueryTestSupport.SETTINGS;
import static app.platform.data.internal.QueryTestSupport.access;
import static app.platform.data.internal.QueryTestSupport.thing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.QueryFilter;
import app.platform.data.QueryFilter.Op;
import app.platform.data.QueryPlan.Compare;
import app.platform.data.QueryResolve;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The first three stages of a query (Sprint 16, ADR-0100): shape, fields, operators and values. The first stage that
 * finds problems answers alone, and a field the member may not read is a field that does not exist at every step.
 */
class QueryAnalyzerTest {

    private final QueryAnalyzer analyzer = new QueryAnalyzer(SETTINGS);

    private static RecordQuery filtered(QueryFilter filter) {
        return new RecordQuery(filter, List.of(), null, List.of(), 0, null);
    }

    private static Map<String, List<String>> refused(RecordQuery query) {
        QueryAnalyzer analyzer = new QueryAnalyzer(SETTINGS);
        return ((ApiException) catchThrowable(() -> analyzer.analyze(thing(), access(), query))).fields();
    }

    private static Throwable catchThrowable(Runnable run) {
        try {
            run.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("the query was accepted");
    }

    private static QueryFilter nest(int levels) {
        QueryFilter node = QueryFilter.on("name__c", Op.EQ, "x");
        for (int i = 1; i < levels; i++) {
            node = QueryFilter.and(List.of(node));
        }
        return node;
    }

    // ---- stage 1: shape ----

    @Test
    void aFilterMayBeAtMostFourLevelsDeep() {
        assertThat(analyzer.analyze(thing(), access(), filtered(nest(4))).where()).isNotNull();
        assertThat(refused(filtered(nest(5)))).containsKey("filter.items[0].items[0].items[0].items[0]");
    }

    @Test
    void aFilterMayHaveAtMostFiftyNodes() {
        List<QueryFilter> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(QueryFilter.on("name__c", Op.EQ, "x" + i));
        }

        assertThat(refused(filtered(QueryFilter.or(many)))).containsKey("filter");
    }

    @Test
    void theNumberOfValuesFitsTheComparison() {
        assertThat(refused(filtered(QueryFilter.on("qty__c", Op.BETWEEN, 1)))).containsKey("filter.values");
        assertThat(refused(filtered(QueryFilter.on("qty__c", Op.EQ)))).containsKey("filter.values");
        assertThat(refused(filtered(QueryFilter.on("qty__c", Op.EQ, 1, 2)))).containsKey("filter.values");
        assertThat(refused(filtered(QueryFilter.on("qty__c", Op.IS_EMPTY, 1)))).containsKey("filter.values");
        List<Object> hundredAndOne = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            hundredAndOne.add(i);
        }
        assertThat(refused(filtered(new QueryFilter(Op.IN, List.of(), "qty__c", hundredAndOne))))
                .containsKey("filter.values");
    }

    @Test
    void anEmptyGroupAndAConditionWithoutAFieldAreRefused() {
        assertThat(refused(filtered(QueryFilter.and(List.of())))).containsKey("filter");
        assertThat(refused(filtered(new QueryFilter(Op.EQ, List.of(), null, List.of("x")))))
                .containsKey("filter.field");
    }

    @Test
    void thePageSizeTheOrderAndTheFieldListAreBounded() {
        assertThat(refused(new RecordQuery(null, List.of(), null, List.of(), 201, null))).containsKey("limit");
        assertThat(refused(new RecordQuery(null, List.of(new QuerySort("name__c", false),
                new QuerySort("qty__c", false), new QuerySort("due__c", false)), null, List.of(), 0, null)))
                .containsKey("sort");
        List<String> manyFields = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            manyFields.add("name__c");
        }
        assertThat(refused(new RecordQuery(null, List.of(), manyFields, List.of(), 0, null)))
                .containsKey("fields");
    }

    // ---- stage 2: the fields, against the metadata and the member's permissions together ----

    @Test
    void aFieldTheMemberMayNotReadAnswersExactlyLikeAFieldThatDoesNotExist() {
        Map<String, List<String>> forbidden = refused(filtered(QueryFilter.on("secret__c", Op.EQ, "x")));
        Map<String, List<String>> unknown = refused(filtered(QueryFilter.on("nothing__c", Op.EQ, "x")));

        assertThat(forbidden).isEqualTo(unknown);
        assertThat(forbidden.get("filter.field")).containsExactly(QueryAnalyzer.NOT_A_FIELD);
    }

    @Test
    void anOperatorThatDoesNotFitCannotRevealTheTypeOfAFieldTheMemberMayNotRead() {
        // CONTAINS on a number would be a fine "does not fit" error for a readable field; for a field the member may
        // not read, the answer must be the one for a field that does not exist, and nothing more.
        Map<String, List<String>> answer = refused(filtered(QueryFilter.and(List.of(
                QueryFilter.on("secret__c", Op.GT, "x"), QueryFilter.on("qty__c", Op.CONTAINS, "x")))));

        assertThat(answer).containsOnlyKeys("filter.items[0].field");
        assertThat(answer.get("filter.items[0].field")).containsExactly(QueryAnalyzer.NOT_A_FIELD);
    }

    @Test
    void theFieldsOfTheOrderTheProjectionAndTheLinksAreCheckedTheSameWay() {
        Map<String, List<String>> answer = refused(new RecordQuery(null, List.of(new QuerySort("secret__c", false)),
                List.of("secret__c", "name__c"), List.of(new QueryResolve("secret__c", List.of("x"))), 0, null));

        assertThat(answer).containsOnlyKeys("sort[0].field", "fields[0]", "resolve[0].field");
        answer.values().forEach(texts -> assertThat(texts).containsExactly(QueryAnalyzer.NOT_A_FIELD));
    }

    @Test
    void everyProblemOfTheFirstStageThatFindsAnyIsReportedAtOnce() {
        Map<String, List<String>> answer = refused(filtered(QueryFilter.and(List.of(
                QueryFilter.on("nothing__c", Op.EQ, "x"), QueryFilter.on("secret__c", Op.EQ, "y")))));

        assertThat(answer).containsOnlyKeys("filter.items[0].field", "filter.items[1].field");
    }

    // ---- stage 3: operators and values ----

    @Test
    void aTextFieldTakesEveryComparisonANumberTakesNoTextSearch() {
        for (Op op : List.of(Op.EQ, Op.NE, Op.GT, Op.GTE, Op.LT, Op.LTE, Op.IS_EMPTY, Op.IS_NOT_EMPTY)) {
            Object[] values = op == Op.IS_EMPTY || op == Op.IS_NOT_EMPTY ? new Object[0] : new Object[] {"a"};
            assertThat(analyzer.analyze(thing(), access(), filtered(QueryFilter.on("name__c", op, values))).where())
                    .as(op.name()).isNotNull();
        }
        assertThat(analyzer.analyze(thing(), access(), filtered(QueryFilter.on("name__c", Op.STARTS_WITH, "a")))
                .where()).isNotNull();
        assertThat(refused(filtered(QueryFilter.on("qty__c", Op.STARTS_WITH, "1")))).containsKey("filter.op");
        assertThat(refused(filtered(QueryFilter.on("done__c", Op.GT, true)))).containsKey("filter.op");
        assertThat(refused(filtered(QueryFilter.on("owner__c", Op.GT, "x")))).containsKey("filter.op");
    }

    @Test
    void valuesAreConvertedToTheStoredTextOfTheirKindOrRefusedWithWhatIsExpected() {
        QueryAnalyzer.Cond number = (QueryAnalyzer.Cond) analyzer.analyze(thing(), access(),
                filtered(QueryFilter.on("qty__c", Op.EQ, "12.50"))).where();
        QueryAnalyzer.Cond day = (QueryAnalyzer.Cond) analyzer.analyze(thing(), access(),
                filtered(QueryFilter.on("due__c", Op.EQ, "2026-10-12"))).where();
        QueryAnalyzer.Cond time = (QueryAnalyzer.Cond) analyzer.analyze(thing(), access(),
                filtered(QueryFilter.on("seen__c", Op.EQ, "2026-10-12T11:30:00+02:00"))).where();
        QueryAnalyzer.Cond flag = (QueryAnalyzer.Cond) analyzer.analyze(thing(), access(),
                filtered(QueryFilter.on("done__c", Op.EQ, true))).where();

        assertThat(number.values()).containsExactly("12.50");
        assertThat(day.values()).containsExactly("2026-10-12");
        assertThat(time.values()).containsExactly("2026-10-12T09:30:00Z");
        assertThat(flag.values()).containsExactly("true");

        assertThat(refused(filtered(QueryFilter.on("qty__c", Op.EQ, "twelve"))).get("filter.values[0]"))
                .containsExactly("Enter a number.");
        assertThat(refused(filtered(QueryFilter.on("due__c", Op.EQ, "12/10/2026"))).get("filter.values[0]"))
                .containsExactly("Enter a day as year-month-day.");
        assertThat(refused(filtered(QueryFilter.on("done__c", Op.EQ, "true"))).get("filter.values[0]"))
                .containsExactly("Enter true or false.");
        assertThat(refused(filtered(QueryFilter.on("owner__c", Op.EQ, "not-an-id"))).get("filter.values[0]"))
                .containsExactly("Enter an identifier.");
        assertThat(refused(filtered(QueryFilter.on("name__c", Op.EQ, "x".repeat(256)))).get("filter.values[0]"))
                .hasSize(1);
    }

    @Test
    void aMessageNeverRepeatsTheValueOrTheNameTheMemberTyped() {
        Map<String, List<String>> answer = refused(filtered(QueryFilter.on("qty__c", Op.EQ, "SECRET-VALUE")));

        assertThat(answer.toString()).doesNotContain("SECRET-VALUE");
    }

    @Test
    void aFormulaALongTextAndALinkCannotBeUsedWhereTheyCannotWork() {
        assertThat(refused(filtered(QueryFilter.on("calc__c", Op.GT, 1))).get("filter.field"))
                .containsExactly(QueryAnalyzer.FORMULA);
        assertThat(refused(filtered(QueryFilter.on("notes__c", Op.EQ, "x"))).get("filter.field"))
                .containsExactly(QueryAnalyzer.CANNOT_FILTER);
        assertThat(refused(new RecordQuery(null, List.of(new QuerySort("calc__c", false)), null, List.of(), 0,
                null)).get("sort[0].field")).containsExactly(QueryAnalyzer.FORMULA);
        assertThat(refused(new RecordQuery(null, List.of(new QuerySort("owner__c", false)), null, List.of(), 0,
                null)).get("sort[0].field")).containsExactly("A link cannot be used in an order.");
        assertThat(refused(new RecordQuery(null, List.of(new QuerySort("name__c", false),
                new QuerySort("name__c", true)), null, List.of(), 0, null))).containsKey("sort[1].field");
    }

    @Test
    void aLinkToFollowMustBeALinkField() {
        assertThat(refused(new RecordQuery(null, List.of(), null, List.of(new QueryResolve("name__c", List.of("x"))),
                0, null)).get("resolve[0].field")).containsExactly(QueryAnalyzer.NOT_A_LINK);
        QueryAnalyzer.Analyzed fine = analyzer.analyze(thing(), access(), new RecordQuery(null, List.of(), null,
                List.of(new QueryResolve("owner__c", List.of("name__c"))), 0, null));
        assertThat(fine.follows()).hasSize(1);
        assertThat(fine.follows().get(0).target()).isEqualTo("Target__c");
    }

    @Test
    void theSystemFieldsAreReadFromTheColumnsAndTakeTheirKindOfComparison() {
        QueryAnalyzer.Cond created = (QueryAnalyzer.Cond) analyzer.analyze(thing(), access(),
                filtered(QueryFilter.on("createdAt", Op.GTE, "2026-01-01T00:00:00Z"))).where();
        QueryAnalyzer.Cond id = (QueryAnalyzer.Cond) analyzer.analyze(thing(), access(),
                filtered(QueryFilter.on("id", Op.IN, "01a1311b-3b3f-79d7-8c8e-effddcd1239e"))).where();

        assertThat(created.ref().system()).isNotNull();
        assertThat(created.compare()).isEqualTo(Compare.GTE);
        assertThat(id.ref().system()).isNotNull();
        assertThat(refused(filtered(QueryFilter.on("ownerId", Op.GT, "x")))).containsKey("filter.op");
    }

    @Test
    void thePageSizeDefaultsAndTheProjectionIsKept() {
        QueryAnalyzer.Analyzed defaults = analyzer.analyze(thing(), access(), new RecordQuery(null, List.of(),
                List.of("name__c"), List.of(), 0, null));

        assertThat(defaults.limit()).isEqualTo(50);
        assertThat(defaults.projection()).containsExactly("name__c");
        assertThatThrownBy(() -> analyzer.analyze(thing(), access(), new RecordQuery(null, List.of(),
                List.of("secret__c"), List.of(), 0, null))).isInstanceOf(ApiException.class);
    }

    // ---- what identifies a query to its cursors ----

    @Test
    void theFingerprintCoversTheFilterAndTheOrderButNotThePageSizeOrTheProjection() {
        RecordQuery base = new RecordQuery(QueryFilter.on("name__c", Op.EQ, "a"),
                List.of(new QuerySort("qty__c", false)), null, List.of(), 10, null);
        RecordQuery otherPage = new RecordQuery(QueryFilter.on("name__c", Op.EQ, "a"),
                List.of(new QuerySort("qty__c", false)), List.of("name__c"), List.of(), 100, "anything");
        RecordQuery otherValue = new RecordQuery(QueryFilter.on("name__c", Op.EQ, "b"),
                List.of(new QuerySort("qty__c", false)), null, List.of(), 10, null);
        RecordQuery otherOrder = new RecordQuery(QueryFilter.on("name__c", Op.EQ, "a"),
                List.of(new QuerySort("qty__c", true)), null, List.of(), 10, null);

        String signature = analyzer.analyze(thing(), access(), base).signature();

        assertThat(analyzer.analyze(thing(), access(), otherPage).signature()).isEqualTo(signature);
        assertThat(analyzer.analyze(thing(), access(), otherValue).signature()).isNotEqualTo(signature);
        assertThat(analyzer.analyze(thing(), access(), otherOrder).signature()).isNotEqualTo(signature);
    }
}
