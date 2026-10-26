package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cost of what the home page asks the database for (FA9, ADR-0203): the fill-ins sent in a period out of 50,000
 * (spread over a year), the fill-ins that wait for a review, and the dates of the versions of a page of 500 templates
 * out of 2,000 templates with 50 versions each. Each statement is explained with {@code explain (analyze, buffers)} and
 * set against reading the table; the numbers are written to {@code target/it-logs/quality-home-cost.txt} so the sprint
 * document can quote them. The assertions are the relations that must hold: the period reads a small part of the table
 * through the new index, never a scan of it, and the dates of a page use the index of the owner.
 *
 * <p>What is not measured here is the record store's judgement of the fill-ins found (one query of at most 100
 * identifiers each, through the same stages as any list); that cost is the record store's and is bounded by the
 * setting {@code platform.inspection.home.max-scanned}.
 */
@PlatformIntegrationTest
class QualityHomeCostIT {

    private static final int FILLS = 50_000;
    private static final int TEMPLATES = 2_000;
    private static final int VERSIONS = 50;
    private static final int PAGE = 500;
    private static final UUID ACTOR = ActorId.SYSTEM.value();

    /** The statements of {@code SheetFillStore} and {@code SheetVersionStore} (kept in step by this test failing). */
    private static final String SUBMITTED_BETWEEN = "select fill_id, owner_id, status, submitted_at, "
            + "approval_request_id, created_by from sheet_fill where tenant_id = ? "
            + "and fill_object = 'InspectionFill' and submitted_at >= ? and submitted_at < ? and deleted_at is null "
            + "order by submitted_at, fill_id limit 50001";
    private static final String AWAITING = "select fill_id, owner_id, status, submitted_at, approval_request_id, "
            + "created_by from sheet_fill where tenant_id = ? and fill_object = 'InspectionFill' "
            + "and status = 'SUBMITTED' and approval_request_id is not null and deleted_at is null "
            + "order by submitted_at, fill_id limit 50001";
    private static final String DATES = "select owner_id, version_no, status, submitted_at, decided_at, "
            + "approval_request_id from sheet_version where tenant_id = ? and owner_object = 'InspectionTemplate' "
            + "and owner_id in (%s) and deleted_at is null and (status = 'IN_REVIEW' or decided_at >= ?) "
            + "order by owner_id, version_no";

    private record Cost(long buffers, double millis, String plan) {
    }

    private record Seeded(List<UUID> page) {
    }

    private static Seeded seed(TenantId tenant) throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setTenant(connection, tenant);
            try (var statement = connection.createStatement()) {
                // The guards would make every row walk through the lifecycle; the point is the cost of reading rows
                // that already are what a long life leaves, so they are switched off for the load.
                statement.execute("alter table sheet_fill disable trigger user");
                statement.execute("alter table sheet_version disable trigger user");
                try (PreparedStatement fills = connection.prepareStatement("insert into sheet_fill (tenant_id, "
                        + "fill_object, fill_id, owner_object, owner_id, version_no, status, cells, cells_hash, "
                        + "cells_bytes, submitted_at, approval_request_id, created_by, updated_by) select ?, "
                        + "'InspectionFill', gen_random_uuid(), 'InspectionTemplate', gen_random_uuid(), 1, "
                        + "case when n % 20 = 0 then 'SUBMITTED' else 'APPROVED' end, '{\"formatVersion\":1}'::jsonb, "
                        + "decode(repeat('00', 32), 'hex'), 20, now() - (n % 365) * interval '1 day', "
                        + "case when n % 2 = 0 then gen_random_uuid() end, ?, ? "
                        + "from generate_series(1, " + FILLS + ") as g(n)")) {
                    fills.setObject(1, tenant.value());
                    fills.setObject(2, ACTOR);
                    fills.setObject(3, ACTOR);
                    fills.executeUpdate();
                }
                statement.execute("create temporary table owners on commit drop as select gen_random_uuid() as id "
                        + "from generate_series(1, " + TEMPLATES + ")");
                try (PreparedStatement versions = connection.prepareStatement("insert into sheet_version (tenant_id, "
                        + "owner_object, owner_id, version_no, status, dimension, rows_mode, grid, grid_hash, "
                        + "grid_bytes, submitted_at, decided_at, created_by, updated_by) select ?, "
                        + "'InspectionTemplate', o.id, v.n, case when v.n = " + VERSIONS
                        + " then 'ACTIVE' else 'SUPERSEDED' end, 2, 'FIXED', '{\"formatVersion\":1}'::jsonb, "
                        + "decode(repeat('00', 32), 'hex'), 20, now() - (v.n * interval '7 days'), "
                        + "now() - (v.n * interval '7 days') + interval '2 days', ?, ? "
                        + "from owners o cross join generate_series(1, " + VERSIONS + ") as v(n)")) {
                    versions.setObject(1, tenant.value());
                    versions.setObject(2, ACTOR);
                    versions.setObject(3, ACTOR);
                    versions.executeUpdate();
                }
                List<UUID> page = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("select id from owners limit " + PAGE)) {
                    while (rs.next()) {
                        page.add(rs.getObject(1, UUID.class));
                    }
                }
                statement.execute("alter table sheet_fill enable trigger user");
                statement.execute("alter table sheet_version enable trigger user");
                connection.commit();
                statement.execute("analyze sheet_fill");
                statement.execute("analyze sheet_version");
                return new Seeded(page);
            }
        }
    }

    private static Cost explain(TenantId tenant, String sql, Object... parameters) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("explain (analyze, buffers, format json) "
                    + sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    String json = rs.getString(1);
                    long hit = ((Number) JsonPath.read(json, "$[0].Plan['Shared Hit Blocks']")).longValue();
                    long read = ((Number) JsonPath.read(json, "$[0].Plan['Shared Read Blocks']")).longValue();
                    double millis = ((Number) JsonPath.read(json, "$[0]['Execution Time']")).doubleValue();
                    return new Cost(hit + read, millis, json);
                }
            }
        });
    }

    private static void clear(TenantId tenant) throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setTenant(connection, tenant);
            try (var statement = connection.createStatement()) {
                statement.execute("alter table sheet_fill disable trigger user");
                statement.execute("alter table sheet_version disable trigger user");
                statement.execute("delete from sheet_fill");
                statement.execute("delete from sheet_version");
                statement.execute("alter table sheet_fill enable trigger user");
                statement.execute("alter table sheet_version enable trigger user");
            }
            connection.commit();
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("vacuum analyze sheet_fill");
                statement.execute("vacuum analyze sheet_version");
            }
        }
    }

    @Test
    void aPeriodOfFillInsAndAPageOfTemplateDatesReadAFractionOfTheirTablesThroughTheirIndexes()
            throws SQLException, IOException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        try {
            measure(tenant);
        } finally {
            clear(tenant);
        }
    }

    private static void measure(TenantId tenant) throws SQLException, IOException {
        Seeded seeded = seed(tenant);
        Instant now = Instant.now();
        Timestamp to = Timestamp.from(now.plus(1, ChronoUnit.DAYS));
        Timestamp week = Timestamp.from(now.minus(7, ChronoUnit.DAYS));
        Timestamp month = Timestamp.from(now.minus(30, ChronoUnit.DAYS));
        Timestamp year = Timestamp.from(now.minus(365, ChronoUnit.DAYS));
        String marks = String.join(", ", java.util.Collections.nCopies(seeded.page().size(), "?"));
        Object[] parameters = new Object[2 + seeded.page().size()];
        parameters[0] = tenant.value();
        for (int i = 0; i < seeded.page().size(); i++) {
            parameters[i + 1] = seeded.page().get(i);
        }
        parameters[parameters.length - 1] = Timestamp.from(now.minus(60, ChronoUnit.DAYS));

        Cost sevenDays = explain(tenant, SUBMITTED_BETWEEN, tenant.value(), week, to);
        Cost thirtyDays = explain(tenant, SUBMITTED_BETWEEN, tenant.value(), month, to);
        Cost aYear = explain(tenant, SUBMITTED_BETWEEN, tenant.value(), year, to);
        Cost awaiting = explain(tenant, AWAITING, tenant.value());
        Cost dates = explain(tenant, String.format(DATES, marks), parameters);
        Cost everything = explain(tenant, "select fill_id from sheet_fill where tenant_id = ? and deleted_at is null",
                tenant.value());
        long rows = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from sheet_fill"));
        long versions = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from sheet_version"));
        long tableBytes = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select pg_total_relation_size('sheet_fill')"));

        String report = "fill-ins: " + rows + ", sheet versions: " + versions + "\n"
                + "fill table with its indexes (bytes): " + tableBytes + "\n"
                + "fill-ins sent in 7 days: " + sevenDays.buffers() + " pages, " + sevenDays.millis() + " ms\n"
                + "fill-ins sent in 30 days: " + thirtyDays.buffers() + " pages, " + thirtyDays.millis() + " ms\n"
                + "fill-ins sent in a year: " + aYear.buffers() + " pages, " + aYear.millis() + " ms\n"
                + "fill-ins waiting for a review: " + awaiting.buffers() + " pages, " + awaiting.millis() + " ms\n"
                + "dates of a page of " + PAGE + " templates: " + dates.buffers() + " pages, " + dates.millis()
                + " ms\n"
                + "reading every fill-in: " + everything.buffers() + " pages, " + everything.millis() + " ms\n"
                + "30 day plan: " + thirtyDays.plan().replaceAll("\\s+", " ") + "\n"
                + "dates plan: " + dates.plan().replaceAll("\\s+", " ") + "\n";
        Path out = Path.of("target/it-logs/quality-home-cost.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);

        assertThat(rows).isEqualTo(FILLS);
        assertThat(sevenDays.buffers()).as(report).isLessThan(everything.buffers() / 10);
        assertThat(thirtyDays.buffers()).as(report).isLessThan(everything.buffers() / 3);
        assertThat(sevenDays.buffers()).isLessThan(thirtyDays.buffers());
        assertThat(thirtyDays.plan()).as("the period is read through its index, not by scanning the table")
                .contains("sheet_fill_tenant_submitted").doesNotContain("Seq Scan");
        assertThat(awaiting.plan()).as("the waiting ones have an index of their own")
                .contains("sheet_fill_tenant_awaiting");
        assertThat(dates.plan()).as("the index of the owner is used for the dates of a page")
                .contains("sheet_version_owner_number").doesNotContain("Seq Scan on sheet_version");
    }
}
