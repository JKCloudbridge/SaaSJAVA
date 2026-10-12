package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a write may contain (Sprint 14, ADR-0089), without a database: which keys are accepted, which values the store
 * can keep and which it refuses, that an error names a key and never a value, and the bounds. Nothing here checks what
 * a value means: that is Sprint 15.
 */
class RecordValuesTest {

    private static final int MAX_BYTES = 4096;

    private final RecordValues values = new RecordValues(new RecordJson(), MAX_BYTES);

    private static FieldDefinition field(String name, FieldType type, DefinitionKind kind, boolean retired) {
        return new FieldDefinition("Thing__c", name, name, "", kind, type, false, false, null,
                new FieldConfiguration.NoConfiguration(), retired, 0);
    }

    private static final ObjectDefinition THING = new ObjectDefinition("Thing__c", "Thing", "Things", "",
            DefinitionKind.CUSTOM, null, true, 0, List.of(
                    field("id", FieldType.TEXT, DefinitionKind.SYSTEM, false),
                    field("name__c", FieldType.TEXT, DefinitionKind.CUSTOM, false),
                    field("count__c", FieldType.NUMBER, DefinitionKind.CUSTOM, false),
                    field("tags__c", FieldType.MULTI_PICKLIST, DefinitionKind.CUSTOM, false),
                    field("total__c", FieldType.FORMULA, DefinitionKind.CUSTOM, false),
                    field("old__c", FieldType.TEXT, DefinitionKind.CUSTOM, true)));

    private RecordValues.Checked check(Map<String, Object> set) {
        return values.check(THING, set, List.of(), false);
    }

    private static Map<String, List<String>> problemsOf(Runnable refused) {
        return catchApi(refused).fields();
    }

    private static ApiException catchApi(Runnable refused) {
        try {
            refused.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected the write to be refused");
    }

    // ---- keys ----

    @Test
    void aKeyThatIsAWritableFieldIsAccepted() {
        RecordValues.Checked checked = check(Map.of("name__c", "A", "count__c", 3, "tags__c", List.of("a", "b")));

        assertThat(checked.set()).containsOnlyKeys("name__c", "count__c", "tags__c");
        assertThat(checked.set().get("tags__c")).isEqualTo(List.of("a", "b"));
        assertThat(checked.names()).containsExactly("count__c", "name__c", "tags__c");
    }

    @Test
    void anUnknownKeyIsRefusedAndNamed() {
        Map<String, List<String>> problems = problemsOf(() -> check(Map.of("nope__c", "x")));

        assertThat(problems).containsOnlyKeys("nope__c");
        assertThat(problems.get("nope__c")).containsExactly("This is not a field of the object.");
    }

    @Test
    void aSystemFieldACalculatedFieldAndARetiredFieldCannotBeWritten() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("id", "x");
        all.put("total__c", 1);
        all.put("old__c", "x");

        Map<String, List<String>> problems = problemsOf(() -> check(all));

        assertThat(problems).containsOnlyKeys("id", "total__c", "old__c");
        assertThat(problems.values()).allSatisfy(texts -> assertThat(texts).containsExactly(
                "This field cannot be written."));
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("nope__c", "x");
        bad.put("name__c", Map.of("deep", 1));
        bad.put("total__c", 1);

        assertThat(problemsOf(() -> check(bad))).containsOnlyKeys("nope__c", "name__c", "total__c");
    }

    @Test
    void aKeyThatIsNotShapedLikeAFieldNameIsNeverRepeated() {
        for (String hostile : List.of("x'; drop table object_record; --", "a b", "", "1abc",
                "<script>alert(1)</script>",
                "a".repeat(61), "caf\u00e9__c", "..", "name__c ")) {
            Map<String, List<String>> problems = problemsOf(() -> check(Map.of(hostile, "x")));

            assertThat(problems).as("key: " + hostile.length() + " characters").containsOnlyKeys("fields");
            assertThat(problems.toString()).doesNotContain(hostile.isEmpty() ? "\u0000" : hostile);
        }
    }

    @Test
    void theSameFieldCannotBeGivenAValueAndClearedAndAClearNamesOnlyWritableFields() {
        Map<String, List<String>> both = problemsOf(() -> values.check(THING, Map.of("name__c", "x"),
                List.of("name__c"), true));
        Map<String, List<String>> unknown = problemsOf(() -> values.check(THING, Map.of(),
                List.of("nope__c", "id"), true));

        assertThat(both).containsOnlyKeys("name__c");
        assertThat(unknown).containsOnlyKeys("nope__c", "id");
    }

    @Test
    void anUpdateMustChangeSomethingAndACreateMayNot() {
        assertThat(values.check(THING, Map.of(), List.of(), false).set()).isEmpty();
        assertThat(problemsOf(() -> values.check(THING, Map.of(), List.of(), true))).containsOnlyKeys("fields");
        assertThat(values.check(THING, Map.of(), List.of("name__c"), true).clear()).containsExactly("name__c");
    }

    // ---- values ----

    @Test
    void textsNumbersAndBooleansAreKeptAndNumbersBecomeDecimals() {
        Map<String, Object> given = new HashMap<>();
        given.put("name__c", "line one\nline \"two\" \u00e9 \u1f600");
        given.put("count__c", 7);
        given.put("tags__c", List.of(true, "x", 1.5, 2L));

        Map<String, Object> kept = check(given).set();

        assertThat(kept.get("name__c")).isEqualTo("line one\nline \"two\" \u00e9 \u1f600");
        assertThat(kept.get("count__c")).isEqualTo(new BigDecimal("7"));
        assertThat(kept.get("tags__c")).isEqualTo(List.of(true, "x", new BigDecimal("1.5"), new BigDecimal("2")));
    }

    @Test
    void aDecimalIsKeptExactlyAndADoubleIsKeptAsItPrints() {
        assertThat(check(Map.of("count__c", new BigDecimal("12345678901234.567890123"))).set().get("count__c"))
                .isEqualTo(new BigDecimal("12345678901234.567890123"));
        assertThat(check(Map.of("count__c", 0.1d)).set().get("count__c")).isEqualTo(new BigDecimal("0.1"));
        assertThat(check(Map.of("count__c", new BigDecimal("0E+50"))).set()).as("zero is zero however it is written")
                .containsKey("count__c");
    }

    @Test
    void aNumberTheStoreCannotKeepIsRefused() {
        for (Number number : List.<Number>of(Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("1E+999999"),
                new BigDecimal("1E-999999"), new BigDecimal("1".repeat(39)),
                new BigDecimal("0.".concat("1".repeat(39))))) {
            assertThat(problemsOf(() -> check(Map.of("count__c", number)))).as(number.toString())
                    .containsOnlyKeys("count__c");
        }
    }

    @Test
    void aValueOfAnotherShapeIsRefusedWithoutBeingRepeated() {
        List<Object> nested = new ArrayList<>();
        nested.add(List.of("x"));
        for (Object value : List.of(Map.of("deep", "SENTINEL"), nested, List.of(Map.of("a", "SENTINEL")),
                "before\u0000SENTINEL", "x".repeat(RecordValues.MAX_TEXT_LENGTH + 1), new Object())) {
            Map<String, List<String>> problems = problemsOf(() -> check(Map.of("name__c", value)));

            assertThat(problems).containsOnlyKeys("name__c");
            assertThat(problems.toString()).doesNotContain("SENTINEL");
        }
    }

    @Test
    void aListHasAtMostThatManyValuesAndNoNulls() {
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i <= RecordValues.MAX_LIST_SIZE; i++) {
            tooMany.add("v");
        }
        List<Object> withNull = new ArrayList<>();
        withNull.add("a");
        withNull.add(null);

        assertThat(problemsOf(() -> check(Map.of("tags__c", tooMany)))).containsOnlyKeys("tags__c");
        assertThat(problemsOf(() -> check(Map.of("tags__c", withNull)))).containsOnlyKeys("tags__c");
    }

    @Test
    void theValuesOfOneRecordAreBounded() {
        Map<String, Object> big = Map.of("name__c", "a".repeat(MAX_BYTES + 1));
        Map<String, Object> fits = Map.of("name__c", "a".repeat(MAX_BYTES - 100));

        assertThat(problemsOf(() -> check(big))).containsOnlyKeys("fields");
        assertThat(check(fits).set()).containsKey("name__c");
        assertThatThrownBy(() -> check(big)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void theBytesCountEncodedCharactersNotJavaCharacters() {
        // 1,200 emoji are 2,400 Java characters, 4,800 bytes of UTF-8 and more as escaped JSON: over the 4,096 bound.
        Map<String, Object> emoji = Map.of("name__c", "\u1f600".repeat(1_200));

        assertThat(problemsOf(() -> check(emoji))).containsOnlyKeys("fields");
    }
}
