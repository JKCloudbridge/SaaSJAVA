package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The limits of saved list views (Sprint 19, ADR-0130) are bounded, so a setting cannot switch the bound off. */
class ListViewPropertiesTest {

    @Test
    void theLimitsAreWithinTheirBounds() {
        ListViewProperties properties = new ListViewProperties(50, 50);

        assertThat(properties.maxPersonalPerObject()).isEqualTo(50);
        assertThat(properties.maxSharedPerObject()).isEqualTo(50);
        assertThat(new ListViewProperties(1, 1000)).isNotNull();
    }

    @Test
    void aLimitOutsideItsBoundsRefusesToStart() {
        assertThatThrownBy(() -> new ListViewProperties(0, 50)).hasMessageContaining("max-personal-per-object");
        assertThatThrownBy(() -> new ListViewProperties(1001, 50)).hasMessageContaining("max-personal-per-object");
        assertThatThrownBy(() -> new ListViewProperties(50, 0)).hasMessageContaining("max-shared-per-object");
        assertThatThrownBy(() -> new ListViewProperties(50, 1001)).hasMessageContaining("max-shared-per-object");
    }
}
