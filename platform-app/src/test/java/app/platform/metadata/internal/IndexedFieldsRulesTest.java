package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.SlotClass;
import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which fields may be flagged as indexed and how many (Sprint 16, ADR-0101): the type rule, the limit per kind of
 * value as a rule of the result of a change (so the order of the changes of one change set does not matter), and the
 * words of the refusal.
 */
class IndexedFieldsRulesTest {

    private static FieldDefinition text(String name, boolean indexed) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, FieldType.TEXT, false, false,
                null, new TextConfiguration(255), false, 0, indexed);
    }

    private static FieldDefinition masterDetail(String name) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, FieldType.MASTER_DETAIL, true,
                false, null, new ReferenceConfiguration("Parent__c"), false, 0, false);
    }

    private static ObjectDefinition object(List<FieldDefinition> fields) {
        List<FieldDefinition> all = new ArrayList<>();
        all.add(new FieldDefinition("Thing__c", "id", "id", "", DefinitionKind.SYSTEM, FieldType.TEXT, true, true,
                null, new TextConfiguration(36), false, 0));
        all.addAll(fields);
        return new ObjectDefinition("Thing__c", "Thing", "Things", "", DefinitionKind.CUSTOM, null, true, 0, all);
    }

    private static List<DependencyGraph.Violation> violations(ObjectDefinition... objects) {
        MetadataSnapshot snapshot = new MetadataSnapshot(List.of(objects), Map.of());
        return DependencyGraph.of(snapshot, List.of(new Dependencies.IndexedFields())).violations();
    }

    @Test
    void everyTypeIsEitherIndexableOrRefusedWithAReason() {
        Set<FieldType> refused = EnumSet.of(FieldType.LONG_TEXT, FieldType.MULTI_PICKLIST, FieldType.TIME,
                FieldType.FORMULA);
        for (FieldType type : FieldType.values()) {
            if (refused.contains(type)) {
                assertThat(SlotClass.of(type)).as(type.name()).isEmpty();
                assertThatThrownBy(() -> FieldRules.checkIndexed(type, true)).isInstanceOf(ApiException.class)
                        .satisfies(error -> assertThat(((ApiException) error).fields()).containsKey("indexed"));
            } else {
                assertThat(SlotClass.of(type)).as(type.name()).isPresent();
                assertThat(FieldRules.checkIndexed(type, true)).isTrue();
            }
            assertThat(FieldRules.checkIndexed(type, false)).as("not flagged is always fine").isFalse();
        }
    }

    @Test
    void aFormulaIsRefusedBecauseItsStoredResultIsNotCurrent() {
        assertThatThrownBy(() -> FieldRules.checkIndexed(FieldType.FORMULA, true))
                .satisfies(error -> assertThat(((ApiException) error).fields().get("indexed").get(0))
                        .contains("formula").contains("saved"));
    }

    @Test
    void eachKindHasTheNumberOfSlotsTheTableHas() {
        assertThat(SlotClass.TEXT.capacity()).isEqualTo(8);
        assertThat(SlotClass.NUMBER.capacity()).isEqualTo(4);
        assertThat(SlotClass.DATE.capacity()).isEqualTo(2);
        assertThat(SlotClass.TIMESTAMP.capacity()).isEqualTo(2);
        assertThat(SlotClass.REFERENCE.capacity()).isEqualTo(4);
        assertThat(SlotClass.BOOLEAN.capacity()).isEqualTo(2);
    }

    @Test
    void anObjectWithinTheLimitHasNoViolation() {
        List<FieldDefinition> fields = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fields.add(text("t" + i + "__c", true));
        }
        fields.add(text("plain__c", false));

        assertThat(violations(object(fields))).isEmpty();
    }

    @Test
    void oneFieldTooManyIsRefusedNamingTheKindTheLimitAndTheFieldsThatUseTheSlots() {
        List<FieldDefinition> fields = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            fields.add(text("t" + i + "__c", true));
        }

        List<DependencyGraph.Violation> found = violations(object(fields));

        assertThat(found).hasSize(1);
        String message = found.get(0).message();
        assertThat(message).contains("Thing__c has 9 indexed or unique text fields").contains("at most 8");
        for (int i = 0; i < 9; i++) {
            assertThat(message).contains("t" + i + "__c");
        }
    }

    @Test
    void theLimitIsJudgedOnTheResultSoAFieldTakenAwayInTheSameChangeMakesRoom() {
        List<FieldDefinition> before = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            before.add(text("t" + i + "__c", true));
        }
        List<FieldDefinition> after = new ArrayList<>(before.subList(1, 8));
        after.add(text("new__c", true));

        assertThat(violations(object(after))).as("one stops, another starts: eight in the result").isEmpty();
    }

    @Test
    void everyMasterDetailUsesAReferenceSlotWhetherOrNotItWasFlagged() {
        List<FieldDefinition> fields = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fields.add(masterDetail("m" + i + "__c"));
        }

        List<DependencyGraph.Violation> found = violations(object(fields));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).contains("5 indexed or unique link fields").contains("at most 4");
    }

    @Test
    void aFlaggedFieldOfATypeThatCannotBeIndexedIsAViolationOfItsOwn() {
        FieldDefinition longText = new FieldDefinition("Thing__c", "notes__c", "notes", "", DefinitionKind.CUSTOM,
                FieldType.LONG_TEXT, false, false, null, new TextConfiguration(1000), false, 0, true);
        FieldDefinition time = new FieldDefinition("Thing__c", "at__c", "at", "", DefinitionKind.CUSTOM,
                FieldType.TIME, false, false, null, new NoConfiguration(), false, 0, true);

        List<DependencyGraph.Violation> found = violations(object(List.of(longText, time)));

        assertThat(found).hasSize(2);
        assertThat(found.get(0).dependent().describe()).isEqualTo("field Thing__c.notes__c");
    }

    private static FieldDefinition uniqueText(String name) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, FieldType.TEXT, false, true,
                null, new TextConfiguration(255), false, 0, false);
    }

    @Test
    void aUniqueFieldIsIndexedByBeingUniqueAndCountsAgainstTheSlots() {
        List<FieldDefinition> fields = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fields.add(text("t" + i + "__c", true));
        }
        for (int i = 0; i < 4; i++) {
            fields.add(uniqueText("u" + i + "__c"));
        }

        List<DependencyGraph.Violation> found = violations(object(fields));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).contains("9 indexed or unique text fields").contains("at most 8")
                .contains("u0__c").contains("t0__c");
    }

    @Test
    void aFieldFlaggedBothWaysUsesOneSlot() {
        FieldDefinition both = new FieldDefinition("Thing__c", "both__c", "both", "", DefinitionKind.CUSTOM,
                FieldType.TEXT, false, true, null, new TextConfiguration(255), false, 0, true);
        List<FieldDefinition> fields = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            fields.add(text("t" + i + "__c", true));
        }
        fields.add(both);

        assertThat(violations(object(fields))).isEmpty();
    }

    @Test
    void anAutoNumberTheSystemIdentifierAndTheFieldsOfAPlatformObjectNeverTakeASlot() {
        FieldDefinition sequence = new FieldDefinition("Thing__c", "number__c", "number", "", DefinitionKind.CUSTOM,
                FieldType.AUTO_NUMBER, false, true, null,
                new app.platform.metadata.FieldConfiguration.AutoNumberConfiguration("", 1, 6), false, 0, false);
        List<FieldDefinition> fields = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fields.add(text("t" + i + "__c", true));
        }
        fields.add(sequence);
        ObjectDefinition managed = new ObjectDefinition("User", "User", "Users", "", DefinitionKind.STANDARD,
                "identity", false, 0, new ArrayList<>(List.of(uniqueText("a__c"), uniqueText("b__c"),
                        uniqueText("c__c"), uniqueText("d__c"), uniqueText("e__c"), uniqueText("f__c"),
                        uniqueText("g__c"), uniqueText("h__c"), uniqueText("i__c"))));

        assertThat(violations(object(fields), managed)).isEmpty();
    }

    @Test
    void aFieldThatIsNotFlaggedIsNeverAViolationWhateverItsType() {
        FieldDefinition longText = new FieldDefinition("Thing__c", "notes__c", "notes", "", DefinitionKind.CUSTOM,
                FieldType.LONG_TEXT, false, false, null, new TextConfiguration(1000), false, 0, false);

        assertThat(violations(object(List.of(longText)))).isEmpty();
    }
}
