package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.Uuids;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The base schema conventions (ADR-0010) proven on a real PostgreSQL: a table built to the pattern gets time-ordered
 * identifiers, database-owned timestamps, a version that must advance on every update and soft delete bookkeeping;
 * a scanner finds any table that does not follow the pattern, and finds none among the platform's real tables.
 */
@PlatformIntegrationTest
class SchemaConventionsIT {

    private static final String PROBE = "conventions_probe";
    private static final UUID ACTOR = Uuids.v7();
    private static final String PERSONAL = "user-a@example.test";

    private static Connection db;

    @BeforeAll
    static void createProbeSchema() throws SQLException {
        db = TestDatabase.ownerConnection();
        try (Statement statement = db.createStatement()) {
            statement.execute("drop schema if exists " + PROBE + " cascade");
            statement.execute("create schema " + PROBE);
            statement.execute("""
                    create table %1$s.item (
                        id          uuid        primary key default uuidv7(),
                        name        text        not null,
                        version     bigint      not null default 0,
                        created_at  timestamptz not null default now(),
                        created_by  uuid        not null,
                        updated_at  timestamptz not null default now(),
                        updated_by  uuid        not null,
                        deleted_at  timestamptz,
                        deleted_by  uuid
                    )""".formatted(PROBE));
            statement.execute("create trigger item_row_guard before insert or update on " + PROBE
                    + ".item for each row execute function public.platform_row_guard()");
            statement.execute("create unique index item_name_live on " + PROBE
                    + ".item (name) where deleted_at is null");
        }
    }

    @AfterAll
    static void dropProbeSchema() throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute("drop schema " + PROBE + " cascade");
        } finally {
            db.close();
        }
    }

    // ---- the identifier ----

    @Test
    void identifiersGeneratedByTheDatabaseAreVersion7AndIncreaseWithInsertionOrder() throws SQLException {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(insert("ordered-" + i + "-" + Uuids.v7()));
        }

        assertThat(ids).allSatisfy(id -> assertThat(Uuids.isV7(id)).isTrue());
        assertThat(ids).isSortedAccordingTo(SchemaConventionsIT::unsigned);
    }

    // ---- insert ----

    @Test
    void theDatabaseOwnsTheTimestampsTheFirstVersionAndTheDeletionState() throws SQLException {
        Instant before = Instant.now().minusSeconds(5);
        UUID id = insertRaw("insert into " + PROBE + ".item (name, created_at, created_by, updated_by, version, "
                + "deleted_at, deleted_by) values ('forced-" + Uuids.v7() + "', '1999-01-01', '" + ACTOR + "', '"
                + ACTOR + "', 41, now(), '" + ACTOR + "') returning id");

        Row row = row(id);

        assertThat(row.createdAt()).isAfter(before);
        assertThat(row.updatedAt()).isEqualTo(row.createdAt());
        assertThat(row.version()).isZero();
        assertThat(row.deletedAt()).isNull();
        assertThat(row.deletedBy()).isNull();
    }

    @Test
    void anInsertWithoutAnActorIsRefused() {
        assertThatThrownBy(() -> execute("insert into " + PROBE + ".item (name, created_by, updated_by) "
                + "values ('no-actor', null, null)"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23502"));
    }

    // ---- update and optimistic concurrency ----

    @Test
    void anUpdateMustAdvanceTheVersionByExactlyOneAndTouchesUpdatedAt() throws SQLException {
        UUID id = insert("update-" + Uuids.v7());
        Row created = row(id);

        execute("update " + PROBE + ".item set name = 'renamed-" + id + "', updated_by = '" + ACTOR
                + "', version = version + 1 where id = '" + id + "'");

        Row updated = row(id);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(created.updatedAt());
    }

    @Test
    void anUpdateThatForgetsTheVersionIsRefusedWithoutQuotingTheRow() throws SQLException {
        UUID id = insertRaw("insert into " + PROBE + ".item (name, created_by, updated_by) values ('" + PERSONAL + "-"
                + Uuids.v7() + "', '" + ACTOR + "', '" + ACTOR + "') returning id");

        assertThatThrownBy(() -> execute("update " + PROBE + ".item set updated_by = '" + ACTOR + "' where id = '"
                + id + "'"))
                .isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo("23514");
                    assertThat(e.getMessage()).contains("version").doesNotContain(PERSONAL);
                });
        assertThatThrownBy(() -> execute("update " + PROBE + ".item set version = version + 2 where id = '" + id + "'"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void identityAndCreationDataCanNeverChange() throws SQLException {
        UUID id = insert("immutable-" + Uuids.v7());

        for (String assignment : List.of(
                "id = '" + Uuids.v7() + "'",
                "created_at = now() - interval '1 day'",
                "created_by = '" + Uuids.v7() + "'")) {
            assertThatThrownBy(() -> execute("update " + PROBE + ".item set " + assignment
                    + ", version = version + 1 where id = '" + id + "'"))
                    .as(assignment)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
    }

    @Test
    void aStaleVersionMatchesNoRowSoTheUsualGuardedUpdateDetectsTheConflict() throws SQLException {
        UUID id = insert("stale-" + Uuids.v7());
        String guarded = "update " + PROBE + ".item set updated_by = '" + ACTOR + "', version = version + 1 "
                + "where id = '" + id + "' and version = ";

        assertThat(update(guarded + 0)).as("first writer wins").isEqualTo(1);
        assertThat(update(guarded + 0)).as("second writer read version 0 and loses").isZero();
        assertThat(update(guarded + 1)).as("a writer that reread succeeds").isEqualTo(1);
    }

    // ---- soft delete ----

    @Test
    void softDeleteIsRecordedByTheDatabaseAndADeletedRowIsReadOnlyUntilRestored() throws SQLException {
        UUID id = insert("deleted-" + Uuids.v7());

        execute("update " + PROBE + ".item set deleted_at = now() - interval '1 year', deleted_by = '" + ACTOR
                + "', updated_by = '" + ACTOR + "', version = version + 1 where id = '" + id + "'");
        Row deleted = row(id);
        assertThat(deleted.deletedAt()).as("the database clock, not the caller's")
                .isAfter(Instant.now().minusSeconds(5));
        assertThat(deleted.deletedBy()).isEqualTo(ACTOR);

        assertThatThrownBy(() -> execute("update " + PROBE + ".item set name = 'edit-while-deleted', version = "
                + "version + 1 where id = '" + id + "'"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));

        execute("update " + PROBE + ".item set deleted_at = null, updated_by = '" + ACTOR
                + "', version = version + 1 where id = '" + id + "'");
        Row restored = row(id);
        assertThat(restored.deletedAt()).isNull();
        assertThat(restored.deletedBy()).as("restoring clears who deleted it").isNull();
        assertThat(restored.version()).isEqualTo(2);
    }

    @Test
    void deletingWithoutSayingWhoIsRefused() throws SQLException {
        UUID id = insert("anonymous-delete-" + Uuids.v7());

        assertThatThrownBy(() -> execute("update " + PROBE + ".item set deleted_at = now(), version = version + 1 "
                + "where id = '" + id + "'"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void aSoftDeletedRowDoesNotBlockItsKeyAndPermanentDeleteIsStillPossible() throws SQLException {
        String name = "reusable-" + Uuids.v7();
        UUID first = insert(name);
        assertThatThrownBy(() -> insert(name)).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as("a live duplicate is refused").isEqualTo("23505"));

        execute("update " + PROBE + ".item set deleted_at = now(), deleted_by = '" + ACTOR + "', updated_by = '" + ACTOR
                + "', version = version + 1 where id = '" + first + "'");
        UUID second = insert(name);

        assertThat(second).isNotEqualTo(first);
        assertThat(update("delete from " + PROBE + ".item where id = '" + first + "'")).isEqualTo(1);
    }

    // ---- the scanner ----

    @Test
    void theScannerAcceptsATableBuiltToThePattern() throws SQLException {
        assertThat(SchemaConventions.problems(db, PROBE)).isEmpty();
    }

    @Test
    void theScannerFindsEveryKindOfDeviation() throws SQLException {
        String bad = "conventions_bad";
        try (Statement statement = db.createStatement()) {
            statement.execute("drop schema if exists " + bad + " cascade");
            statement.execute("create schema " + bad);
            statement.execute("create table " + bad + ".no_version (id uuid primary key default uuidv7(), "
                    + "created_at timestamptz not null default now(), created_by uuid not null, "
                    + "updated_at timestamptz not null default now(), updated_by uuid not null, "
                    + "deleted_at timestamptz, deleted_by uuid)");
            statement.execute("create table " + bad + ".wrong_types (id uuid primary key default uuidv7(), "
                    + "version bigint not null default 0, created_at timestamptz, created_by text not null, "
                    + "updated_at timestamptz not null default now(), updated_by uuid not null, "
                    + "deleted_at timestamptz, deleted_by uuid)");
            statement.execute("create table " + bad + ".random_id (id uuid primary key default gen_random_uuid(), "
                    + "version bigint not null default 0, created_at timestamptz not null default now(), "
                    + "created_by uuid not null, updated_at timestamptz not null default now(), "
                    + "updated_by uuid not null, deleted_at timestamptz, deleted_by uuid)");
            statement.execute("create table " + bad + ".no_trigger (id uuid primary key default uuidv7(), "
                    + "version bigint not null default 0, created_at timestamptz not null default now(), "
                    + "created_by uuid not null, updated_at timestamptz not null default now(), "
                    + "updated_by uuid not null, deleted_at timestamptz, deleted_by uuid)");
            statement.execute("create table " + bad + ".plain_unique (id uuid primary key default uuidv7(), "
                    + "name text, version bigint not null default 0, created_at timestamptz not null default now(), "
                    + "created_by uuid not null, updated_at timestamptz not null default now(), "
                    + "updated_by uuid not null, deleted_at timestamptz, deleted_by uuid)");
            statement.execute("create unique index plain_unique_name on " + bad + ".plain_unique (name)");
            for (String table : List.of("no_version", "wrong_types", "random_id", "plain_unique")) {
                statement.execute("create trigger " + table + "_row_guard before insert or update on " + bad + "."
                        + table + " for each row execute function public.platform_row_guard()");
            }
            statement.execute("create table " + bad + ".serial_key (n serial primary key)");

            List<String> problems = SchemaConventions.problems(db, bad);

            assertThat(problems).anyMatch(p -> p.startsWith("no_version") && p.contains("missing column version"));
            assertThat(problems).anyMatch(p -> p.startsWith("wrong_types") && p.contains("created_by"));
            assertThat(problems).anyMatch(p -> p.startsWith("wrong_types") && p.contains("created_at"));
            assertThat(problems).anyMatch(p -> p.startsWith("random_id") && p.contains("uuidv7()"));
            assertThat(problems).anyMatch(p -> p.startsWith("no_trigger") && p.contains("platform_row_guard"));
            assertThat(problems).anyMatch(p -> p.startsWith("plain_unique") && p.contains("deleted_at is null"));
            assertThat(problems).anyMatch(p -> p.startsWith("serial_key") && p.contains("primary key"));
            statement.execute("drop schema " + bad + " cascade");
        }
    }

    @Test
    void everyTableThePlatformMigrationsCreateFollowsTheConventions() throws SQLException {
        assertThat(SchemaConventions.problems(db, "public")).isEmpty();
    }

    // ---- helpers ----

    private record Row(Instant createdAt, Instant updatedAt, long version, Instant deletedAt, UUID deletedBy) {
    }

    private static UUID insert(String name) throws SQLException {
        return insertRaw("insert into " + PROBE + ".item (name, created_by, updated_by) values ('" + name + "', '"
                + ACTOR + "', '" + ActorId.SYSTEM + "') returning id");
    }

    private static UUID insertRaw(String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int update(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static Row row(UUID id) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement("select created_at, updated_at, version, deleted_at, "
                + "deleted_by from " + PROBE + ".item where id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                Timestamp deleted = rs.getTimestamp("deleted_at");
                return new Row(rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getLong("version"), deleted == null ? null : deleted.toInstant(),
                        rs.getObject("deleted_by", UUID.class));
            }
        }
    }

    private static int unsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
