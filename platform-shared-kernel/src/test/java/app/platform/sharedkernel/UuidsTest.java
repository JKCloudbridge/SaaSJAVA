package app.platform.sharedkernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidsTest {

    @Test
    void generatedIdentifiersAreVersion7WithStandardVariant() {
        UUID id = Uuids.v7();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(Uuids.isV7(id)).isTrue();
    }

    @Test
    void identifiersAreStrictlyIncreasingAndUniqueEvenWithinOneMillisecond() {
        Clock frozen = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        UuidV7Generator generator = new UuidV7Generator(frozen, new Random(7));

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            ids.add(generator.next());
        }

        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
        for (int i = 1; i < ids.size(); i++) {
            assertThat(compare(ids.get(i - 1), ids.get(i))).as("position %d", i).isNegative();
        }
    }

    @Test
    void identifiersStayIncreasingWhenTheClockStepsBackwards() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:10Z"));
        UuidV7Generator generator = new UuidV7Generator(clock, new Random(1));

        UUID before = generator.next();
        clock.set(Instant.parse("2026-01-01T00:00:05Z"));
        UUID after = generator.next();

        assertThat(compare(before, after)).isNegative();
    }

    @Test
    void embeddedTimestampMatchesTheClock() {
        Instant now = Instant.parse("2026-03-04T05:06:07.123Z");
        UuidV7Generator generator = new UuidV7Generator(Clock.fixed(now, ZoneOffset.UTC), new Random(3));

        assertThat(Uuids.timestampOf(generator.next())).isEqualTo(now);
    }

    @Test
    void timestampOfRejectsOtherVersions() {
        UUID random = UUID.randomUUID();

        assertThatThrownBy(() -> Uuids.timestampOf(random)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemActorIsTheAllZeroIdentifierAndNeverVersion7() {
        assertThat(ActorId.SYSTEM.value()).isEqualTo(new UUID(0L, 0L));
        assertThat(Uuids.isV7(ActorId.SYSTEM.value())).isFalse();
    }

    /** Unsigned comparison of both halves, which is the order in which PostgreSQL sorts uuid values. */
    private static int compare(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant value) {
            this.now = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
