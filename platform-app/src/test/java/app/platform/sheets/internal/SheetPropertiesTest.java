package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The settings of the sheets module (FA3): the defaults are the product caps, and an unreasonable value is refused. */
class SheetPropertiesTest {

    @Test
    void theDefaultsAreTheCapsTheProductOwnerDecided() {
        SheetProperties.Limits limits = SheetProperties.Limits.standard();

        assertThat(limits.maxBytes()).as("1 MB").isEqualTo(1_000_000);
        assertThat(limits.maxRows()).isEqualTo(500);
        assertThat(limits.maxColumns()).isEqualTo(100);
        assertThat(limits.maxCellChars()).isEqualTo(1_000);
        assertThat(limits.maxLabelChars()).isEqualTo(200);
        assertThat(limits.maxPicklistValues()).isEqualTo(100);
        assertThat(limits.maxMerges()).isEqualTo(2_000);
        assertThat(limits.maxDepth()).isEqualTo(12);
    }

    @Test
    void aSizeAboveTheCeilingOfTheDatabaseIsRefused() {
        assertThatThrownBy(() -> SheetProperties.Limits.ofOne(5_000_001, 500, 100, 10, 1_000, 200, 100, 2_000, 12,
                1_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-bytes");
    }

    @Test
    void everySettingHasARange() {
        int[][] bad = {
            {999, 500, 100, 10, 1_000, 200, 100, 2_000, 12, 1_000}, {1_000_000, 0, 100, 10, 1_000, 200, 100, 2_000, 12,
                1_000}, {1_000_000, 500, 0, 10, 1_000, 200, 100, 2_000, 12, 1_000}, {1_000_000, 500, 100, -1, 1_000,
                200, 100, 2_000, 12, 1_000}, {1_000_000, 500, 100, 10, 0, 200, 100, 2_000, 12, 1_000}, {1_000_000, 500,
                100, 10, 1_000, 0, 100, 2_000, 12, 1_000}, {1_000_000, 500, 100, 10, 1_000, 200, 0, 2_000, 12, 1_000},
            {1_000_000, 500, 100, 10, 1_000, 200, 100, -1, 12, 1_000}, {1_000_000, 500, 100, 10, 1_000, 200, 100,
                2_000, 3, 1_000}, {1_000_000, 500, 100, 10, 1_000, 200, 100, 2_000, 12, 0}};
        for (int[] values : bad) {
            assertThatThrownBy(() -> SheetProperties.Limits.ofOne(values[0], values[1], values[2], values[3],
                    values[4], values[5], values[6], values[7], values[8], values[9]))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("platform.sheets.limits.");
        }
    }

    @Test
    void theBodyOfARequestThatCarriesNoGridIsBounded() {
        assertThat(new SheetProperties(SheetProperties.Limits.standard(), 4096, new SheetProperties.FormatTwo(false))
                .smallBodyBytes()).isEqualTo(4096);
        assertThatThrownBy(() -> new SheetProperties(SheetProperties.Limits.standard(), 10,
                new SheetProperties.FormatTwo(false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOwnersListLetsOneApplicationMakeTheNewFormatWhileTheOthersKeepTheEarlierOne() {
        SheetProperties.FormatTwo everyOwner = new SheetProperties.FormatTwo(true);
        SheetProperties.FormatTwo oneOwner = new SheetProperties.FormatTwo(true,
                java.util.List.of("EnvironmentTemplate"));
        SheetProperties.FormatTwo off = new SheetProperties.FormatTwo(false,
                java.util.List.of("EnvironmentTemplate"));

        assertThat(everyOwner.makesFor("InspectionTemplate")).isTrue();
        assertThat(oneOwner.makesFor("EnvironmentTemplate")).isTrue();
        assertThat(oneOwner.makesFor("InspectionTemplate")).isFalse();
        assertThat(off.makesFor("EnvironmentTemplate")).as("the switch is the master").isFalse();
    }
}
