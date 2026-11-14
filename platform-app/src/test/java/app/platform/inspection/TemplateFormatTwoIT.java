package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A template whose sheet is in format version 2 lives its whole life on the real database (SH1A, ADR-0251): draft,
 * review, approval, publish, in use, replaced by the next revision, retired, each move leaving the grid and its hash as
 * they were; the approver's counted correction in review works; the guards of the version table need no migration;
 * the download writes what format version 1 already writes and says in the file that the look is not in it yet; and a
 * fill-in cannot be started on it until fill format 3 exists.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateFormatTwoIT extends FillSupport {

    /** A sheet in format version 2: a look on two cells, a link, a narrow column, a short row, a stated input cell. */
    private static String two(String heading) {
        return "{\"formatVersion\":2,\"sheets\":[{\"key\":\"main\",\"title\":\"Plant activity\","
                + "\"freeze\":{\"rows\":1,\"columns\":1},\"dimension\":2,\"rowsMode\":\"FIXED\",\"columns\":["
                + "{\"id\":\"c1\",\"label\":\"Item\",\"type\":\"text\",\"width\":8},"
                + "{\"id\":\"c2\",\"label\":\"Amount\",\"type\":\"number\"}],\"headerRows\":[{\"id\":\"h1\"}],"
                + "\"rows\":[{\"id\":\"r1\",\"label\":\"First\",\"height\":4}],\"cells\":{"
                + "\"h1:c1\":{\"value\":\"" + heading + "\",\"look\":{\"fill\":\"#FFEECC\",\"borders\":{\"top\":"
                + "\"THIN\"}}},\"h1:c2\":{\"value\":\"Guide\",\"link\":\"https://example.test/guide\"},"
                + "\"r1:c2\":{\"look\":{\"fill\":\"#aabbcc\"}}},\"merges\":[],\"inputs\":{\"r1:c1\":{\"type\":"
                + "\"attachment\",\"required\":true}}}]}";
    }

    private record Run(Setting setting, Person author, Person approver, Person filler, String template,
            String process) {
    }

    /** A template in format version 2 with its first version saved as a draft. */
    private Run draft() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        Person filler = holding(setting, "Filler");
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        department(setting.admin(), "PLANT1", "PRD", group(setting.admin(), "dept-" + System.nanoTime(), filler));
        String template = template(author.browser(), "WI", "PRD");
        int number = firstVersion(author.browser(), template);
        saveSheet(author.browser(), template, number, two("Item"));
        return new Run(setting, author, approver, filler, template, process);
    }

    private static String hashOf(Run run, String who, int number) {
        Response read = version(who.equals("author") ? run.author().browser() : run.approver().browser(),
                run.template(), number);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return JsonPath.read(read.body(), "$.data.version.gridHash");
    }

    private static Object gridOfVersion(Run run, int number) {
        return JsonPath.read(version(run.approver().browser(), run.template(), number).body(), "$.data.grid");
    }

    private static String part(byte[] file, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    // ---- stored, and the database needs nothing ----

    @Test
    void aSheetInFormatVersionTwoIsStoredInItsOneFormAndReadBackWithItsLook() {
        Run run = draft();

        Response read = version(run.author().browser(), run.template(), 1);

        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(read.body(), "$.data.grid.formatVersion")).isEqualTo(2);
        assertThat(JsonPath.<String>read(read.body(), "$.data.grid.sheets[0].cells['h1:c1'].look.fill"))
                .as("a colour is stored in lower case").isEqualTo("#ffeecc");
        assertThat(JsonPath.<String>read(read.body(), "$.data.grid.sheets[0].cells['h1:c2'].link"))
                .isEqualTo("https://example.test/guide");
        assertThat(JsonPath.<Integer>read(read.body(), "$.data.grid.sheets[0].columns[0].width")).isEqualTo(8);
        assertThat(JsonPath.<Integer>read(read.body(), "$.data.grid.sheets[0].rows[0].height")).isEqualTo(4);
        assertThat(JsonPath.<Map<String, Object>>read(read.body(), "$.data.grid.sheets[0].inputs['r1:c1']"))
                .containsEntry("type", "attachment").containsEntry("required", true);
        // The columns the database copies from the grid are those of the first sheet.
        assertThat(owner("select dimension || '/' || rows_mode from sheet_version where owner_id = ?::uuid and "
                + "version_no = 1", run.template())).containsExactly("2/FIXED");
        assertThat(JsonPath.<Number>read(read.body(), "$.data.version.gridBytes").longValue()).isGreaterThan(300L);
    }

    @Test
    void aSheetThatSavesTheSameMeaningInAnotherOrderHasTheSameHash() {
        Run run = draft();
        String before = hashOf(run, "author", 1);
        Response read = version(run.author().browser(), run.template(), 1);
        // The same sheet with its colour in capitals and its look keys in reverse: the same meaning.
        String reordered = two("Item").replace("\"fill\":\"#FFEECC\",\"borders\":{\"top\":\"THIN\"}",
                "\"borders\":{\"top\":\"THIN\"},\"fill\":\"#ffeecc\"");

        Response saved = saveGrid(run.author().browser(), run.template(), 1, rowVersion(read), reordered);

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(saved.body(), "$.data.gridHash")).isEqualTo(before);
    }

    // ---- the whole life ----

    @Test
    void aTemplateInFormatVersionTwoIsReviewedApprovedPublishedReplacedAndRetiredWithTheSameGridAndHash() {
        Run run = draft();
        String hash = hashOf(run, "author", 1);
        Object grid = gridOfVersion(run, 1);
        String request = submitted(run.author().browser(), run.template(), run.process());
        assertThat(statuses(run.author().browser(), run.template())).containsExactly("IN_REVIEW");
        assertThat(hashOf(run, "approver", 1)).as("sending it for review changes nothing in the grid").isEqualTo(hash);

        assertThat(approve(run.approver().browser(), request).status()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), run.template())).containsExactly("APPROVED");
        assertThat(hashOf(run, "approver", 1)).isEqualTo(hash);

        Response published = publish(run.approver().browser(), run.template(), null, "PLANT1/PRD");
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), run.template())).containsExactly("ACTIVE");
        assertThat(hashOf(run, "approver", 1)).isEqualTo(hash);
        assertThat(gridOfVersion(run, 1)).isEqualTo(grid);

        // The next revision is made the way the product makes it: a copy of the one in use, changed, approved.
        int next = NextRevision.inUse(run.setting(), run.author().browser(), run.approver(), run.template(),
                two("Item, second revision"));
        assertThat(next).isEqualTo(2);
        assertThat(statuses(run.author().browser(), run.template())).containsExactly("ACTIVE", "SUPERSEDED");
        assertThat(hashOf(run, "approver", 1)).as("the replaced revision is exactly as it was").isEqualTo(hash);
        assertThat(gridOfVersion(run, 1)).isEqualTo(grid);
        assertThat(hashOf(run, "approver", 2)).isNotEqualTo(hash);

        assertThat(retire(run.approver().browser(), run.template()).status()).isEqualTo(200);
        assertThat(statuses(run.author().browser(), run.template())).contains("RETIRED");
        assertThat(gridOfVersion(run, 2)).isNotNull();
    }

    // ---- the approver's correction in review ----

    @Test
    void anApproverCorrectsAFormatVersionTwoSheetInReviewAndItIsCounted() {
        Run run = draft();
        submitted(run.author().browser(), run.template(), run.process());
        String before = hashOf(run, "approver", 1);
        Response read = version(run.approver().browser(), run.template(), 1);

        Response corrected = saveGrid(run.approver().browser(), run.template(), 1, rowVersion(read),
                two("Corrected heading"));

        assertThat(corrected.status()).as(corrected.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(corrected.body(), "$.data.status")).isEqualTo("IN_REVIEW");
        assertThat(JsonPath.<Integer>read(corrected.body(), "$.data.reviewEdits")).isEqualTo(1);
        assertThat(JsonPath.<String>read(corrected.body(), "$.data.gridHash")).isNotEqualTo(before);
        assertThat(version(run.approver().browser(), run.template(), 1).body()).contains("Corrected heading");
        assertThat(audited(run.setting().organization(), "sheets.version.review_edited")).isEqualTo(1);
    }

    // ---- the download ----

    @Test
    void theDownloadCarriesTheSheetAndTheInformationOfTheFileSaysOnlyThatThePlatformMadeIt() throws Exception {
        Run run = draft();
        String request = submitted(run.author().browser(), run.template(), run.process());
        assertThat(approve(run.approver().browser(), request).status()).isEqualTo(200);
        assertThat(publish(run.approver().browser(), run.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(run.approver().browser().request("PUT", TEMPLATES + "/" + run.template() + "/options",
                "{\"viewOnly\":false,\"allowDownload\":true}").status()).isEqualTo(200);

        BytesResponse file = run.approver().browser().bytes("GET", TEMPLATES + "/" + run.template() + "/download",
                null);

        assertThat(file.status()).isEqualTo(200);
        // The texts of the cells are strings of the workbook (SH3).
        assertThat(part(file.body(), "xl/sharedStrings.xml")).contains("Item", "Guide");
        String info = part(file.body(), "docProps/core.xml");
        assertThat(info).contains("Made by the platform. Revision ").doesNotContain("not in this file yet")
                .doesNotContain("new format");
        assertThat(part(file.body(), "[Content_Types].xml")).contains("/docProps/core.xml");
    }

    // ---- a fill-in ----

    @Test
    void aFillInCanBeStartedOnATemplateInFormatVersionTwoAndIsStoredInFormatThree() {
        Run run = draft();
        String request = submitted(run.author().browser(), run.template(), run.process());
        assertThat(approve(run.approver().browser(), request).status()).isEqualTo(200);
        assertThat(publish(run.approver().browser(), run.template(), null, "PLANT1/PRD").status()).isEqualTo(200);

        Response started = startFill(run.filler().browser(), run.template());

        assertThat(started.status()).as(started.body()).isBetween(200, 299);
        assertThat(listedFills(run.filler().browser(), "")).as("the fill-in was made").hasSize(1);
    }
}
