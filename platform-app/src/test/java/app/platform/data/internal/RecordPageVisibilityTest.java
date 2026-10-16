package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Whether a component or an action of a record page is shown (Sprint 20, ADR-0131): the closed rule of Sprint 12
 * read off the page's own JSON. No expression, no code: a field compared with a value, an ability, and all/any.
 */
class RecordPageVisibilityTest {

    private static final Predicate<String> HOLDS_VIEW = ability -> Set.of("members.view").contains(ability);

    private static Map<String, Object> field(String name, String operator, Object value) {
        return value == null ? Map.of("field", name, "operator", operator)
                : Map.of("field", name, "operator", operator, "value", value);
    }

    private static boolean visible(Object rule, Map<String, Object> record) {
        return RecordPageVisibility.visible(rule, HOLDS_VIEW, record);
    }

    @Test
    void noRuleAndAShapeThatIsNotARuleShowTheComponent() {
        assertThat(visible(null, Map.of())).isTrue();
        assertThat(visible("stage == 'Won'", Map.of())).as("text is never evaluated").isTrue();
        assertThat(visible(Map.of("expression", "1 == 1"), Map.of())).isTrue();
    }

    @Test
    void anAbilityIsAskedOfTheCaller() {
        assertThat(visible(Map.of("ability", "members.view"), Map.of())).isTrue();
        assertThat(visible(Map.of("ability", "members.invite"), Map.of())).isFalse();
    }

    @Test
    void equalsAndNotEqualsComparePlainValuesAndNumbersByValue() {
        Map<String, Object> record = Map.of("stage", "Won", "amount", 10);

        assertThat(visible(field("stage", "EQUALS", "Won"), record)).isTrue();
        assertThat(visible(field("stage", "EQUALS", "Lost"), record)).isFalse();
        assertThat(visible(field("stage", "NOT_EQUALS", "Lost"), record)).isTrue();
        assertThat(visible(field("stage", "NOT_EQUALS", "Won"), record)).isFalse();
        assertThat(visible(field("amount", "EQUALS", 10.0), record)).as("10 and 10.0 are the same number").isTrue();
    }

    @Test
    void emptyMeansNoValueOrAnEmptyText() {
        Map<String, Object> record = new HashMap<>();
        record.put("blank", "");
        record.put("none", null);
        record.put("set", "x");

        assertThat(visible(field("blank", "IS_EMPTY", null), record)).isTrue();
        assertThat(visible(field("none", "IS_EMPTY", null), record)).isTrue();
        assertThat(visible(field("missing", "IS_EMPTY", null), record)).isTrue();
        assertThat(visible(field("set", "IS_EMPTY", null), record)).isFalse();
        assertThat(visible(field("set", "IS_NOT_EMPTY", null), record)).isTrue();
        assertThat(visible(field("blank", "IS_NOT_EMPTY", null), record)).isFalse();
    }

    @Test
    void aFieldTheMemberCannotReadIsAbsentSoItReadsAsEmptyAndNeverAsEqual() {
        Map<String, Object> record = Map.of("visible", "x");

        assertThat(visible(field("secret", "IS_EMPTY", null), record)).isTrue();
        assertThat(visible(field("secret", "EQUALS", "anything"), record)).isFalse();
        assertThat(visible(field("secret", "NOT_EQUALS", "anything"), record)).isTrue();
    }

    @Test
    void aRecordThatDoesNotExistYetHasNothingToTestSoAFieldRuleIsPermissive() {
        assertThat(visible(field("stage", "EQUALS", "Won"), null)).isTrue();
        assertThat(visible(field("stage", "IS_NOT_EMPTY", null), null)).isTrue();
        assertThat(visible(Map.of("ability", "members.invite"), null)).as("an ability is still asked").isFalse();
    }

    @Test
    void allNeedsEveryPartAndAnyNeedsOne() {
        Map<String, Object> record = Map.of("stage", "Won");
        Object won = field("stage", "EQUALS", "Won");
        Object lost = field("stage", "EQUALS", "Lost");

        assertThat(visible(Map.of("all", List.of(won, Map.of("ability", "members.view"))), record)).isTrue();
        assertThat(visible(Map.of("all", List.of(won, lost)), record)).isFalse();
        assertThat(visible(Map.of("any", List.of(lost, won)), record)).isTrue();
        assertThat(visible(Map.of("any", List.of(lost, Map.of("ability", "members.invite"))), record))
                .isFalse();
    }

    @Test
    void groupsNestOnce() {
        Map<String, Object> record = Map.of("stage", "Won");
        Object inner = Map.of("any",
                List.of(field("stage", "EQUALS", "Lost"), Map.of("ability", "members.view")));

        assertThat(visible(Map.of("all", List.of(field("stage", "NOT_EQUALS", "Lost"), inner)), record))
                .isTrue();
    }

    @Test
    void anOperatorTheRuleDoesNotKnowNeverHidesAComponent() {
        assertThat(visible(field("stage", "STARTS_WITH", "W"), Map.of("stage", "Won"))).isTrue();
    }
}
