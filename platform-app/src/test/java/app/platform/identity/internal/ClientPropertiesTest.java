package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The settings of connected applications (Sprint 31, ADR-0267 to ADR-0273), without a database: every number has a
 * safe default so the {@code local} profile starts unchanged, the platform ceilings cannot be raised past what the
 * database holds, and nonsense is refused at start.
 */
class ClientPropertiesTest {

    private static ClientProperties bound(java.util.Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("platform.identity.clients", Bindable.of(ClientProperties.class));
    }

    private static ClientProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(java.util.Map.of()))
                .bindOrCreate("platform.identity.clients", Bindable.of(ClientProperties.class));
    }

    @Test
    void nothingSetGivesTheSafeDefaults() {
        ClientProperties properties = defaults();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.tokens().defaultLife()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.tokens().min()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.tokens().max()).isEqualTo(Duration.ofMinutes(60));
        assertThat(properties.maxActiveSecrets()).isEqualTo(3);
        assertThat(properties.maxOverlap()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.maxPerOrganization()).isEqualTo(50);
        assertThat(properties.failures().perSource()).isEqualTo(20);
        assertThat(properties.failures().perClient()).isEqualTo(10);
        assertThat(properties.lastUsedInterval()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void theKillSwitchCanBeTurnedOff() {
        assertThat(bound(java.util.Map.of("platform.identity.clients.enabled", "false")).enabled()).isFalse();
    }

    @Test
    void theTokenLifeCannotGoOutsideWhatTheDatabaseHolds() {
        assertThatThrownBy(() -> new ClientProperties.Tokens(Duration.ofMinutes(15), Duration.ofMinutes(1),
                Duration.ofMinutes(60))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientProperties.Tokens(Duration.ofMinutes(15), Duration.ofMinutes(5),
                Duration.ofMinutes(61))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientProperties.Tokens(Duration.ofMinutes(4), Duration.ofMinutes(5),
                Duration.ofMinutes(60))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientProperties.Tokens(Duration.ofMinutes(30), Duration.ofMinutes(5),
                Duration.ofMinutes(20))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOverlapCannotBeLongerThanTheTwentyFourHoursTheDatabaseHolds() {
        ClientProperties ok = defaults();

        assertThatThrownBy(() -> new ClientProperties(true, ok.tokens(), 3, Duration.ofHours(25), 50, ok.failures(),
                Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientProperties(true, ok.tokens(), 3, Duration.ofHours(-1), 50, ok.failures(),
                Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsMustBePositiveAndWindowsMustHaveALength() {
        ClientProperties ok = defaults();

        assertThatThrownBy(() -> new ClientProperties(true, ok.tokens(), 0, Duration.ofHours(1), 50, ok.failures(),
                Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientProperties(true, ok.tokens(), 3, Duration.ofHours(1), 0, ok.failures(),
                Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientProperties(true, ok.tokens(), 3, Duration.ofHours(1), 50, ok.failures(),
                Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientProperties.Failures(0, Duration.ofMinutes(1), 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientProperties.Failures(5, Duration.ZERO, 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
