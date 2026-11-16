package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * What the Home page that compares the plants costs when an organization has many fill-ins (MC3, ADR-0262): 11,520
 * fill-in headers, 24 plants with 40 forms in each of 12 months, written straight into the record table with the typed
 * copies the indexed fields have (the guards are switched off for the load, as the cost test of the other dashboard
 * does, because the point is the cost of reading what a long life leaves). The page is asked for as a person who may
 * see every fill-in; the time of each answer and the number of headers behind it are written to
 * {@code target/it-logs/management-home-cost.txt} so the sprint document can quote them.
 *
 * <p>The assertions are what must hold whatever the machine: the numbers are the ones the load makes, nothing was
 * dropped or doubled, no section failed, and the page answers well inside a generous bound. The page reads the headers
 * a page of 200 at a time through the record store's own query, so its cost is the record store's and is bounded by the
 * setting {@code platform.inspection.home.max-scanned}.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ManagementHomeCostIT extends ManagementHomeSupport {

    private static final int PLANTS = 24;
    private static final int FORMS = 40;
    private static final int MONTHS = 12;
    private static final int ROWS = PLANTS * FORMS * MONTHS;
    private static final long MOST_MILLIS = 20_000;

    /** The typed column that holds each indexed field of the fill-in object in this organization. */
    private static Map<String, String> slots(TenantId tenant) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            Map<String, String> found = new HashMap<>();
            try (var statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("select field_api_name, slot from field_slot "
                            + "where object_api_name = 'InspectionFill' and deleted_at is null")) {
                while (rs.next()) {
                    found.put(rs.getString(1), rs.getString(2));
                }
            }
            return found;
        });
    }

    /** The value of a field of the load as SQL, from {@code n}, the number of the row (0 to the last). */
    private static String expression(String field, String form) {
        return switch (field) {
            case "templateId" -> "'" + form + "'";
            case "name" -> "'Plant activity'";
            case "application" -> "'management-core'";
            case "plant" -> "'PL' || lpad(((n % " + PLANTS + ") + 1)::text, 2, '0')";
            case "period" -> "to_char(date_trunc('month', timezone('UTC', now())) - (((n / " + (PLANTS * FORMS)
                    + ")::int) * interval '1 month'), 'YYYY-MM')";
            case "status" -> "'APPROVED'";
            case "score" -> "(60 + (n % " + PLANTS + "))::numeric";
            case "result" -> "'ACTION_PLAN'";
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static void seed(TenantId tenant, UUID owner, String form) throws SQLException {
        Map<String, String> slots = slots(tenant);
        List<String> fields = List.of("templateId", "name", "application", "plant", "period", "status", "score",
                "result");
        StringBuilder payload = new StringBuilder();
        StringBuilder columns = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (String field : fields) {
            payload.append(payload.length() == 0 ? "" : ", ").append('\'').append(field).append("', ")
                    .append(expression(field, form));
            String slot = slots.get(field);
            if (slot != null) {
                columns.append(", ").append(slot);
                values.append(", ").append(expression(field, form)).append(slot.startsWith("s_num") ? "::float8" : "");
            }
        }
        String sql = "insert into object_record (tenant_id, object_api_name, owner_id, payload" + columns
                + ", created_by, updated_by) select ?, 'InspectionFill', ?, jsonb_build_object(" + payload + ")"
                + values + ", ?, ? from generate_series(0, " + (ROWS - 1) + ") as g(n)";
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setTenant(connection, tenant);
            try (var statement = connection.createStatement()) {
                statement.execute("alter table object_record disable trigger user");
                try (var insert = connection.prepareStatement(sql)) {
                    insert.setObject(1, tenant.value());
                    insert.setObject(2, owner);
                    insert.setObject(3, owner);
                    insert.setObject(4, owner);
                    insert.executeUpdate();
                }
                statement.execute("alter table object_record enable trigger user");
            }
            connection.commit();
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("analyze object_record");
            }
        }
    }

    @Test
    void aYearOfFillInsOfManyPlantsAndFormsIsAnsweredWithTheNumbersTheLoadMakes() throws SQLException, IOException {
        Plants plants = scored();
        TestBrowser approver = plants.world().approver().browser();
        TenantId tenant = plants.world().setting().organization().id();
        seed(tenant, UUID.fromString(plants.world().approver().userId()), plants.template());

        long first = System.nanoTime();
        Response cold = approver.postJson(HOME, "{}");
        long coldMillis = (System.nanoTime() - first) / 1_000_000;
        long second = System.nanoTime();
        Response warm = approver.postJson(HOME, "{\"span\":12}");
        long warmMillis = (System.nanoTime() - second) / 1_000_000;

        assertThat(cold.status()).as(cold.body()).isEqualTo(200);
        assertThat(warm.status()).as(warm.body()).isEqualTo(200);
        String report = "fill-in headers: " + ROWS + " (" + PLANTS + " plants, " + FORMS + " forms, " + MONTHS
                + " months)\n"
                + "answer for 6 months: " + coldMillis + " ms, " + cold.body().length() + " bytes\n"
                + "answer for 12 months: " + warmMillis + " ms, " + warm.body().length() + " bytes\n";
        Path out = Path.of("target/it-logs/management-home-cost.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);

        assertThat(JsonPath.<List<Object>>read(cold.body(), "$.data.problems")).as(report).isEmpty();
        assertThat(JsonPath.<Number>read(cold.body(), "$.data.tiles.plantsSubmitted.value").intValue())
                .as(report).isEqualTo(PLANTS);
        assertThat(JsonPath.<Number>read(cold.body(), "$.data.tiles.plantsSubmitted.whole").intValue())
                .as("the 24 of the load and the two plants the document is published to").isEqualTo(PLANTS + 2);
        assertThat(JsonPath.<Boolean>read(cold.body(), "$.data.tiles.plantsSubmitted.atLeast")).isFalse();
        assertThat(JsonPath.<Number>read(cold.body(), "$.data.tiles.averageScore.value").doubleValue())
                .as("the plants score 60 to 83: the average of the plants is 71.5, not the average of the fill-ins")
                .isEqualTo(71.5);
        assertThat(JsonPath.<Number>read(cold.body(), "$.data.tiles.plantsBelowLimit.value").intValue())
                .isEqualTo(PLANTS);
        assertThat(JsonPath.<List<Object>>read(cold.body(), "$.data.charts.scoresByPlant"))
                .as("the load and the two published plants with no score").hasSize(PLANTS + 2);
        assertThat(JsonPath.<String>read(cold.body(), "$.data.charts.scoresByPlant[0].plant")).isEqualTo("PL24");
        assertThat(JsonPath.<List<Object>>read(warm.body(), "$.data.months")).hasSize(MONTHS);
        assertThat(JsonPath.<Number>read(warm.body(), "$.data.charts.fillsByMonth[11].approved").intValue())
                .as("the load has the same number of fill-ins in each month").isEqualTo(PLANTS * FORMS);
        assertThat(coldMillis).as(report).isLessThan(MOST_MILLIS);
        assertThat(warmMillis).as(report).isLessThan(MOST_MILLIS);
    }
}
