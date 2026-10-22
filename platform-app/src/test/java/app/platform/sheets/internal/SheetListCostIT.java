package app.platform.sheets.internal;

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
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cost of reading the history of a sheet (FA3, ADR-0173 section 5, ADR-0186): the list of versions never selects
 * the grid. A sheet of 200 versions, each with a grid of about 100 KB of text that does not compress away (so about
 * 20 MB in all), is listed and the pages the database touched are counted with {@code explain (analyze, buffers)},
 * against the cost of reading every grid. The numbers are written to
 * {@code target/it-logs/sheets-list-cost.txt} so that the sprint document can quote them; the assertions are the
 * relations that must hold.
 */
@PlatformIntegrationTest
class SheetListCostIT {

    private static final int VERSIONS = 200;
    private static final int GRID_CHARS = 100_000;
    private static final UUID ACTOR = ActorId.SYSTEM.value();

    private static final String LIST = "select version_no, status, dimension, rows_mode, grid_bytes, "
            + "encode(grid_hash, 'hex') as grid_hash, version, created_at, updated_at, submitted_at, decided_at "
            + "from sheet_version where tenant_id = ? and owner_object = 'Subject' and owner_id = ? "
            + "and deleted_at is null order by version_no desc";

    private static String randomHex(int chars) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[chars / 2];
        random.nextBytes(bytes);
        StringBuilder out = new StringBuilder(chars);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static void seed(TenantId tenant, UUID owner) throws SQLException {
        String pad = randomHex(GRID_CHARS);
        try (Connection connection = TestDatabase.ownerConnection()) {
            for (int number = 1; number <= VERSIONS; number++) {
                try (PreparedStatement insert = connection.prepareStatement("insert into sheet_version (tenant_id, "
                        + "owner_object, owner_id, version_no, dimension, rows_mode, grid, grid_hash, grid_bytes, "
                        + "created_by, updated_by) values (?, 'Subject', ?, ?, 2, 'FIXED', "
                        + "jsonb_build_object('formatVersion', 1, 'pad', ?::text), decode(repeat('00', 32), 'hex'), "
                        + "?, ?, ?)")) {
                    insert.setObject(1, tenant.value());
                    insert.setObject(2, owner);
                    insert.setInt(3, number);
                    insert.setString(4, pad.substring(number % 50) + number);
                    insert.setInt(5, GRID_CHARS);
                    insert.setObject(6, ACTOR);
                    insert.setObject(7, ACTOR);
                    insert.executeUpdate();
                }
                if (number < VERSIONS) {
                    for (String status : new String[] {"IN_REVIEW", "REJECTED"}) {
                        try (PreparedStatement move = connection.prepareStatement("update sheet_version set status = "
                                + "?, version = version + 1, updated_by = updated_by where owner_id = ? and "
                                + "version_no = ?")) {
                            move.setString(1, status);
                            move.setObject(2, owner);
                            move.setInt(3, number);
                            move.executeUpdate();
                        }
                    }
                }
            }
            try (var statement = connection.createStatement()) {
                statement.execute("analyze sheet_version");
            }
        }
    }

    /** What the plan says: the pages touched and the time. */
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
    void theListOfAHundredsOfVersionsTouchesAFractionOfWhatReadingTheGridsWould() throws SQLException, IOException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID owner = UUID.randomUUID();
        seed(tenant, owner);

        Cost list = explain(tenant, LIST, tenant.value(), owner);
        Cost newest = explain(tenant, "select grid::text from sheet_version where tenant_id = ? and owner_object = "
                + "'Subject' and owner_id = ? and version_no = ?", tenant.value(), owner, VERSIONS);
        Cost everyGrid = explain(tenant, "select sum(length(grid::text)) from sheet_version where tenant_id = ? and "
                + "owner_object = 'Subject' and owner_id = ?", tenant.value(), owner);
        long rows = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from sheet_version where owner_id = ?", owner));
        long tableBytes = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select pg_total_relation_size('sheet_version')"));

        String report = "versions: " + rows + "\n"
                + "grid size each (characters): " + GRID_CHARS + "\n"
                + "table with its out-of-line storage and indexes (bytes, whole table): " + tableBytes + "\n"
                + "list of the versions: " + list.buffers() + " pages, " + list.millis() + " ms\n"
                + "one grid (the newest): " + newest.buffers() + " pages, " + newest.millis() + " ms\n"
                + "every grid: " + everyGrid.buffers() + " pages, " + everyGrid.millis() + " ms\n"
                + "list plan: " + list.plan().replaceAll("\\s+", " ") + "\n";
        Path out = Path.of("target/it-logs/sheets-list-cost.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);

        assertThat(rows).isEqualTo(VERSIONS);
        assertThat(list.buffers()).as("the list touches few pages\n" + report).isLessThan(150);
        assertThat(everyGrid.buffers()).as("reading every grid touches many\n" + report).isGreaterThan(1_000);
        assertThat(list.buffers() * 20).as("the list costs a small part of reading the grids\n" + report)
                .isLessThan(everyGrid.buffers());
        assertThat(newest.buffers()).as("one grid costs a small part of all of them\n" + report)
                .isLessThan(everyGrid.buffers() / 20);
    }
}
