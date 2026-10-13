package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.SlotCopy;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a write carries for uniqueness (Sprint 16A, ADR-0111): the bit of each constraint a record takes part in, set
 * exactly when the record has a copy in every column of the constraint, where a refusal is answered, and which values
 * the pipeline must ask the storage about (only the ones the write changes). No database: the lists the write reads
 * under the lock are given.
 */
class RecordSlotsUniquenessTest {

    private static final String LEFT = "left__c";
    private static final String RIGHT = "right__c";
    private final RecordSlots slots = new RecordSlots(null, null);

    private static FieldDefinition text(String name, boolean unique) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, FieldType.TEXT, false, unique,
                null, new TextConfiguration(255), false, 0);
    }

    private static FieldDefinition master(String name) {
        return new FieldDefinition("Thing__c", name, name, "", DefinitionKind.CUSTOM, FieldType.MASTER_DETAIL, true,
                false, null, new ReferenceConfiguration("Parent__c"), false, 0);
    }

    private static ObjectDefinition object() {
        return new ObjectDefinition("Thing__c", "Thing", "Things", "", DefinitionKind.CUSTOM, null, true, 0,
                new ArrayList<>(List.of(text("code__c", true), text("name__c", false), master(LEFT), master(RIGHT))));
    }

    private static FieldSlotStore.Row copyOf(String field, SlotColumn slot, FieldSlotStore.State state) {
        return new FieldSlotStore.Row(UUID.randomUUID(), "Thing__c", field, slot, state, null, 0, null);
    }

    private static UniqueStore.Row value(String field, SlotColumn slot, UniqueStore.State state) {
        return new UniqueStore.Row(UUID.randomUUID(), "Thing__c", UniqueStore.Kind.VALUE, field, null,
                UniqueKey.of(slot).bit(), state, null, 0, 0);
    }

    private static UniqueStore.Row pair(UniqueStore.State state) {
        return new UniqueStore.Row(UUID.randomUUID(), "Thing__c", UniqueStore.Kind.PAIR, LEFT, RIGHT,
                UniqueKey.pair(SlotColumn.REF_1, SlotColumn.REF_2).orElseThrow().bit(), state, null, 0, 0);
    }

    private static RecordSlots.Active codeIsUnique(UniqueStore.State state) {
        return new RecordSlots.Active(List.of(copyOf("code__c", SlotColumn.TEXT_1, FieldSlotStore.State.FILLED),
                copyOf("name__c", SlotColumn.TEXT_2, FieldSlotStore.State.FILLED)),
                List.of(value("code__c", SlotColumn.TEXT_1, state)));
    }

    private static RecordSlots.Active junction() {
        return new RecordSlots.Active(List.of(copyOf(LEFT, SlotColumn.REF_1, FieldSlotStore.State.FILLED),
                copyOf(RIGHT, SlotColumn.REF_2, FieldSlotStore.State.FILLED)), List.of(pair(
                UniqueStore.State.ENFORCED)));
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    @Test
    void aRecordWithAValueCarriesTheBitOfTheConstraintAndSaysWhereARefusalIsAnswered() {
        SlotCopy copy = slots.copyIndexed(codeIsUnique(UniqueStore.State.ENFORCED), object(),
                values("code__c", "A-1", "name__c", "n"));

        assertThat(copy.uniqueMask()).isEqualTo(UniqueKey.of(SlotColumn.TEXT_1).mask());
        assertThat(copy.uniquePlaces()).containsEntry(UniqueKey.of(SlotColumn.TEXT_1).bit(), "code__c");
        assertThat(copy.columns()).containsEntry("s_text_1", "A-1").containsEntry("s_text_2", "n");
    }

    @Test
    void aRecordWithoutAValueTakesNoPartInTheConstraint() {
        SlotCopy copy = slots.copyIndexed(codeIsUnique(UniqueStore.State.ENFORCED), object(),
                values("name__c", "n"));

        assertThat(copy.uniqueMask()).isZero();
        assertThat(copy.uniquePlaces()).isEmpty();
    }

    @Test
    void aConstraintThatIsStillBeingSwitchedOnIsCarriedFromTheFirstWrite() {
        for (UniqueStore.State state : UniqueStore.State.values()) {
            SlotCopy copy = slots.copyIndexed(codeIsUnique(state), object(), values("code__c", "x"));

            assertThat(copy.uniqueMask()).as(state.name()).isEqualTo(UniqueKey.of(SlotColumn.TEXT_1).mask());
        }
    }

    @Test
    void anObjectWithoutConstraintsCarriesNoMask() {
        RecordSlots.Active plain = new RecordSlots.Active(
                List.of(copyOf("code__c", SlotColumn.TEXT_1, FieldSlotStore.State.FILLED)), List.of());

        assertThat(slots.copyIndexed(plain, object(), values("code__c", "x")).uniqueMask()).isZero();
        assertThat(slots.probes(plain, object(), values("code__c", "x"), Map.of())).isEmpty();
        assertThat(slots.copyIndexed(new RecordSlots.Active(List.of(), List.of()), object(), values()))
                .isEqualTo(SlotCopy.none());
    }

    @Test
    void aNewRecordAsksAboutItsValueAndOneWithoutAValueAsksNothing() {
        RecordSlots.Active active = codeIsUnique(UniqueStore.State.ENFORCED);

        List<RecordSlots.Probe> asked = slots.probes(active, object(), values("code__c", "A-1"), Map.of());

        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).values()).containsExactly("A-1");
        assertThat(asked.get(0).place()).isEqualTo("code__c");
        assertThat(asked.get(0).pair()).isFalse();
        assertThat(slots.probes(active, object(), values("name__c", "n"), Map.of())).isEmpty();
    }

    @Test
    void aChangeThatLeavesTheValueAloneAsksNothingAndAChangedValueAsksAboutTheNewOne() {
        RecordSlots.Active active = codeIsUnique(UniqueStore.State.ENFORCED);
        Map<String, Object> held = values("code__c", "A-1", "name__c", "n");

        assertThat(slots.probes(active, object(), values("code__c", "A-1", "name__c", "other"), held))
                .as("a record may keep its own value").isEmpty();
        List<RecordSlots.Probe> asked = slots.probes(active, object(), values("code__c", "A-2", "name__c", "n"),
                held);
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).values()).containsExactly("A-2");
        assertThat(slots.probes(active, object(), values("name__c", "n"), held))
                .as("a value that is cleared needs no check").isEmpty();
    }

    @Test
    void theTwoParentsOfAJunctionAreOneConstraintAnsweredAtTheSecondParent() {
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        Map<String, Object> pairValues = values(LEFT, left.toString(), RIGHT, right.toString());

        SlotCopy copy = slots.copyIndexed(junction(), object(), pairValues);
        List<RecordSlots.Probe> asked = slots.probes(junction(), object(), pairValues, Map.of());

        assertThat(copy.uniqueMask()).isEqualTo(UniqueKey.pair(SlotColumn.REF_1, SlotColumn.REF_2).orElseThrow()
                .mask());
        assertThat(copy.uniquePlaces().values()).containsExactly(RIGHT);
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).pair()).isTrue();
        assertThat(asked.get(0).values()).containsExactly(left.toString(), right.toString());
        assertThat(slots.copyIndexed(junction(), object(), values(LEFT, left.toString())).uniqueMask())
                .as("a pair needs both parents").isZero();
    }

    @Test
    void movingOneParentAsksAboutTheNewPairAndKeepingBothAsksNothing() {
        UUID left = UUID.randomUUID();
        Map<String, Object> held = values(LEFT, left.toString(), RIGHT, UUID.randomUUID().toString());
        Map<String, Object> moved = new HashMap<>(held);
        moved.put(RIGHT, UUID.randomUUID().toString());

        assertThat(slots.probes(junction(), object(), held, held)).isEmpty();
        assertThat(slots.probes(junction(), object(), moved, held)).hasSize(1);
    }

    @Test
    void theValueTextIsWhatThePayloadGivesSoAWriteAndABackfillAgree() {
        assertThat(RecordSlots.textOf(new java.math.BigDecimal("1.50"))).isEqualTo("1.50");
        assertThat(RecordSlots.textOf(true)).isEqualTo("true");
        assertThat(RecordSlots.textOf(null)).isNull();
    }
}
