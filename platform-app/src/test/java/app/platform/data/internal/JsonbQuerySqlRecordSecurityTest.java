package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.QueryPlan;
import app.platform.data.QueryPlan.Compare;
import app.platform.data.QueryPlan.Group;
import app.platform.data.QueryPlan.NoRecords;
import app.platform.data.QueryPlan.OwnedByAny;
import app.platform.data.QueryPlan.SharedWithAny;
import app.platform.data.QueryPlan.SystemColumn;
import app.platform.data.QueryPlan.ValueKind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The conditions of record-level security as SQL (ADR-0119): built from fixed fragments, with every identifier a
 * parameter, so that no identifier typed by anyone and no name of an owner is ever part of the text of a statement.
 */
class JsonbQuerySqlRecordSecurityTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)");
    private static final UUID ONE = UUID.fromString("01a1311b-3b3f-79d7-8c8e-effddcd1239e");
    private static final UUID TWO = UUID.fromString("01a1311b-3b3f-79d7-8c8e-effddcd1239f");

    private static Set<String> placeholders(String sql) {
        Set<String> names = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(sql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    void theOwnersAreOneParameterConvertedToAnArrayInTheStatement() {
        JsonbQuerySql.Condition condition = JsonbQuerySql.condition(new OwnedByAny(List.of(ONE, TWO)));

        assertThat(condition.sql()).startsWith("owner_id = any(cast(string_to_array(:p0, ',') as uuid[]))")
                .doesNotContain(ONE.toString());
        assertThat(condition.params()).containsEntry("p0", ONE + "," + TWO);
    }

    @Test
    void aShareIsAConditionOnTheIdentifiersOfTheSharedRecordsReadOnceForTheStatement() {
        JsonbQuerySql.Condition condition = JsonbQuerySql.condition(new SharedWithAny(List.of(ONE), 2));

        assertThat(condition.sql()).startsWith("id = any(array(select s.record_id from record_share s where")
                .contains("s.tenant_id = :tenant").contains("s.object_api_name = :object")
                .contains("s.deleted_at is null").contains("s.access_level >= :p1");
        assertThat(condition.params()).containsEntry("p0", ONE.toString()).containsEntry("p1", 2);
        assertThat(condition.sql()).doesNotContain(ONE.toString());
    }

    @Test
    void noRecordsIsFalse() {
        assertThat(JsonbQuerySql.condition(new NoRecords()).sql()).isEqualTo("false");
        assertThat(JsonbQuerySql.condition(new NoRecords()).params()).isEmpty();
    }

    @Test
    void anOrOfOwnersAndSharesBecomesAnOrOfTheTwoConditions() {
        QueryPlan.Node node = new Group(false, List.of(new OwnedByAny(List.of(ONE)),
                new SharedWithAny(List.of(TWO), 1)));

        JsonbQuerySql.Condition condition = JsonbQuerySql.condition(node);

        assertThat(condition.sql()).startsWith("(owner_id = any(").contains(") or id = any(array(select")
                .endsWith("))");
        assertThat(condition.params()).hasSize(3);
    }

    @Test
    void everyPlaceholderHasAParameterAndEveryParameterAPlaceholderExceptWhatTheStorageSupplies() {
        QueryPlan.Node node = new Group(true, List.of(
                new QueryPlan.Test(new QueryPlan.Column(SystemColumn.OWNER), Compare.EQ, List.of(ONE.toString())),
                new Group(false, List.of(new OwnedByAny(List.of(ONE, TWO)), new SharedWithAny(List.of(ONE), 1),
                        new NoRecords()))));

        JsonbQuerySql.Condition condition = JsonbQuerySql.condition(node);
        Set<String> used = placeholders(condition.sql());
        used.removeAll(Set.of("tenant", "object"));

        assertThat(condition.params().keySet()).containsExactlyInAnyOrderElementsOf(used);
    }

    @Test
    void theConditionInAFullPlanKeepsEveryParameterNamedOnce() {
        QueryPlan plan = new QueryPlan("Deal__c", new Group(true, List.of(new QueryPlan.Test(
                new QueryPlan.Payload("name__c", ValueKind.TEXT), Compare.EQ, List.of("x")),
                new OwnedByAny(List.of(ONE)))), List.of(), null, 11);

        JsonbQuerySql.Built built = JsonbQuerySql.build(plan);

        assertThat(built.filter()).contains("owner_id = any(cast(string_to_array(:p1, ',') as uuid[]))");
        assertThat(built.params()).containsKeys("f0", "p0", "p1");
        assertThat(built.filter()).doesNotContain(ONE.toString());
    }
}
