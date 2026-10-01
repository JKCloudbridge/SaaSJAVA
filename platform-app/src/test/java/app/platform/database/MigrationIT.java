package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.testsupport.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The migration framework against a real, empty PostgreSQL: it brings an empty database to the current version in
 * order, does nothing the second time, and refuses a migration that was edited after it ran (migrations are
 * forward-only; a correction is a new migration).
 */
class MigrationIT {

    private String databaseName;
    private String url;

    @BeforeEach
    void createEmptyDatabase() throws SQLException {
        databaseName = "migrate_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create database " + databaseName);
        }
        url = TestDatabase.jdbcUrl().replace("/platform?", "/" + databaseName + "?");
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("drop database " + databaseName + " with (force)");
        }
    }

    @Test
    void anEmptyDatabaseIsMigratedToTheCurrentVersionInOrder() throws Exception {
        List<String> files = applicationMigrationFiles();
        assertThat(files).as("the application has migrations").isNotEmpty();

        MigrateResult result = flyway("classpath:db/migration").migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(files.size());
        try (Connection connection = DriverManager.getConnection(url, TestDatabase.ownerUser(),
                        TestDatabase.ownerPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select version, description, success, installed_by from flyway_schema_history "
                                + "order by installed_rank")) {
            List<String> versions = new ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getString("version"));
                assertThat(rs.getBoolean("success")).isTrue();
                assertThat(rs.getString("installed_by")).isEqualTo(TestDatabase.ownerUser());
            }
            assertThat(versions).hasSize(files.size());
            assertThat(versions).isSorted();
            assertThat(versions.get(0)).isEqualTo("001");
        }
    }

    @Test
    void theGuardFunctionOfTheBaseConventionsExistsAfterMigration() throws Exception {
        flyway("classpath:db/migration").migrate();

        try (Connection connection = DriverManager.getConnection(url, TestDatabase.ownerUser(),
                        TestDatabase.ownerPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select count(*) from pg_proc where proname = 'platform_row_guard'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void migratingAgainChangesNothing() {
        Flyway flyway = flyway("classpath:db/migration");
        flyway.migrate();

        MigrateResult again = flyway.migrate();

        assertThat(again.migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
    }

    @Test
    void aMigrationEditedAfterItRanIsRefused(@org.junit.jupiter.api.io.TempDir Path folder) throws IOException {
        Path migration = folder.resolve("V001__create_probe.sql");
        Files.writeString(migration, "create table probe (id int);\n");
        Flyway flyway = flyway("filesystem:" + folder);
        flyway.migrate();

        Files.writeString(migration, "create table probe (id int, extra int);\n");

        assertThatThrownBy(flyway::validate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void aMigrationThatFailsLeavesNothingHalfApplied(@org.junit.jupiter.api.io.TempDir Path folder)
            throws Exception {
        Files.writeString(folder.resolve("V001__good.sql"), "create table good_one (id int);\n");
        Files.writeString(folder.resolve("V002__bad.sql"),
                "create table half_done (id int);\nselect 1 / 0;\n");
        Flyway flyway = flyway("filesystem:" + folder);

        assertThatThrownBy(flyway::migrate).isInstanceOf(Exception.class);

        try (Connection connection = DriverManager.getConnection(url, TestDatabase.ownerUser(),
                        TestDatabase.ownerPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select to_regclass('good_one') is not null, to_regclass('half_done') is null")) {
            rs.next();
            assertThat(rs.getBoolean(1)).as("the first migration stays applied").isTrue();
            assertThat(rs.getBoolean(2)).as("the failed one rolled back completely").isTrue();
        }
    }

    private Flyway flyway(String location) {
        return Flyway.configure()
                .dataSource(url, TestDatabase.ownerUser(), TestDatabase.ownerPassword())
                .locations(location)
                .cleanDisabled(true)
                .placeholderReplacement(false)
                .load();
    }

    private static List<String> applicationMigrationFiles() throws IOException {
        List<String> names = new ArrayList<>();
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
        for (Resource resource : resources) {
            names.add(resource.getFilename());
        }
        return names;
    }
}
