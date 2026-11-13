package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The upload of a workbook with format version 2 switched on (SH1B, ADR-0253), through the real application on a real
 * PostgreSQL: the sheet that is made is a document of format version 2 with the look of the file, the report lists the
 * visible worksheets so a person can choose, the choice is carried out, and a choice that is not in the file stores
 * nothing. The same upload with the switch off is {@link SheetImportIT}.
 */
@PlatformIntegrationTest
class SheetImportFormatTwoIT extends SheetSupport {

    private static final String OCTET = "application/octet-stream";

    private static BytesResponse upload(TestBrowser browser, String owner, byte[] file, String query) {
        return browser.bytes("POST", versions(owner) + "/import" + query, file, "Content-Type", OCTET);
    }

    private static byte[] looked() {
        return RichWorkbook.file()
                .row(1, XlsxFile.text("A1", "Item", RichWorkbook.HEADING), XlsxFile.text("B1", "Score"))
                .row(2, XlsxFile.text("A2", "Door closes"), "<c r=\"B2\" s=\"" + RichWorkbook.YELLOW_BOX + "\"/>")
                .bytes();
    }

    private static byte[] twoWorksheets() {
        return new XlsxFile()
                .row(1, XlsxFile.text("A1", "Notes about the form"))
                .extraSheet("Data", null, XlsxFile.sheetXml("<row r=\"1\">" + XlsxFile.text("A1", "Item")
                        + XlsxFile.text("B1", "Result") + "</row><row r=\"2\">" + XlsxFile.text("A2", "Door")
                                + "</row>"))
                .extraSheet("Hidden", "hidden", XlsxFile.sheetXml("<row r=\"1\">" + XlsxFile.text("A1", "x")
                        + "</row>"))
                .bytes();
    }

    @Test
    void aWorkbookBecomesAFormatTwoSheetWithTheLookOfTheFile() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser author = as(setting.organization(), author(setting, owner));

        BytesResponse made = upload(author, owner, looked(), "?format=xlsx");

        assertThat(made.status()).as(made.text()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(made.text(), "$.data.report.formatTwo")).isTrue();
        var opened = read(author, owner, 1);
        assertThat(JsonPath.<Integer>read(opened.body(), "$.data.grid.formatVersion")).isEqualTo(2);
        assertThat(JsonPath.<String>read(opened.body(), "$.data.grid.sheets[0].title")).isEqualTo("One");
        assertThat(JsonPath.<List<String>>read(opened.body(), "$.data.grid.sheets[0].columns[*].label"))
                .containsExactly("Item", "Score");
        assertThat(opened.body()).as("the look of the file is stored as keys and checked colours")
                .contains("\"fill\":\"#ffff00\"").contains("\"font\":\"SANS_HUMANIST\"");
        assertThat(opened.body()).as("no family name of the file is stored").doesNotContain("Arial")
                .doesNotContain("Calibri");
        assertThat(countRows(setting.organization(), owner)).isEqualTo(1);
    }

    @Test
    void theReportListsTheVisibleWorksheetsAndTheChoiceIsCarriedOut() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser author = as(setting.organization(), author(setting, owner));

        BytesResponse preview = upload(author, owner, twoWorksheets(), "?format=xlsx&preview=true");

        assertThat(preview.status()).as(preview.text()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(preview.text(), "$.data.report.worksheets[*].title"))
                .containsExactly("One", "Data");
        assertThat(JsonPath.<Integer>read(preview.text(), "$.data.report.worksheet")).isEqualTo(0);
        assertThat(JsonPath.<List<String>>read(preview.text(), "$.data.report.notes[*].code"))
                .contains("other_sheets", "hidden_sheets");
        assertThat(countRows(setting.organization(), owner)).isZero();

        BytesResponse made = upload(author, owner, twoWorksheets(), "?format=xlsx&worksheet=1");

        assertThat(made.status()).as(made.text()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.report.worksheet")).isEqualTo(1);
        var opened = read(author, owner, 1);
        assertThat(JsonPath.<String>read(opened.body(), "$.data.grid.sheets[0].title")).isEqualTo("Data");
        assertThat(JsonPath.<List<String>>read(opened.body(), "$.data.grid.sheets[0].columns[*].label"))
                .containsExactly("Item", "Result");
    }

    @Test
    void aWorksheetThatIsNotInTheFileIsRefusedAndNothingIsStored() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser author = as(setting.organization(), author(setting, owner));

        for (String query : List.of("?format=xlsx&worksheet=5", "?format=xlsx&worksheet=-1")) {
            BytesResponse refused = upload(author, owner, twoWorksheets(), query);

            assertThat(refused.status()).as(refused.text()).isEqualTo(400);
            assertThat(refused.text()).doesNotContain("Data").doesNotContain("Notes about the form");
        }
        assertThat(countRows(setting.organization(), owner)).isZero();
    }
}
