package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platformapi.ApiException;
import app.platformapi.PicklistSubset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a record type may offer and allow (ADR-0064). */
class RecordTypeRulesTest {

    private static FieldDefinition field(String name, FieldType type, DefinitionKind kind,
            FieldConfiguration configuration) {
        return new FieldDefinition("Deal__c", name, name, "", kind, type, false, false, null, configuration, false, 0);
    }

    private static final ObjectDefinition DEAL = new ObjectDefinition("Deal__c", "Deal", "Deals", "",
            DefinitionKind.CUSTOM, null, true, 0, List.of(
                    field("id", FieldType.TEXT, DefinitionKind.SYSTEM, new TextConfiguration(36)),
                    field("code__c", FieldType.TEXT, DefinitionKind.CUSTOM, new TextConfiguration(10)),
                    field("done__c", FieldType.BOOLEAN, DefinitionKind.CUSTOM, new NoConfiguration()),
                    field("stage__c", FieldType.PICKLIST, DefinitionKind.CUSTOM, new PicklistConfiguration(List.of(
                            new PicklistValue("Open", "Open", true), new PicklistValue("Old", "Old", false),
                            new PicklistValue("Won", "Won", true))))));

    @Test
    void noListMeansEveryFieldAndTheDefaultsAreActiveAndNotDefault() {
        RecordTypeRules.Checked checked = RecordTypeRules.check(DEAL, null, null, null, null);

        assertThat(checked.active()).isTrue();
        assertThat(checked.defaultType()).isFalse();
        assertThat(checked.allFields()).isTrue();
        assertThat(checked.fields()).isEmpty();
        assertThat(checked.picklists()).isEmpty();
    }

    @Test
    void anEmptyListMeansEveryFieldToo() {
        assertThat(RecordTypeRules.check(DEAL, true, false, List.of(), List.of()).allFields()).isTrue();
    }

    @Test
    void aListOffersOnlyThoseFieldsAndTheSystemFieldsAreAlwaysThereSoNotKept() {
        RecordTypeRules.Checked checked = RecordTypeRules.check(DEAL, true, false,
                List.of("code__c", "id", "code__c", "done__c"), null);

        assertThat(checked.allFields()).isFalse();
        assertThat(checked.fields()).containsExactly("code__c", "done__c");
    }

    @Test
    void aSubsetKeepsOnlyActiveValuesOfAnAvailablePicklistInTheOrderGiven() {
        RecordTypeRules.Checked checked = RecordTypeRules.check(DEAL, true, false, List.of("stage__c"),
                List.of(new PicklistSubset("stage__c", List.of("Won", "Open", "Won"))));

        assertThat(checked.picklists()).containsEntry("stage__c", List.of("Won", "Open"));
    }

    @Test
    void everyProblemIsToldTogetherAndTheValueIsNotRepeated() {
        assertThatThrownBy(() -> RecordTypeRules.check(DEAL, false, true, List.of("ghost__c"), List.of(
                new PicklistSubset("stage__c", List.of("Old")), new PicklistSubset("code__c", List.of("x")))))
                .isInstanceOf(ApiException.class).satisfies(e -> {
                    ApiException refused = (ApiException) e;
                    assertThat(refused.fields()).containsOnlyKeys("defaultType", "availableFields",
                            "picklistSubsets");
                    assertThat(refused.fields().toString()).doesNotContain("Old");
                });
    }

    @Test
    void aPicklistMustBeAvailableNamedOnceAndKeepAtLeastOneValue() {
        List<String> messages = List.of(
                problem(List.of("code__c"), List.of(new PicklistSubset("stage__c", List.of("Open")))),
                problem(null, List.of(new PicklistSubset("stage__c", List.of("Open")),
                        new PicklistSubset("stage__c", List.of("Won")))),
                problem(null, List.of(new PicklistSubset("stage__c", List.of("Ghost")))));

        assertThat(messages).containsExactly("A picklist in the list is not one of the available fields.",
                "A picklist is named twice.",
                "A picklist in the list must allow at least one value.");
    }

    private static String problem(List<String> fields, List<PicklistSubset> subsets) {
        ApiException refused = assertThrows(ApiException.class,
                () -> RecordTypeRules.check(DEAL, true, false, fields, subsets));
        List<String> texts = refused.fields().get("picklistSubsets");
        return texts.get(texts.size() - 1);
    }

    @Test
    void anObjectWhoseRecordsBelongToThePlatformHasNoRecordTypes() {
        ObjectDefinition managed = new ObjectDefinition("User", "User", "Users", "", DefinitionKind.STANDARD,
                "identity", true, 0, List.of());

        assertThat(RecordTypeRules.allowedOn(managed)).isFalse();
        assertThat(RecordTypeRules.allowedOn(DEAL)).isTrue();
    }
}
