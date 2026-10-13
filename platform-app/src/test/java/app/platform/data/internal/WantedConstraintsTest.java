package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which uniqueness constraints an object should enforce (Sprint 16A, ADR-0111): one per unique field whose copy is
 * complete, and one for the two parents of a junction whose copies are complete. No database.
 */
class WantedConstraintsTest {

    private static FieldDefinition field(String name, FieldType type, boolean unique, FieldConfiguration configuration,
            DefinitionKind kind) {
        return new FieldDefinition("Thing__c", name, name, "", kind, type, false, unique, null,
                configuration, false, 0);
    }

    private static ObjectDefinition object(FieldDefinition... fields) {
        return new ObjectDefinition("Thing__c", "Thing", "Things", "", DefinitionKind.CUSTOM, null, true, 0,
                new ArrayList<>(List.of(fields)));
    }

    private static FieldSlotStore.Row slot(String field, SlotColumn column, FieldSlotStore.State state) {
        return new FieldSlotStore.Row(UUID.randomUUID(), "Thing__c", field, column, state, null, 0, null);
    }

    private static FieldDefinition code() {
        return field("code__c", FieldType.TEXT, true, new TextConfiguration(255), DefinitionKind.CUSTOM);
    }

    private static FieldDefinition master(String name) {
        return field(name, FieldType.MASTER_DETAIL, false, new ReferenceConfiguration("Parent__c"),
                DefinitionKind.CUSTOM);
    }

    @Test
    void aUniqueFieldIsWantedOnlyOnceItsCopyIsComplete() {
        ObjectDefinition thing = object(code());

        assertThat(IndexPolicy.wantedConstraints(thing, List.of())).isEmpty();
        assertThat(IndexPolicy.wantedConstraints(thing,
                List.of(slot("code__c", SlotColumn.TEXT_3, FieldSlotStore.State.FILLING)))).isEmpty();
        assertThat(IndexPolicy.wantedConstraints(thing,
                List.of(slot("code__c", SlotColumn.TEXT_3, FieldSlotStore.State.CLEARING)))).isEmpty();

        List<IndexPolicy.Wanted> wanted = IndexPolicy.wantedConstraints(thing,
                List.of(slot("code__c", SlotColumn.TEXT_3, FieldSlotStore.State.FILLED)));
        assertThat(wanted).hasSize(1);
        assertThat(wanted.get(0).kind()).isEqualTo(UniqueStore.Kind.VALUE);
        assertThat(wanted.get(0).field()).isEqualTo("code__c");
        assertThat(wanted.get(0).key()).isEqualTo(UniqueKey.of(SlotColumn.TEXT_3));
    }

    @Test
    void aFieldThatIsNotUniqueIsNeverWantedEvenWhenItsCopyIsComplete() {
        FieldDefinition plain = field("name__c", FieldType.TEXT, false, new TextConfiguration(255),
                DefinitionKind.CUSTOM);

        assertThat(IndexPolicy.wantedConstraints(object(plain),
                List.of(slot("name__c", SlotColumn.TEXT_1, FieldSlotStore.State.FILLED)))).isEmpty();
    }

    @Test
    void anAutoNumberTheSystemIdentifierAndAnUncheckableSetupAreNeverWanted() {
        FieldDefinition sequence = field("number__c", FieldType.AUTO_NUMBER, true,
                new AutoNumberConfiguration("", 1, 6), DefinitionKind.CUSTOM);
        FieldDefinition identifier = field("id", FieldType.TEXT, true, new TextConfiguration(36),
                DefinitionKind.SYSTEM);
        FieldDefinition wide = field("big__c", FieldType.NUMBER, true, new NumberConfiguration(18),
                DefinitionKind.CUSTOM);
        FieldDefinition address = field("site__c", FieldType.URL, true, new TextConfiguration(2048),
                DefinitionKind.CUSTOM);

        assertThat(IndexPolicy.wantedConstraints(object(sequence, identifier, wide, address),
                List.of(slot("number__c", SlotColumn.TEXT_1, FieldSlotStore.State.FILLED),
                        slot("id", SlotColumn.TEXT_2, FieldSlotStore.State.FILLED),
                        slot("big__c", SlotColumn.NUM_1, FieldSlotStore.State.FILLED),
                        slot("site__c", SlotColumn.TEXT_3, FieldSlotStore.State.FILLED)))).isEmpty();
    }

    @Test
    void aNumberOfFifteenDigitsIsWanted() {
        FieldDefinition narrow = field("qty__c", FieldType.NUMBER, true, new NumberConfiguration(15),
                DefinitionKind.CUSTOM);

        assertThat(IndexPolicy.wantedConstraints(object(narrow),
                List.of(slot("qty__c", SlotColumn.NUM_2, FieldSlotStore.State.FILLED)))).hasSize(1);
    }

    @Test
    void theTwoParentsOfAJunctionAreWantedAsAPairOnceBothCopiesAreComplete() {
        ObjectDefinition junction = object(master("left__c"), master("right__c"));
        FieldSlotStore.Row left = slot("left__c", SlotColumn.REF_3, FieldSlotStore.State.FILLED);

        assertThat(IndexPolicy.wantedConstraints(junction, List.of(left))).isEmpty();
        assertThat(IndexPolicy.wantedConstraints(junction,
                List.of(left, slot("right__c", SlotColumn.REF_1, FieldSlotStore.State.FILLING)))).isEmpty();

        List<IndexPolicy.Wanted> wanted = IndexPolicy.wantedConstraints(junction,
                List.of(left, slot("right__c", SlotColumn.REF_1, FieldSlotStore.State.FILLED)));
        assertThat(wanted).hasSize(1);
        assertThat(wanted.get(0).kind()).isEqualTo(UniqueStore.Kind.PAIR);
        assertThat(wanted.get(0).field()).isEqualTo("left__c");
        assertThat(wanted.get(0).partner()).isEqualTo("right__c");
        assertThat(wanted.get(0).key()).isEqualTo(UniqueKey.pair(SlotColumn.REF_1, SlotColumn.REF_3).orElseThrow());
    }

    @Test
    void anObjectWithOneParentIsNotAJunction() {
        assertThat(IndexPolicy.wantedConstraints(object(master("a__c")),
                List.of(slot("a__c", SlotColumn.REF_1, FieldSlotStore.State.FILLED)))).isEmpty();
    }

    @Test
    void aWantedConstraintMatchesTheRowThatEnforcesItAndNoOther() {
        IndexPolicy.Wanted wanted = new IndexPolicy.Wanted(UniqueStore.Kind.VALUE, "code__c", null,
                UniqueKey.of(SlotColumn.TEXT_3));
        UniqueStore.Row same = new UniqueStore.Row(UUID.randomUUID(), "Thing__c", UniqueStore.Kind.VALUE, "code__c",
                null, SlotColumn.TEXT_3.ordinal(), UniqueStore.State.ENFORCED, null, 0, 0);
        UniqueStore.Row otherSlot = new UniqueStore.Row(UUID.randomUUID(), "Thing__c", UniqueStore.Kind.VALUE,
                "code__c", null, SlotColumn.TEXT_4.ordinal(), UniqueStore.State.ENFORCED, null, 0, 0);

        assertThat(wanted.matches(same)).isTrue();
        assertThat(wanted.matches(otherSlot)).as("the field moved to another slot: the old row stops").isFalse();
    }
}
