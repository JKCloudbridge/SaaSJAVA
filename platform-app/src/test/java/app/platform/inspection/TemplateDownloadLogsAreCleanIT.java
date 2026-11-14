package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.BytesResponse;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the file a template is downloaded as (SH3, ADR-0012, ADR-0253): the sheet is written by the library
 * with its look and its formulas, with the application and the security framework logging at debug level. Nothing in
 * the sheet (a heading, a text), the template's number or the name of the file is ever logged, whatever the library
 * does while it builds the workbook. The audit trail of the download is checked by {@code TemplateSheetOptionsIT}.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + TemplateDownloadLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.apache.poi=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class TemplateDownloadLogsAreCleanIT extends FillSupport {

    static final String LOG_FILE = "target/it-logs/template-download-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @Test
    void nothingOfTheSheetTheNumberOrTheFileNameIsLoggedWhenATemplateIsDownloaded() throws IOException {
        World world = world();
        assertThat(world.approver().browser().request("PUT", TEMPLATES + "/" + world.template() + "/options",
                "{\"viewOnly\":false,\"allowDownload\":true}").status()).isEqualTo(200);
        String number = JsonPath.read(lifecycle(world.approver().browser(), world.template()).body(), "$.data.number");

        BytesResponse file = world.filler().browser().bytes("GET", TEMPLATES + "/" + world.template() + "/download",
                null);

        assertThat(file.status()).isEqualTo(200);
        String log = Files.readString(Path.of(LOG_FILE));
        assertThat(log).as("the log has content to check").contains("platform.http");
        assertThat(log).doesNotContain("Checklist").doesNotContain(number).doesNotContain(number.replace('/', '-'))
                .doesNotContain("Rev No.");
    }
}
