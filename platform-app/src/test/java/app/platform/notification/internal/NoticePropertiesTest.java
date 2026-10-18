package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NoticePropertiesTest {

    private static NoticeProperties.Retention retention(Duration read, Duration unread, Duration floor) {
        return new NoticeProperties.Retention(true, read, unread, floor, Duration.ofHours(1), 1000);
    }

    @Test
    void theDefaultsAreThirtyAndOneHundredAndEightyDaysWithAFloorOfSeven() {
        NoticeProperties.Retention defaults = retention(Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(7));

        assertThat(defaults.readCutoff()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.unreadCutoff()).isEqualTo(Duration.ofDays(180));
    }

    @Test
    void nothingYoungerThanTheFloorIsEverRemovedWhateverTheOtherTwoSay() {
        NoticeProperties.Retention eager = retention(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(7));

        assertThat(eager.readCutoff()).isEqualTo(Duration.ofDays(7));
        assertThat(eager.unreadCutoff()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void theFloorItselfCannotBeSetBelowADay() {
        assertThatThrownBy(() -> retention(Duration.ofDays(30), Duration.ofDays(180), Duration.ofHours(23)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoticeProperties.Retention(true, Duration.ofDays(30), Duration.ofDays(180),
                Duration.ofDays(7), Duration.ofHours(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePagesAreBounded() {
        NoticeProperties.Retention retention = retention(Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(7));

        assertThat(new NoticeProperties(30, 100, retention).pageSize()).isEqualTo(30);
        assertThatThrownBy(() -> new NoticeProperties(0, 100, retention)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoticeProperties(50, 40, retention)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoticeProperties(30, 500, retention)).isInstanceOf(IllegalArgumentException.class);
    }
}
