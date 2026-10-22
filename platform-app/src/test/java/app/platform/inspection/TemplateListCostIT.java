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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cost of the lifecycle summary of the master list (FA4, ADR-0188 section 7): 2,000 templates of 200 versions each
 * (400,000 rows of the version table, with grids of a few bytes, because the statement must never read a grid and so
 * its cost cannot depend on their size). The grouped statement for one page of 50 templates is explained with
 * {@code explain (analyze, buffers)} and set against reading the table; the numbers are written to
 * {@code target/it-logs/template-list-cost.txt} so that the sprint document can quote them. The assertions are the
 * relations that must hold: a page touches a small fraction of the table, and the plan uses the index of the owner,
 * not a scan of the table.
 */
@PlatformIntegrationTest
class TemplateListCostIT {

    private static final int TEMPLATES = 2_000;
    private static final int VERSIONS = 200;
    private static final int PAGE = 50;
    private static final UUID ACTOR = ActorId.SYSTEM.value();

    /** The statement of {@code SheetVersionStore} for the summaries of a page (kept in step by this test failing). */
    private static final String SUMMARIES = "select owner_id, "
            + "coalesce(max(version_no) filter (where status = 'ACTIVE'), 0) as active, "
            + "coalesce(max(version_no) filter (where status = 'APPROVED'), 0) as approved, "
            + "coalesce(max(version_no) filter (where status = 'IN_REVIEW'), 0) as in_review, "
            + "coalesce(max(version_no) filter (where status = 'DRAFT'), 0) as draft, "
            + "coalesce(max(version_no) filter (where status = 'RETIRED'), 0) as retired, "
            + "max(version_no) as highest, count(*) as total from sheet_version "
            + "where tenant_id = ? and owner_object = 'InspectionTemplate' and owner_id in (%s) "
            + "and deleted_at is null group by owner_id";

    private static List<UUID> seed(TenantId tenant) throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setTenant(connection, tenant);
            try (var statement = connection.createStatement()) {
                // The guards of the lifecycle would make every row walk through draft and review; the point here is the
                // cost of reading rows that already are what a long life leaves, so they are switched off for the load.
                statement.execute("alter table sheet_version disable trigger user");
                statement.execute("create temporary table owners on commit drop as select gen_random_uuid() as id "
                        + "from generate_series(1, " + TEMPLATES + ")");
                try (PreparedStatement insert = connection.prepareStatement("insert into sheet_version (tenant_id, "
                        + "owner_object, owner_id, version_no, status, dimension, rows_mode, grid, grid_hash, "
                        + "grid_bytes, created_by, updated_by) select ?, 'InspectionTemplate', o.id, v.n, "
                        + "case when v.n = " + VERSIONS + " then 'ACTIVE' else 'SUPERSEDED' end, 2, 'FIXED', "
                        + "'{\"formatVersion\":1}'::jsonb, decode(repeat('00', 32), 'hex'), 20, ?, ? "
                        + "from owners o cross join generate_series(1, " + VERSIONS + ") as v(n)")) {
                    insert.setObject(1, tenant.value());
                    insert.setObject(2, ACTOR);
                    insert.setObject(3, ACTOR);
                    insert.executeUpdate();
                }
                List<UUID> ids = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("select id from owners limit " + PAGE)) {
                    while (rs.next()) {
                        ids.add(rs.getObject(1, UUID.class));
                    }
                }
                statement.execute("alter table sheet_version enable trigger user");
                connection.commit();
                statement.execute("analyze sheet_version");
                return ids;
            }
        }
    }

    private record Cost(long buffers, double millis, String plan) {
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

    @Test
    void theSummaryOfAPageOfFiftyTemplatesTouchesAFractionOfAFortyThousandRowTableAndUsesTheIndex()
            throws SQLException, IOException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        try {
            measure(tenant);
        } finally {
            clear(tenant);
        }
    }

    /** Leaves the table as it was found: other tests of the same database read its statistics. */
    private static void clear(TenantId tenant) throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setTenant(connection, tenant);
            try (var statement = connection.createStatement()) {
                statement.execute("alter table sheet_version disable trigger user");
                statement.execute("delete from sheet_version");
                statement.execute("alter table sheet_version enable trigger user");
            }
            connection.commit();
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("vacuum analyze sheet_version");
            }
        }
    }

    private static void measure(TenantId tenant) throws SQLException, IOException {
        List<UUID> page = seed(tenant);
        String marks = String.join(", ", java.util.Collections.nCopies(page.size(), "?"));
        Object[] parameters = new Object[1 + page.size()];
        parameters[0] = tenant.value();
        for (int i = 0; i < page.size(); i++) {
            parameters[i + 1] = page.get(i);
        }

        Cost summary = explain(tenant, String.format(SUMMARIES, marks), parameters);
        Cost one = explain(tenant, String.format(SUMMARIES, "?"), tenant.value(), page.get(0));
        Cost everything = explain(tenant, "select owner_id, max(version_no) from sheet_version where tenant_id = ? "
                + "and owner_object = 'InspectionTemplate' and deleted_at is null group by owner_id", tenant.value());
        long rows = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from sheet_version"));
        long tableBytes = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select pg_total_relation_size('sheet_version')"));

        String report = "templates: " + TEMPLATES + ", versions each: " + VERSIONS + ", rows: " + rows + "\n"
                + "table with its indexes (bytes): " + tableBytes + "\n"
                + "summary of a page of " + PAGE + " templates: " + summary.buffers() + " pages, " + summary.millis()
                + " ms\n"
                + "summary of one template: " + one.buffers() + " pages, " + one.millis() + " ms\n"
                + "the same grouping over every template: " + everything.buffers() + " pages, " + everything.millis()
                + " ms\n"
                + "page plan: " + summary.plan().replaceAll("\\s+", " ") + "\n";
        Path out = Path.of("target/it-logs/template-list-cost.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);

        assertThat(rows).isEqualTo((long) TEMPLATES * VERSIONS);
        assertThat(summary.buffers()).as(report).isLessThan(everything.buffers() / 5);
        assertThat(one.buffers()).isLessThan(summary.buffers());
        assertThat(summary.plan()).as("the index of the owner is used, not a scan of the table")
                .contains("sheet_version_owner_number").doesNotContain("Seq Scan");
    }
}
