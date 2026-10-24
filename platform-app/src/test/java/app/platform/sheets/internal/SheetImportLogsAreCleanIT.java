package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.BytesResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the upload of a spreadsheet file (UI3B, ADR-0012, ADR-0195): files are previewed, uploaded and
 * refused with the application and the security framework logging at debug level. The cells of a file, its column
 * names and a text that is refused are the organization's own text, and the name of a file or of its sheet is not the
 * platform's business either. None of it is ever logged, written to the audit trail, put in a metric or echoed by a
 * refusal. The audit trail does show that a draft was made from a file, by its format, sizes and counts.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + SheetImportLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class SheetImportLogsAreCleanIT extends SheetSupport {

    static final String LOG_FILE = "target/it-logs/sheet-import-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingAFileHoldsIsLoggedOrWrittenToTheTrailOrEchoedByARefusal() throws IOException {
        Setting setting = setting();
        String owner = template(setting.admin());
        String label = typed("column-label");
        String cell = typed("cell-text");
        String sheetName = typed("sheet-name");
        String refusedText = typed("refused-text");
        String refusedMarkup = typed("refused-markup");

        byte[] good = new XlsxFile()
                .row(1, XlsxFile.text("A1", label, XlsxFile.BOLD))
                .row(2, XlsxFile.text("A2", cell)).bytes();
        BytesResponse preview = setting.admin().bytes("POST", versions(owner) + "/import?format=xlsx&preview=true",
                good, "Content-Type", "application/octet-stream");
        BytesResponse refusedMarkupAnswer = setting.admin().bytes("POST", versions(owner) + "/import?format=csv",
                ("h\n<b>" + refusedMarkup + "</b>\n").getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/octet-stream");
        BytesResponse refusedLong = setting.admin().bytes("POST", versions(owner) + "/import?format=csv",
                ("h\n" + refusedText + "x".repeat(1_100) + "\n").getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/octet-stream");
        BytesResponse notAWorkbook = setting.admin().bytes("POST", versions(owner) + "/import?format=xlsx",
                (sheetName + refusedText).getBytes(StandardCharsets.UTF_8), "Content-Type",
                "application/octet-stream");
        BytesResponse made = setting.admin().bytes("POST", versions(owner) + "/import?format=xlsx", good,
                "Content-Type", "application/octet-stream");
        var listed = setting.admin().get(versions(owner));
        var opened = read(setting.admin(), owner, 1);
        String trail = setting.admin().get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));
        assertThat(made.status()).as(made.text()).isEqualTo(201);
        assertThat(preview.status()).as(preview.text()).isEqualTo(200);
        assertThat(log).as("the log has content to check").contains("platform.http");
        List<String> secrets = List.of(label, cell, sheetName, refusedText, refusedMarkup);
        for (String text : secrets) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            assertThat(trail).as("typed text is not in the audit trail: " + text).doesNotContain(text);
        }
        for (BytesResponse refused : List.of(refusedMarkupAnswer, refusedLong, notAWorkbook)) {
            assertThat(refused.status()).as(refused.text()).isEqualTo(400);
            for (String text : secrets) {
                assertThat(refused.text()).as("a refusal does not echo what was typed").doesNotContain(text);
            }
        }
        for (String answer : List.of(preview.text(), made.text(), listed.body())) {
            for (String text : secrets) {
                assertThat(answer).as("only the call that reads a grid carries its text").doesNotContain(text);
            }
        }
        assertThat(opened.body()).as("the call that reads a grid does carry it").contains(label).contains(cell);
        assertThat(trail).as("the trail shows a draft was made from a file, by counts and sizes")
                .contains("sheets.version.imported");
    }
}
