package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldType;
import app.platform.metadata.PicklistValue;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** What is stored for a field's settings reads back exactly, and an old row stays readable (ADR-0060). */
class ConfigCodecTest {

    private final ConfigCodec codec = new ConfigCodec();

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    void everyConfigurationReadsBackExactlyAsItWasWritten(FieldType type, FieldConfiguration configuration) {
        assertThat(codec.read(type, codec.write(configuration))).isEqualTo(configuration);
    }

    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of(FieldType.TEXT, new TextConfiguration(120)),
                Arguments.of(FieldType.LONG_TEXT, new TextConfiguration(5000)),
                Arguments.of(FieldType.NUMBER, new NumberConfiguration(9)),
                Arguments.of(FieldType.DECIMAL, new DecimalConfiguration(12, 4)),
                Arguments.of(FieldType.CURRENCY, new DecimalConfiguration(18, 2)),
                Arguments.of(FieldType.PERCENT, new DecimalConfiguration(5, 2)),
                Arguments.of(FieldType.BOOLEAN, new NoConfiguration()),
                Arguments.of(FieldType.PICKLIST, new PicklistConfiguration(List.of(
                        new PicklistValue("Red", "Red colour", true), new PicklistValue("Old", "Old", false)))),
                Arguments.of(FieldType.MULTI_PICKLIST, new PicklistConfiguration(List.of(
                        new PicklistValue("A", "A \"quoted\" label", true)))),
                Arguments.of(FieldType.LOOKUP, new ReferenceConfiguration("Account")),
                Arguments.of(FieldType.MASTER_DETAIL, new ReferenceConfiguration("Employee__c")),
                Arguments.of(FieldType.FORMULA, new FormulaConfiguration("a + \"b\"\n* 2", FieldType.NUMBER)),
                Arguments.of(FieldType.AUTO_NUMBER, new AutoNumberConfiguration("INV-", 100, 5)));
    }

    @Test
    void aMissingSettingTakesTheDefaultOfTheType() {
        assertThat(codec.read(FieldType.TEXT, "{}")).isEqualTo(new TextConfiguration(255));
        assertThat(codec.read(FieldType.DECIMAL, null)).isEqualTo(new DecimalConfiguration(18, 2));
        assertThat(codec.read(FieldType.AUTO_NUMBER, ""))
                .isEqualTo(new AutoNumberConfiguration("", 1, 6));
    }

    @Test
    void aKeyThatALaterReleaseAddedIsIgnored() {
        assertThat(codec.read(FieldType.NUMBER, "{\"digits\": 7, \"somethingNew\": true}"))
                .isEqualTo(new NumberConfiguration(7));
    }

    @Test
    void theStoredFormIsSmallAndNamedLikeTheSettings() {
        assertThat(codec.write(new DecimalConfiguration(10, 3))).isEqualTo("{\"precision\":10,\"scale\":3}");
        assertThat(codec.write(new NoConfiguration())).isEqualTo("{}");
    }
}
