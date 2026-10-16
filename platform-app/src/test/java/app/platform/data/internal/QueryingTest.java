package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import org.junit.jupiter.api.Test;

/**
 * What a screen is told it may ask of a field (Sprint 19, ADR-0129): taken from the tables the query engine itself
 * uses, so the form description and the engine cannot disagree about which comparisons a field takes.
 */
class QueryingTest {

    private static final String[] ALL = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN", "IS_EMPTY",
        "IS_NOT_EMPTY", "STARTS_WITH", "CONTAINS"};
    private static final String[] ORDERED = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN", "IS_EMPTY",
        "IS_NOT_EMPTY"};

    private static FieldDefinition field(String name, DefinitionKind kind, FieldType type,
            FieldConfiguration configuration) {
        return new FieldDefinition("Thing__c", name, name, "", kind, type, false, false, null, configuration, false, 0);
    }

    private static FieldDefinition custom(FieldType type, FieldConfiguration configuration) {
        return field("a__c", DefinitionKind.CUSTOM, type, configuration);
    }

    @Test
    void textLikeFieldsTakeEveryComparisonAndCanBeOrdered() {
        for (FieldType type : new FieldType[] {FieldType.TEXT, FieldType.EMAIL, FieldType.PHONE, FieldType.URL}) {
            QueryAnalyzer.Querying querying = QueryAnalyzer.queryingOf(custom(type, new TextConfiguration(50)));

            assertThat(querying.operators()).as(type.name()).containsExactly(ALL);
            assertThat(querying.sortable()).as(type.name()).isTrue();
        }
    }

    @Test
    void numbersAndDaysTakeTheOrderedComparisonsButNoTextSearch() {
        for (FieldType type : new FieldType[] {FieldType.NUMBER, FieldType.DECIMAL, FieldType.CURRENCY,
            FieldType.PERCENT, FieldType.DATE, FieldType.DATETIME}) {
            QueryAnalyzer.Querying querying = QueryAnalyzer.queryingOf(custom(type, new NoConfiguration()));

            assertThat(querying.operators()).as(type.name()).containsExactly(ORDERED);
            assertThat(querying.sortable()).as(type.name()).isTrue();
        }
    }

    @Test
    void aCheckboxTakesFourComparisons() {
        QueryAnalyzer.Querying querying = QueryAnalyzer.queryingOf(custom(FieldType.BOOLEAN, new NoConfiguration()));

        assertThat(querying.operators()).containsExactly("EQ", "NE", "IS_EMPTY", "IS_NOT_EMPTY");
        assertThat(querying.sortable()).isTrue();
    }

    @Test
    void aLinkIsFilteredByEqualityAndNeverOrdered() {
        for (FieldType type : new FieldType[] {FieldType.LOOKUP, FieldType.MASTER_DETAIL}) {
            QueryAnalyzer.Querying querying = QueryAnalyzer.queryingOf(
                    custom(type, new ReferenceConfiguration("Target__c")));

            assertThat(querying.operators()).as(type.name()).containsExactly("EQ", "NE", "IN", "IS_EMPTY",
                    "IS_NOT_EMPTY");
            assertThat(querying.sortable()).as(type.name()).isFalse();
        }
    }

    @Test
    void whatCannotBeComparedHasNoComparisonAndNoOrder() {
        for (FieldType type : new FieldType[] {FieldType.LONG_TEXT, FieldType.MULTI_PICKLIST, FieldType.TIME}) {
            QueryAnalyzer.Querying querying = QueryAnalyzer.queryingOf(custom(type, new NoConfiguration()));

            assertThat(querying.operators()).as(type.name()).isEmpty();
            assertThat(querying.sortable()).as(type.name()).isFalse();
        }
    }

    @Test
    void aFormulaIsComparedAsTheTypeOfItsResult() {
        QueryAnalyzer.Querying number = QueryAnalyzer.queryingOf(custom(FieldType.FORMULA,
                new FormulaConfiguration("1", FieldType.NUMBER, true)));
        QueryAnalyzer.Querying text = QueryAnalyzer.queryingOf(custom(FieldType.FORMULA,
                new FormulaConfiguration("'a'", FieldType.TEXT, true)));

        assertThat(number.operators()).containsExactly(ORDERED);
        assertThat(text.operators()).containsExactly(ALL);
        assertThat(number.sortable()).isTrue();
    }

    @Test
    void aSystemFieldIsComparedAsTheColumnItIs() {
        QueryAnalyzer.Querying owner = QueryAnalyzer.queryingOf(field("ownerId", DefinitionKind.SYSTEM,
                FieldType.LOOKUP, new ReferenceConfiguration("User")));
        QueryAnalyzer.Querying created = QueryAnalyzer.queryingOf(field("createdAt", DefinitionKind.SYSTEM,
                FieldType.DATETIME, new NoConfiguration()));
        QueryAnalyzer.Querying id = QueryAnalyzer.queryingOf(field("id", DefinitionKind.SYSTEM, FieldType.TEXT,
                new TextConfiguration(36)));

        assertThat(owner.operators()).containsExactly("EQ", "NE", "IN", "IS_EMPTY", "IS_NOT_EMPTY");
        assertThat(owner.sortable()).isFalse();
        assertThat(created.operators()).containsExactly(ORDERED);
        assertThat(created.sortable()).isTrue();
        assertThat(id.operators()).containsExactly(ORDERED);
    }
}
