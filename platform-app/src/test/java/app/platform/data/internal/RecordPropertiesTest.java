package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The settings of the record store bind from the names the guide documents, and refuse nonsense (Sprint 14). */
class RecordPropertiesTest {

    private static RecordProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("platform.data",
                RecordProperties.class);
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        RecordProperties properties = bind(Map.of());

        assertThat(properties.limits().maxRecordBytes()).isEqualTo(262_144);
        assertThat(properties.retention().enabled()).isTrue();
        assertThat(properties.retention().recycleBin()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.retention().interval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.retention().batchSize()).isEqualTo(500);
    }

    @Test
    void everySettingBindsFromItsDocumentedName() {
        RecordProperties properties = bind(Map.of(
                "platform.data.limits.max-record-bytes", "4096",
                "platform.data.retention.enabled", "false",
                "platform.data.retention.recycle-bin", "PT2S",
                "platform.data.retention.interval", "10m",
                "platform.data.retention.batch-size", "3"));

        assertThat(properties.limits().maxRecordBytes()).isEqualTo(4096);
        assertThat(properties.retention().enabled()).isFalse();
        assertThat(properties.retention().recycleBin()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retention().interval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.retention().batchSize()).isEqualTo(3);
    }

    @Test
    void nonsenseIsRefused() {
        assertThatThrownBy(() -> bind(Map.of("platform.data.limits.max-record-bytes", "10")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("platform.data.retention.recycle-bin", "PT0S")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("platform.data.retention.batch-size", "0")))
                .isInstanceOf(BindException.class);
    }
}
