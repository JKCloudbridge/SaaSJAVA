package app.platform.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TenancyPropertiesTest {

    @Test
    void theBaseDomainIsNormalized() {
        assertThat(new TenancyProperties("  Platform.Example.Test ", false).baseDomain())
                .isEqualTo("platform.example.test");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "has space.test", "http://example.test", "example.test/", "-bad.test", "bad-.test",
        "a..b", ".example.test", "example.test:8080", "ex_ample.test"})
    void aMissingOrMalformedBaseDomainStopsTheStartUp(String value) {
        assertThatThrownBy(() -> new TenancyProperties(value, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theErrorNamesTheSettingButNeverTheValue() {
        assertThatThrownBy(() -> new TenancyProperties("bad value", false))
                .hasMessageContaining("base-domain").hasMessageNotContaining("bad value");
    }
}
