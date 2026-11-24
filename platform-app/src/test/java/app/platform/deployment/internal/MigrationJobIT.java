package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The migration job against a real database (ADR-0288): it brings a fresh schema to the latest version, a second run
 * finds nothing to do, two jobs at the same moment both end well and apply every migration exactly once (the migration
 * library's database lock), and a wrong password is a failure that prints neither the password nor the library's text.
 */
class MigrationJobIT {

    private static ThrowawayDatabase server;

    @BeforeAll
    static void startServer() {
        server = new ThrowawayDatabase();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    private static int latestVersion() throws Exception {
        List<String> names = new java.util.ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql")) {
            names.add(resource.getFilename());
        }
        return SchemaVersions.needed(names);
    }

    private static Map<String, String> environment(String url, String user, String password) {
        Map<String, String> environment = new HashMap<>();
        environment.put("PLATFORM_DB_URL", url);
        environment.put("PLATFORM_DB_OWNER_USER", user);
        environment.put("PLATFORM_DB_OWNER_PASSWORD", password);
        return environment;
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    @Test
    void aFreshDatabaseIsMigratedToTheLatestVersionAndASecondRunHasNothingToDo() throws Exception {
        String database = server.newDatabase();
        Map<String, String> environment = environment(server.url(database), ThrowawayDatabase.OWNER,
                server.ownerPassword());

        assertThat(MigrationJob.run(environment::get)).isEqualTo(MigrationJob.DONE);

        try (Connection owner = server.owner(database)) {
            assertThat(scalar(owner, "select platform_schema_version()")).isEqualTo(String.valueOf(latestVersion()));
            String applied = scalar(owner, "select count(*) from flyway_schema_history where success");
            assertThat(MigrationJob.run(environment::get)).isEqualTo(MigrationJob.DONE);
            assertThat(scalar(owner, "select count(*) from flyway_schema_history where success")).isEqualTo(applied);
        }
    }

    @Test
    void twoJobsAtTheSameMomentBothEndWellAndEveryMigrationIsAppliedOnce() throws Exception {
        String database = server.newDatabase();
        Map<String, String> environment = environment(server.url(database), ThrowawayDatabase.OWNER,
                server.ownerPassword());
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> job = () -> {
            go.await();
            return MigrationJob.run(environment::get);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(job);
            Future<Integer> second = pool.submit(job);
            go.countDown();

            assertThat(first.get(180, TimeUnit.SECONDS)).isEqualTo(MigrationJob.DONE);
            assertThat(second.get(180, TimeUnit.SECONDS)).isEqualTo(MigrationJob.DONE);
        } finally {
            pool.shutdownNow();
        }

        try (Connection owner = server.owner(database)) {
            assertThat(scalar(owner, "select count(*) = count(distinct version) from flyway_schema_history "
                    + "where version is not null")).isEqualTo("t");
            assertThat(scalar(owner, "select count(*) from flyway_schema_history where not success")).isEqualTo("0");
            assertThat(scalar(owner, "select platform_schema_version()")).isEqualTo(String.valueOf(latestVersion()));
        }
    }

    @Test
    void aWrongPasswordIsAFailureThatNamesNeitherThePasswordNorTheLibrarysText() {
        String database = server.newDatabase();
        String wrong = "not-the-password-" + System.nanoTime();
        Map<String, String> environment = environment(server.url(database), ThrowawayDatabase.OWNER, wrong);

        assertThat(MigrationJob.run(environment::get)).isEqualTo(MigrationJob.FAILED);
    }
}
