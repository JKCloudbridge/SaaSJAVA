package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
import app.platform.data.QueryPlan.ValueKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The statement the storage builds from a plan (Sprint 16, ADR-0100): no name and no value is ever part of the text,
 * every placeholder has a parameter and every parameter a placeholder (a property over random plans), the table is
 * not named here, and the shapes the database must be able to use as index conditions are the ones produced.
 */
class JsonbQuerySqlTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Set<String> SUPPLIED_BY_THE_STORAGE = Set.of("tenant", "object", "limit");
    private static final UUID ID = UUID.fromString("01a1311b-3b3f-79d7-8c8e-effddcd1239e");
    private static final String HOSTILE = "x'; drop table object_record; --";

    private static String everything(JsonbQuerySql.Built built) {
        StringBuilder text = new StringBuilder(built.filter()).append(' ').append(built.sortSelect());
        built.phases().forEach(phase -> text.append(' ').append(phase.where()).append(' ').append(phase.order()));
        return text.toString();
    }

    private static Set<String> placeholders(String sql) {
        Set<String> names = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(sql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @org.junit.jupiter.api.Test
    void aHostileFieldNameAndValueAreOnlyEverParameters() {
        QueryPlan plan = new QueryPlan("Thing__c", new Group(true, List.of(
                new QueryPlan.Test(new Payload(HOSTILE, ValueKind.TEXT), Compare.EQ, List.of(HOSTILE)),
                new QueryPlan.Test(new Payload(HOSTILE, ValueKind.NUMBER), Compare.IN, List.of("1", HOSTILE)))),
                List.of(new SortKey(new Payload(HOSTILE, ValueKind.TEXT), false)), null, 10);

        JsonbQuerySql.Built built = JsonbQuerySql.build(plan);

        assertThat(everything(built)).doesNotContain("drop table").doesNotContain("x'").doesNotContain("--");
        assertThat(built.params().values()).contains(HOSTILE);
    }

    @org.junit.jupiter.api.Test
    void theTableIsNotNamedHere() {
        QueryPlan plan = new QueryPlan("Thing__c", null,
                List.of(new SortKey(new Column(SystemColumn.CREATED_AT), true)),
                new After(List.of("2026-01-01 10:00:00+00"), ID), 10);

        assertThat(everything(JsonbQuerySql.build(plan))).doesNotContain("object_record");
    }

    @org.junit.jupiter.api.Test
    void withNoOrderThePageIsInIdentifierOrderAndTheCursorIsTheIdentifier() {
        JsonbQuerySql.Built first = JsonbQuerySql.build(new QueryPlan("Thing__c", null, List.of(), null, 10));
        JsonbQuerySql.Built next = JsonbQuerySql.build(new QueryPlan("Thing__c", null, List.of(),
                new After(List.of(), ID), 10));

        assertThat(first.phases()).hasSize(1);
        assertThat(first.phases().get(0).order()).isEqualTo("id");
        assertThat(next.phases().get(0).where()).isEqualTo("id > :aid");
        assertThat(next.params()).containsEntry("aid", ID);
    }

    @org.junit.jupiter.api.Test
    void oneKeyUsesAComparisonOfThePairSoTheIndexScanStartsAtTheCursor() {
        Slot slot = new Slot("s_text_1", "name__c", ValueKind.TEXT);

        JsonbQuerySql.Built ascending = JsonbQuerySql.build(new QueryPlan("Thing__c", null,
                List.of(new SortKey(slot, false)), new After(List.of("b"), ID), 10));
        JsonbQuerySql.Built descending = JsonbQuerySql.build(new QueryPlan("Thing__c", null,
                List.of(new SortKey(slot, true)), new After(List.of("b"), ID), 10));

        assertThat(ascending.phases().get(0).where()).contains("(s_text_1, id) > (cast(:a0 as text), :aid)");
        assertThat(ascending.phases().get(0).order()).isEqualTo("s_text_1, id");
        assertThat(descending.phases().get(0).where()).contains("(s_text_1, id) < (cast(:a0 as text), :aid)");
        assertThat(descending.phases().get(0).order()).isEqualTo("s_text_1 desc, id desc");
    }

    @org.junit.jupiter.api.Test
    void recordsWithoutAValueAreReadInASecondPhaseOnlyAfterTheOnesThatHaveOne() {
        Slot slot = new Slot("s_text_1", "name__c", ValueKind.TEXT);

        JsonbQuerySql.Built fromTheStart = JsonbQuerySql.build(new QueryPlan("Thing__c", null,
                List.of(new SortKey(slot, false)), null, 10));
        JsonbQuerySql.Built insideTheSecond = JsonbQuerySql.build(new QueryPlan("Thing__c", null,
                List.of(new SortKey(slot, false)), new After(Arrays.asList((String) null), ID), 10));

        assertThat(fromTheStart.phases()).hasSize(2);
        assertThat(fromTheStart.phases().get(0).where()).isEqualTo("s_text_1 is not null");
        assertThat(fromTheStart.phases().get(1).where()).isEqualTo("s_text_1 is null");
        assertThat(insideTheSecond.phases()).as("a cursor with no value skips the first phase").hasSize(1);
        assertThat(insideTheSecond.phases().get(0).where()).startsWith("s_text_1 is null and (id > :aid");
    }

    @org.junit.jupiter.api.Test
    void aNumberInASlotIsAWiderNetAndThenCompareAgainExactly() {
        Slot slot = new Slot("s_num_1", "salary__c", ValueKind.NUMBER);

        String greater = JsonbQuerySql.build(new QueryPlan("Thing__c", new QueryPlan.Test(slot, Compare.GT,
                List.of("1000.5")), List.of(), null, 10)).filter();
        String notEqual = JsonbQuerySql.build(new QueryPlan("Thing__c", new QueryPlan.Test(slot, Compare.NE,
                List.of("1000.5")), List.of(), null, 10)).filter();

        assertThat(greater).contains("s_num_1 >= cast(cast(:p0 as numeric) as double precision)")
                .contains("> cast(:p0 as numeric)");
        assertThat(notEqual).as("not equal never uses the slot").doesNotContain("s_num_1");
    }

    @org.junit.jupiter.api.Test
    void aTextSearchIsAFunctionOfTheValueNeverAPattern() {
        String sql = JsonbQuerySql.build(new QueryPlan("Thing__c", new Group(false, List.of(
                new QueryPlan.Test(new Payload("name__c", ValueKind.TEXT), Compare.STARTS_WITH, List.of("50%_off")),
                new QueryPlan.Test(new Payload("name__c", ValueKind.TEXT), Compare.CONTAINS, List.of("a")))),
                List.of(), null, 10)).filter();

        assertThat(sql).contains("starts_with(").contains("position(").doesNotContain(" like ")
                .doesNotContain("ilike");
    }

    // ---- the property: any valid plan yields a statement whose placeholders and parameters match ----

    @Provide
    Arbitrary<Source> sources() {
        return Arbitraries.of(ValueKind.values()).flatMap(kind -> Arbitraries.of(0, 1, 2).<Source>map(which -> {
            if (which == 0) {
                return new Payload("field" + kind.ordinal() + "__c", kind);
            }
            if (which == 1) {
                return new Slot(slotColumn(kind), "field" + kind.ordinal() + "__c", kind);
            }
            return kind == ValueKind.TIMESTAMP ? new Column(SystemColumn.CREATED_AT)
                    : new Payload("other" + kind.ordinal() + "__c", kind);
        }));
    }

    private static String slotColumn(ValueKind kind) {
        return switch (kind) {
            case TEXT -> "s_text_1";
            case NUMBER -> "s_num_1";
            case DATE -> "s_date_1";
            case TIMESTAMP -> "s_ts_1";
            case UUID -> "s_ref_1";
            case BOOLEAN -> "s_bool_1";
        };
    }

    @Provide
    Arbitrary<QueryPlan.Test> tests() {
        return sources().flatMap(source -> Arbitraries.of(Compare.values()).filter(compare -> fits(source, compare))
                .flatMap(compare -> Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8)
                        .list().ofSize(arity(compare)).map(values -> new QueryPlan.Test(source, compare, values))));
    }

    private static boolean fits(Source source, Compare compare) {
        boolean text = compare == Compare.STARTS_WITH || compare == Compare.CONTAINS;
        return !text || source.kind() == ValueKind.TEXT;
    }

    private static int arity(Compare compare) {
        return switch (compare) {
            case IS_EMPTY, IS_NOT_EMPTY -> 0;
            case BETWEEN -> 2;
            case IN -> 3;
            default -> 1;
        };
    }

    @Provide
    Arbitrary<QueryPlan> plans() {
        Arbitrary<QueryPlan.Node> node = tests().list().ofMinSize(1).ofMaxSize(4).flatMap(list ->
                Arbitraries.of(true, false).<QueryPlan.Node>map(all -> list.size() == 1 ? list.get(0)
                        : new Group(all, new ArrayList<QueryPlan.Node>(list))));
        Arbitrary<List<SortKey>> order = sources().flatMap(source ->
                Arbitraries.of(true, false).map(descending -> new SortKey(source, descending))).list().ofMaxSize(2);
        return Arbitraries.of(true, false).flatMap(withFilter -> node.flatMap(filter -> order.flatMap(keys ->
                Arbitraries.of(0, 1, 2).map(cursor -> {
                    List<String> values = new ArrayList<>();
                    for (int index = 0; index < keys.size(); index++) {
                        boolean empty = cursor == 2 && index == 0 || cursor == 1 && index == 1;
                        values.add(empty ? null : "v" + index);
                    }
                    After after = cursor == 0 ? null : new After(values, ID);
                    return new QueryPlan("Thing__c", withFilter ? filter : null, keys, after, 11);
                }))));
    }

    @Property(tries = 300)
    void everyPlaceholderHasAParameterAndEveryParameterAPlaceholder(@ForAll("plans") QueryPlan plan) {
        JsonbQuerySql.Built built = JsonbQuerySql.build(plan);
        Set<String> used = placeholders(everything(built));
        used.removeAll(SUPPLIED_BY_THE_STORAGE);

        assertThat(built.params().keySet()).as("parameters for " + plan).containsAll(used);
        assertThat(used).as("placeholders for " + plan).containsAll(built.params().keySet());
        assertThat(everything(built)).doesNotContain("object_record");
    }
}
