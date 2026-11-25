package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The rule checker's own tests: statements that must be flagged, and ones that must pass (ADR-0288). */
class MigrationRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "alter table invoice alter column amount type numeric(20,4);",
        "ALTER TABLE invoice ALTER COLUMN amount SET DATA TYPE bigint;",
        "alter table invoice alter column amount set not null;",
        "create index invoice_by_date on invoice (created_at);",
        "create unique index invoice_number on invoice (number) where deleted_at is null;",
        "alter table invoice add constraint invoice_customer foreign key (customer_id) references customer (id);",
        "alter table invoice add constraint amount_positive check (amount > 0);",
        "alter table invoice add column token uuid default gen_random_uuid();",
        "alter table invoice add column token uuid not null default uuidv7();",
        "alter table invoice drop column legacy;",
        "alter table invoice rename column amount to total;",
        "alter table invoice rename to bill;",
        "drop table invoice;",
        "update invoice set state = 'OLD';",
        "delete from invoice;",
        "lock table invoice in access exclusive mode;",
        "truncate invoice;",
        "vacuum full invoice;",
        "cluster invoice using invoice_pkey;",
        "reindex table invoice;"})
    void aStatementThatNeedsCareIsFlagged(String sql) {
        assertThat(MigrationRules.check(sql)).as(sql).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "create table invoice (id uuid primary key, amount numeric);",
        "create table invoice (id uuid primary key); create index invoice_by_id on invoice (id);",
        "create table invoice (id uuid primary key); alter table invoice alter column id set not null;",
        "create table invoice (id uuid); update invoice set id = id;",
        "create index concurrently invoice_by_date on invoice (created_at);",
        "alter table invoice add column note text;",
        "alter table invoice add column state text not null default 'NEW';",
        "alter table invoice add column seen timestamptz default now();",
        "alter table invoice add constraint amount_positive check (amount > 0) not valid;",
        "alter table invoice validate constraint amount_positive;",
        "alter table invoice drop constraint amount_positive;",
        "update invoice set state = 'OLD' where state = 'LEGACY';",
        "delete from invoice where deleted_at < now();",
        "create or replace function f() returns integer language sql as $$ select 1; update x set a = 1; $$;",
        "-- drop table invoice; truncate invoice;\nselect 1;",
        "/* drop table invoice; */ select 1;",
        "insert into invoice (note) values ('drop table invoice; truncate invoice;');",
        "comment on function platform_schema_version() is 'delete from invoice';"})
    void aStatementThatIsSafeIsNotFlagged(String sql) {
        assertThat(MigrationRules.check(sql)).as(sql).isEmpty();
    }

    @Test
    void aReviewedFileHasNoFindingsButOnlyWithAReason() {
        String reviewed = "-- long-lock-reviewed: the table has fewer than a hundred rows by design\n"
                + "alter table setting alter column value set not null;";
        String noReason = "-- long-lock-reviewed:\nalter table setting alter column value set not null;";
        String tooShort = "-- long-lock-reviewed: ok\nalter table setting alter column value set not null;";

        assertThat(MigrationRules.check(reviewed)).isEmpty();
        assertThat(MigrationRules.check(noReason)).isNotEmpty();
        assertThat(MigrationRules.check(tooShort)).isNotEmpty();
    }

    @Test
    void everyFindingNamesTheStatementAndWhatToDoInstead() {
        List<MigrationRules.Finding> findings = MigrationRules.check("create index a on b (c);");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).statement()).contains("create index a on b");
        assertThat(findings.get(0).reason()).contains("concurrently");
    }

    @Test
    void theProbeMigrationOfThisSprintPasses() throws Exception {
        String sql = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/db/migration/V097__create_schema_version_probe.sql"));

        assertThat(MigrationRules.check(sql)).isEmpty();
    }
}
