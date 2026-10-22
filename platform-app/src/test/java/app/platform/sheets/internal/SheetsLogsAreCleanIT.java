package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the sheets flows (FA3, ADR-0012, ADR-0186): sheets are started, saved, listed, read and refused,
 * with the application and the security framework logging at debug level. A grid is the organization's own text: a
 * column label, a picklist value, the text of a cell, a row label, the name of the record it belongs to. None of it is
 * ever logged, written to the audit trail, put in an event or echoed by a refusal. The audit trail does show that the
 * sheets changed, by identifiers, sizes and counts.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + SheetsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class SheetsLogsAreCleanIT extends SheetSupport {

    static final String LOG_FILE = "target/it-logs/sheets-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedInAGridIsLoggedOrWrittenToTheTrailOrEchoedByARefusal() throws IOException {
        Setting setting = setting();
        String owner = template(setting.admin());
        String label = typed("column-label");
        String pick = typed("picklist-value");
        String cell = typed("cell-text");
        String rowLabel = typed("row-label");
        String refusedMarkup = typed("refused-markup");
        String refusedKey = typed("refused-key");

        firstVersion(setting.admin(), owner, 2);
        Map<String, Object> grid = gridMap(read(setting.admin(), owner, 1));
        String first = (String) list(grid, "columns").get(0).get("id");
        list(grid, "columns").get(0).put("label", label);
        list(grid, "columns").add(GridDocs.map("id", "c2", "label", "Result", "type", "picklist", "picklist",
                GridDocs.map("values", List.of(pick, "Fail"))));
        list(grid, "headerRows").add(GridDocs.map("id", "h1"));
        list(grid, "rows").add(GridDocs.map("id", "r1", "label", rowLabel));
        cells(grid).put("h1:" + first, GridDocs.map("value", cell, "style", GridDocs.map("bold", true)));
        cells(grid).put("r1:c2", GridDocs.map("value", pick, "kind", "input"));
        Response saved = save(setting.admin(), owner, 1, 0, json(grid));
        Response listed = setting.admin().get(versions(owner));
        Response opened = read(setting.admin(), owner, 1);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");
        Response second = startFrom(setting.admin(), owner, null);

        list(grid, "columns").get(0).put("label", "<b>" + refusedMarkup + "</b>");
        grid.put(refusedKey, 1);
        Response markup = save(setting.admin(), owner, 2, 0, json(grid));
        Response keyed = save(setting.admin(), owner, 2, 0, "{\"" + refusedKey + "\":\"" + refusedMarkup + "\"");
        Response late = save(setting.admin(), owner, 1, 1, json(grid));
        Response reader = save(as(setting.organization(), reader(setting, owner)), owner, 2, 0, json(grid));
        String trail = setting.admin().get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        assertThat(log).as("the log has content to check").contains("platform.http");
        List<String> secrets = List.of(label, pick, cell, rowLabel, refusedMarkup, refusedKey);
        for (String text : secrets) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            assertThat(trail).as("typed text is not in the audit trail: " + text).doesNotContain(text);
        }
        for (Response refused : List.of(markup, keyed, late, reader)) {
            assertThat(refused.status()).as(refused.body()).isGreaterThanOrEqualTo(400);
            for (String text : List.of(refusedMarkup, refusedKey)) {
                assertThat(refused.body()).as("a refusal does not echo what was typed").doesNotContain(text);
            }
        }
        for (Response answer : List.of(listed, saved)) {
            for (String text : secrets) {
                assertThat(answer.body()).as("only the one call that reads a grid carries its text")
                        .doesNotContain(text);
            }
        }
        assertThat(opened.body()).as("the call that reads a grid does carry it").contains(label);
        assertThat(trail).as("the trail shows the sheets changed, by counts and sizes")
                .contains("sheets.version.created").contains("sheets.version.saved");
        assertThat(JsonPath.<List<String>>read(setting.admin().get("/api/v1/audit-events?limit=200").body(),
                "$.data[?(@.type=='sheets.version.saved')].attributes.cells")).contains("2");
    }
}
