package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.SlotClass;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The list of uniqueness constraints of the record table (Sprint 16A, ADR-0111): one per slot column and one per pair
 * of link slots, numbered as the bits of {@code unique_mask} and named as the unique indexes of migration V043. A
 * change here without a migration (or the other way round) is caught by {@code UniqueFieldsIT}.
 */
class UniqueKeyTest {

    @Test
    void thereIsOneConstraintPerSlotAndOnePerPairOfLinkSlotsNumberedWithoutGaps() {
        List<UniqueKey> every = UniqueKey.every();

        assertThat(every).hasSize(SlotColumn.values().length + 6);
        for (int bit = 0; bit < every.size(); bit++) {
            assertThat(every.get(bit).bit()).isEqualTo(bit);
        }
        assertThat(UniqueKey.FIRST_PAIR_BIT).isEqualTo(22);
    }

    @Test
    void theBitOfASlotIsItsPositionAndTheMaskIsThatBitSet() {
        for (SlotColumn slot : SlotColumn.values()) {
            UniqueKey key = UniqueKey.of(slot);

            assertThat(key.bit()).isEqualTo(slot.ordinal());
            assertThat(key.mask()).isEqualTo(1 << slot.ordinal());
            assertThat(key.columns()).containsExactly(slot);
            assertThat(key.isPair()).isFalse();
        }
    }

    @Test
    void theMasksAreDistinctAndFitTheCheckOfTheColumn() {
        Set<Integer> masks = UniqueKey.every().stream().map(UniqueKey::mask).collect(Collectors.toSet());

        assertThat(masks).hasSize(UniqueKey.every().size());
        assertThat(UniqueKey.every().stream().mapToLong(UniqueKey::mask).sum())
                .as("every bit together stays below the limit of the check on the column").isLessThan(268_435_456L);
    }

    @Test
    void aPairIsTwoLinkSlotsInEitherOrderAndNothingElse() {
        assertThat(UniqueKey.pair(SlotColumn.REF_1, SlotColumn.REF_2).orElseThrow().bit()).isEqualTo(22);
        assertThat(UniqueKey.pair(SlotColumn.REF_2, SlotColumn.REF_1).orElseThrow().bit()).isEqualTo(22);
        assertThat(UniqueKey.pair(SlotColumn.REF_3, SlotColumn.REF_4).orElseThrow().bit()).isEqualTo(27);
        assertThat(UniqueKey.pair(SlotColumn.REF_1, SlotColumn.REF_1)).isEmpty();
        assertThat(UniqueKey.pair(SlotColumn.TEXT_1, SlotColumn.TEXT_2)).isEmpty();
        assertThat(UniqueKey.pair(SlotColumn.REF_1, SlotColumn.TEXT_1)).isEmpty();
        assertThat(UniqueKey.every().stream().filter(UniqueKey::isPair)
                .allMatch(key -> key.columns().stream().allMatch(slot -> slot.slotClass() == SlotClass.REFERENCE)))
                .isTrue();
    }

    @Test
    void theIndexNamesFollowTheMigrationAndFindTheirConstraintBack() {
        assertThat(UniqueKey.of(SlotColumn.TEXT_1).indexName()).isEqualTo("object_record_u_s_text_1");
        assertThat(UniqueKey.of(SlotColumn.BOOL_2).indexName()).isEqualTo("object_record_u_s_bool_2");
        assertThat(UniqueKey.pair(SlotColumn.REF_1, SlotColumn.REF_3).orElseThrow().indexName())
                .isEqualTo("object_record_u_p_ref_1_ref_3");
        for (UniqueKey key : UniqueKey.every()) {
            assertThat(UniqueKey.ofIndex(key.indexName())).contains(key);
            assertThat(UniqueKey.ofBit(key.bit())).contains(key);
        }
        assertThat(UniqueKey.ofIndex("object_record_pkey")).isEmpty();
        assertThat(UniqueKey.ofBit(-1)).isEmpty();
        assertThat(UniqueKey.ofBit(28)).isEmpty();
    }

    @Test
    void emptyingASlotSwitchesOffItsOwnBitAndTheBitsOfEveryPairItIsPartOf() {
        int mask = UniqueKey.maskTouching(SlotColumn.REF_2);

        assertThat(mask & UniqueKey.of(SlotColumn.REF_2).mask()).isNotZero();
        assertThat(mask & UniqueKey.pair(SlotColumn.REF_1, SlotColumn.REF_2).orElseThrow().mask()).isNotZero();
        assertThat(mask & UniqueKey.pair(SlotColumn.REF_2, SlotColumn.REF_3).orElseThrow().mask()).isNotZero();
        assertThat(mask & UniqueKey.pair(SlotColumn.REF_2, SlotColumn.REF_4).orElseThrow().mask()).isNotZero();
        assertThat(mask & UniqueKey.pair(SlotColumn.REF_1, SlotColumn.REF_3).orElseThrow().mask()).isZero();
        assertThat(mask & UniqueKey.of(SlotColumn.REF_1).mask()).isZero();
        assertThat(UniqueKey.maskTouching(SlotColumn.TEXT_3)).isEqualTo(UniqueKey.of(SlotColumn.TEXT_3).mask());
    }
}
