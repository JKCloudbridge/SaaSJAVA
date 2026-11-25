package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

/** The small behaviours of the deployment module that need no database (ADR-0287 to ADR-0289). */
class DeploymentBehaviourTest {

    // ---- the shutdown delay ----

    @Test
    void aStopSaysNotReadyFirstAndThenWaitsTheDelay() throws Exception {
        CopyOnWriteArrayList<Object> published = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(Object event) {
                published.add(event);
            }

            @Override
            public void publishEvent(ApplicationEvent event) {
                published.add(event);
            }
        };
        ShutdownDelay delay = new ShutdownDelay(Duration.ofMillis(300), publisher);
        delay.start();
        CountDownLatch done = new CountDownLatch(1);
        long started = System.nanoTime();

        delay.stop(done::countDown);

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(waited).isGreaterThanOrEqualTo(280);
        assertThat(published).hasSize(1);
        assertThat(published.get(0)).isInstanceOf(AvailabilityChangeEvent.class);
        AvailabilityChangeEvent<?> event = (AvailabilityChangeEvent<?>) published.get(0);
        assertThat(event.getState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        assertThat(delay.isRunning()).isFalse();
    }

    @Test
    void aZeroDelayStopsAtOnceButStillSaysNotReady() throws Exception {
        CopyOnWriteArrayList<Object> published = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher publisher = published::add;
        ShutdownDelay delay = new ShutdownDelay(Duration.ZERO, publisher);
        delay.start();
        CountDownLatch done = new CountDownLatch(1);

        delay.stop(done::countDown);

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(published).hasSize(1);
    }

    @Test
    void aFirstStartSaysNothingAboutReadiness() {
        CopyOnWriteArrayList<Object> published = new CopyOnWriteArrayList<>();
        ShutdownDelay delay = new ShutdownDelay(Duration.ZERO, published::add);

        delay.start();

        assertThat(published).isEmpty();
        assertThat(delay.isRunning()).isTrue();
    }

    @Test
    void aStopFollowedByAStartIsReadyAgain() throws Exception {
        // A paused and restarted context (a test suite does this to cached contexts) must not stay "not ready".
        CopyOnWriteArrayList<Object> published = new CopyOnWriteArrayList<>();
        ShutdownDelay delay = new ShutdownDelay(Duration.ZERO, published::add);
        delay.start();
        CountDownLatch done = new CountDownLatch(1);
        delay.stop(done::countDown);
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();

        delay.start();

        assertThat(published).hasSize(2);
        assertThat(((AvailabilityChangeEvent<?>) published.get(0)).getState())
                .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        assertThat(((AvailabilityChangeEvent<?>) published.get(1)).getState())
                .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        // And a second plain start says nothing more.
        delay.start();
        assertThat(published).hasSize(2);
    }

    // ---- the build stamp ----

    @Test
    void aStampThatLooksRightIsShownAndAnythingElseIsUnknown() {
        BuildStamp good = new BuildStamp(new DeploymentProperties.Build("0123abc", "2026-11-24T10:15:30Z"), null);
        Info.Builder builder = new Info.Builder();
        good.contribute(builder);
        assertThat(builder.build().getDetails().get("release").toString())
                .contains("commit=0123abc", "builtAt=2026-11-24T10:15:30Z", "version=unknown");

        BuildStamp hostile = new BuildStamp(new DeploymentProperties.Build("not a hash; drop table",
                "yesterday\nINFO fake line"), null);
        Info.Builder other = new Info.Builder();
        hostile.contribute(other);
        assertThat(other.build().getDetails().get("release").toString())
                .contains("commit=unknown", "builtAt=unknown").doesNotContain("drop", "fake");
    }

    // ---- the schema check ----

    @Test
    void theCheckDoesNothingUnlessItIsSwitchedOn() {
        MockEnvironment off = new MockEnvironment();
        // No datasource at all: if it tried to connect it would fail, so reaching the end proves it did nothing.
        SchemaCheck.check(off, new org.springframework.core.io.support.PathMatchingResourcePatternResolver());
    }

    @Test
    void aProcessThatMigratesItselfNeedsNoCheck() {
        MockEnvironment migrating = new MockEnvironment().withProperty(SchemaCheck.VERIFY, "true")
                .withProperty("spring.flyway.enabled", "true");
        SchemaCheck.check(migrating, new org.springframework.core.io.support.PathMatchingResourcePatternResolver());
    }

    @Test
    void aCheckWithoutAnAddressFailsWithAClearSentence() {
        MockEnvironment missing = new MockEnvironment().withProperty(SchemaCheck.VERIFY, "true")
                .withProperty("spring.flyway.enabled", "false");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SchemaCheck.check(missing,
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.datasource.url");
    }

    // ---- the migration job ----

    @Test
    void theJobNeedsExactlyThreeValuesAndSaysWhichIsMissing() {
        Map<String, String> environment = new HashMap<>();
        Function<String, String> read = environment::get;
        assertThat(MigrationJob.run(read)).isEqualTo(MigrationJob.NOT_CONFIGURED);

        environment.put("PLATFORM_DB_URL", "jdbc:postgresql://127.0.0.1:1/none");
        environment.put("PLATFORM_DB_OWNER_USER", "owner");
        assertThat(MigrationJob.run(read)).isEqualTo(MigrationJob.NOT_CONFIGURED);
    }

    @Test
    void aFailureIsDescribedByClassesStateAndFileNeverByTheLibrarysText() {
        RuntimeException failure = new RuntimeException("Migration V098__add_thing.sql failed: ERROR: duplicate key "
                + "value violates unique constraint \"x\" Detail: Key (email)=(user-a@example.test) already exists",
                new java.sql.SQLException("quoted data user-a@example.test", "23505"));

        String described = MigrationJob.describe(failure);

        assertThat(described).contains("RuntimeException", "SQLException", "23505", "V098__add_thing.sql");
        assertThat(described).doesNotContain("user-a@example.test", "duplicate key", "Detail");
    }
}
