package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.SettingSpec;
import app.platform.integration.adapter.SettingSpec.Choice;
import app.platform.integration.adapter.rest.RestConnector;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Settings checked against a connector type's schema, the fingerprint of a definition and the stable reasons. */
class SettingsAndReasonsTest {

    private static final ConnectorDescription REST = new RestConnector().describe();

    @Test
    void defaultsAreFilledInAndTheValuesAreNormalized() {
        SettingsValidator.Result result = SettingsValidator.validate(REST, Map.of("timeoutSeconds", " 07 "));

        assertThat(result.ok()).isTrue();
        assertThat(result.values()).containsEntry("healthPath", "/").containsEntry("probeMethod", "GET")
                .containsEntry("expect", "success").containsEntry("timeoutSeconds", "7");
    }

    @Test
    void everyKindOfWrongValueIsRefusedAtItsPlaceWithoutRepeatingIt() {
        Map<String, String> wrong = Map.of("healthPath", "../secret", "probeMethod", "DELETE", "expect", "anything",
                "timeoutSeconds", "99");

        SettingsValidator.Result result = SettingsValidator.validate(REST, wrong);

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).containsOnlyKeys("settings.healthPath", "settings.probeMethod",
                "settings.expect", "settings.timeoutSeconds");
        result.problems().values().forEach(sentences -> sentences.forEach(sentence -> assertThat(sentence)
                .doesNotContain("../secret").doesNotContain("DELETE").doesNotContain("anything")
                .doesNotContain("99 ")));
        assertThat(SettingsValidator.validate(REST, Map.of("timeoutSeconds", "five")).ok()).isFalse();
        assertThat(SettingsValidator.validate(REST, Map.of("timeoutSeconds", "0")).ok()).isFalse();
        assertThat(SettingsValidator.validate(REST, Map.of("timeoutSeconds", "10")).ok()).isTrue();
    }

    @Test
    void aSettingTheTypeDoesNotHaveIsRefusedWithoutNamingIt() {
        SettingsValidator.Result result = SettingsValidator.validate(REST, Map.of("script", "run()"));

        assertThat(result.problems()).containsOnlyKeys("settings");
        assertThat(result.problems().get("settings").toString()).doesNotContain("script").doesNotContain("run");
    }

    @Test
    void aRequiredSettingWithNoDefaultNeedsAValueAndBooleansAndTextAreChecked() {
        ConnectorDescription type = new ConnectorDescription("sample-two", "Sample two", "Another.", 1,
                List.of(AuthKind.NONE), List.of(
                        new SettingSpec("region", "Region", "Where.", SettingSpec.Kind.TEXT, true, null, List.of(),
                                null, null),
                        new SettingSpec("strict", "Strict", "Yes or no.", SettingSpec.Kind.BOOLEAN, false, "false",
                                List.of(), null, null)),
                List.of());

        assertThat(SettingsValidator.validate(type, Map.of()).problems()).containsOnlyKeys("settings.region");
        assertThat(SettingsValidator.validate(type, Map.of("region", "eu west-1")).ok()).isTrue();
        assertThat(SettingsValidator.validate(type, Map.of("region", "eu<script>")).ok()).isFalse();
        assertThat(SettingsValidator.validate(type, Map.of("region", "eu", "strict", "maybe")).ok()).isFalse();
        assertThat(SettingsValidator.validate(type, Map.of("region", "eu", "strict", "true")).values())
                .containsEntry("strict", "true");
    }

    @Test
    void aBrokenDescriptionIsRefusedWhenItIsMade() {
        assertThatThrownBy(() -> new SettingSpec("a", "A", "h", SettingSpec.Kind.CHOICE, false, "x", List.of(),
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingSpec.choice("a", "A", "h", "z", List.of(new Choice("x", "X"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettingSpec("Bad Key", "A", "h", SettingSpec.Kind.PATH, false, null, List.of(),
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectorDescription("x", "X", "d", 1, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectorDescription("X Y", "X", "d", 1, List.of(AuthKind.NONE), List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectorDescription("x", "X", "d", 1, List.of(AuthKind.NONE),
                List.of(SettingSpec.path("p", "P", "h", "/"), SettingSpec.path("p", "P", "h", "/")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFingerprintChangesWithEveryPartThatDecidesWhetherATestStillCountsAndWithNothingElse() {
        Map<String, String> settings = Map.of("a", "1", "b", "2");
        String base = Fingerprints.of("rest", 1, "https://x.example.com", "BEARER_TOKEN", null, "key-1", settings);

        assertThat(base).matches("[0-9a-f]{64}");
        assertThat(Fingerprints.of("rest", 1, "https://x.example.com", "BEARER_TOKEN", null, "key-1",
                Map.of("b", "2", "a", "1"))).as("order of settings").isEqualTo(base);
        assertThat(Fingerprints.of("other", 1, "https://x.example.com", "BEARER_TOKEN", null, "key-1", settings))
                .isNotEqualTo(base);
        assertThat(Fingerprints.of("rest", 2, "https://x.example.com", "BEARER_TOKEN", null, "key-1", settings))
                .isNotEqualTo(base);
        assertThat(Fingerprints.of("rest", 1, "https://y.example.com", "BEARER_TOKEN", null, "key-1", settings))
                .isNotEqualTo(base);
        assertThat(Fingerprints.of("rest", 1, "https://x.example.com", "BASIC", null, "key-1", settings))
                .isNotEqualTo(base);
        assertThat(Fingerprints.of("rest", 1, "https://x.example.com", "BEARER_TOKEN", null, "key-2", settings))
                .isNotEqualTo(base);
        assertThat(Fingerprints.of("rest", 1, "https://x.example.com", "BEARER_TOKEN", null, "key-1",
                Map.of("a", "1", "b", "3"))).isNotEqualTo(base);
        assertThat(Fingerprints.of("rest", 1, "https://x.example.com", "BEARER_TOKEN", "X-Key", "key-1", settings))
                .isNotEqualTo(base);
    }

    @Test
    void twoDifferentDefinitionsCannotWriteTheSameBytes() {
        assertThat(Fingerprints.of("a", 1, "bc", "NONE", null, null, Map.of()))
                .isNotEqualTo(Fingerprints.of("ab", 1, "c", "NONE", null, null, Map.of()));
        assertThat(Fingerprints.of("rest", 1, "u", "NONE", null, null, Map.of("k", "v;1:x")))
                .isNotEqualTo(Fingerprints.of("rest", 1, "u", "NONE", null, null, Map.of("k", "v", "1", "x")));
    }

    @Test
    void everyReasonHasAStableKeyTheDatabaseAcceptsAndFixedWords() {
        Pattern database = Pattern.compile("[a-z_]{1,40}");
        for (TestReason reason : TestReason.values()) {
            assertThat(reason.key()).matches(database);
            assertThat(reason.words()).isNotBlank();
            assertThat(TestReason.ofKey(reason.key())).isEqualTo(reason);
        }
        assertThat(TestReason.values()).extracting(TestReason::key).doesNotHaveDuplicates();
        assertThat(TestReason.values()).filteredOn(TestReason::passed).containsExactly(TestReason.OK);
        assertThat(TestReason.ofKey("a_reason_of_a_later_release")).isEqualTo(TestReason.UNEXPECTED);
    }
}
