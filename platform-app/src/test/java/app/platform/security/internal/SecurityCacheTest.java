package app.platform.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The rules of the cache on its own, without a database (ADR-0053): an answer is served only for the version it was
 * computed under, a transaction that has written never uses or fills the cache, the size is bounded, an older answer
 * never replaces a newer one, and switched off it keeps nothing.
 */
class SecurityCacheTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static SecurityCache.Key key(UUID membership) {
        return new SecurityCache.Key(TENANT, membership, SecurityCache.Kind.ABILITIES);
    }

    private static SecurityVersions.Reading at(long version) {
        return new SecurityVersions.Reading(version, false);
    }

    private static SecurityCache cache(boolean enabled, int max) {
        return new SecurityCache(enabled, max, new SimpleMeterRegistry());
    }

    @Test
    void anAnswerIsComputedOnceWhileTheVersionStaysTheSame() {
        SecurityCache cache = cache(true, 10);
        UUID member = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();

        String first = cache.get(key(member), at(3), () -> "answer-" + loads.incrementAndGet());
        String second = cache.get(key(member), at(3), () -> "answer-" + loads.incrementAndGet());

        assertThat(first).isEqualTo("answer-1");
        assertThat(second).isEqualTo("answer-1");
        assertThat(loads).hasValue(1);
        assertThat(cache.hitCount()).isEqualTo(1);
        assertThat(cache.missCount()).isEqualTo(1);
    }

    @Test
    void aNewerVersionComputesAgain() {
        SecurityCache cache = cache(true, 10);
        UUID member = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        cache.get(key(member), at(3), () -> "answer-" + loads.incrementAndGet());

        String after = cache.get(key(member), at(4), () -> "answer-" + loads.incrementAndGet());

        assertThat(after).isEqualTo("answer-2");
    }

    @Test
    void anOlderVersionIsNeverServedByANewerAnswerNorTheOtherWayAround() {
        SecurityCache cache = cache(true, 10);
        UUID member = UUID.randomUUID();
        cache.get(key(member), at(5), () -> "new");

        // A slow request that read version 4 finishes late: it gets its own answer and does not replace the newer one.
        String old = cache.get(key(member), at(4), () -> "old");
        String again = cache.get(key(member), at(5), () -> "recomputed");

        assertThat(old).isEqualTo("old");
        assertThat(again).as("the newer answer is still there").isEqualTo("new");
    }

    @Test
    void aTransactionThatHasWrittenSeesItsOwnChangeAndKeepsNothing() {
        SecurityCache cache = cache(true, 10);
        UUID member = UUID.randomUUID();
        cache.get(key(member), at(3), () -> "committed");

        String own = cache.get(key(member), new SecurityVersions.Reading(4, true), () -> "uncommitted view");
        String other = cache.get(key(member), at(3), () -> "recomputed");

        assertThat(own).isEqualTo("uncommitted view");
        assertThat(other).as("the uncommitted view was never kept for anybody else").isEqualTo("committed");
        assertThat(cache.bypassCount()).isEqualTo(1);
    }

    @Test
    void switchedOffEveryQuestionIsComputedAndNothingIsKept() {
        SecurityCache cache = cache(false, 10);
        UUID member = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();

        cache.get(key(member), at(3), loads::incrementAndGet);
        cache.get(key(member), at(3), loads::incrementAndGet);

        assertThat(loads).hasValue(2);
        assertThat(cache.size()).isZero();
        assertThat(cache.hitCount()).isZero();
    }

    @Test
    void theSizeIsBoundedAndTheLeastRecentlyUsedAnswerGoesFirst() {
        SecurityCache cache = cache(true, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        cache.get(key(first), at(1), () -> "first");
        cache.get(key(second), at(1), () -> "second");
        cache.get(key(first), at(1), () -> "first again");

        cache.get(key(third), at(1), () -> "third");

        assertThat(cache.size()).isEqualTo(2);
        AtomicInteger loads = new AtomicInteger();
        cache.get(key(first), at(1), loads::incrementAndGet);
        assertThat(loads).as("the recently used one stayed").hasValue(0);
        cache.get(key(second), at(1), loads::incrementAndGet);
        assertThat(loads).as("the least recently used one was dropped").hasValue(1);
    }

    @Test
    void theKindsAndTheOrganizationsAreKeptApart() {
        SecurityCache cache = cache(true, 10);
        UUID member = UUID.randomUUID();
        cache.get(key(member), at(1), () -> "abilities");

        String effective = cache.get(new SecurityCache.Key(TENANT, member, SecurityCache.Kind.EFFECTIVE), at(1),
                () -> "effective");
        String elsewhere = cache.get(new SecurityCache.Key(UUID.randomUUID(), member, SecurityCache.Kind.ABILITIES),
                at(1), () -> "another organization");

        assertThat(effective).isEqualTo("effective");
        assertThat(elsewhere).isEqualTo("another organization");
    }
}
