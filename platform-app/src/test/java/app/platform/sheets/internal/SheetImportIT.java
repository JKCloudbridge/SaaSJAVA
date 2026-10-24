package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The upload of a spreadsheet file into a sheet (UI3B, ADR-0195), through the real application on a real PostgreSQL:
 * who may do it (allowed, read only, no access, another organization, forged host, platform administrator, nobody),
 * what it makes (a draft with the content, a preview that saves nothing, the shape of a sheet that has versions), what
 * it refuses (a draft that exists, a file that is not what it says, a limit) and that a refusal stores nothing, and a
 * race of two uploads in which one draft wins.
 */
@PlatformIntegrationTest
class SheetImportIT extends SheetSupport {

    private static final String OCTET = "application/octet-stream";

    private static BytesResponse upload(TestBrowser browser, String owner, byte[] file, String query) {
        return browser.bytes("POST", versions(owner) + "/import" + query, file, "Content-Type", OCTET);
    }

    private static BytesResponse xlsx(TestBrowser browser, String owner, byte[] file) {
        return upload(browser, owner, file, "?format=xlsx");
    }

    private static byte[] workbook() {
        return new XlsxFile()
                .row(1, XlsxFile.text("A1", "Item", XlsxFile.BOLD), XlsxFile.text("B1", "Result", XlsxFile.BOLD))
                .row(2, XlsxFile.text("A2", "Door closes"), XlsxFile.text("B2", "pass"))
                .row(3, XlsxFile.text("A3", "Merged note"), XlsxFile.text("B3", "gone"))
                .widths(1, 1, 30).merge("A3:B3").bytes();
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ---- what it makes ----

    @Test
    void anAuthorMakesTheFirstVersionOfAnEmptySheetFromAWorkbook() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser author = as(setting.organization(), author(setting, owner));

        BytesResponse made = xlsx(author, owner, workbook());

        assertThat(made.status()).as(made.text()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(made.text(), "$.data.saved")).isTrue();
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.version.versionNo")).isEqualTo(1);
        assertThat(JsonPath.<String>read(made.text(), "$.data.version.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.version.dimension")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.report.columns")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.report.rows")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.report.mergedAreas")).isEqualTo(1);
        var opened = read(author, owner, 1);
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(opened.body(), "$.data.grid.columns[*].label"))
                .containsExactly("Item", "Result");
        assertThat(JsonPath.<Integer>read(opened.body(), "$.data.grid.columns[0].width")).isEqualTo(215);
        assertThat(JsonPath.<List<Object>>read(opened.body(), "$.data.grid.rows")).hasSize(2);
        assertThat(opened.body()).contains("Door closes").doesNotContain("gone");
        assertThat(countRows(setting.organization(), owner)).isEqualTo(1);
    }

    @Test
    void theVersionListNamesThePersonWhoLastChangedAVersion() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser author = as(setting.organization(), author(setting, owner));
        xlsx(author, owner, workbook());

        var list = author.get(versions(owner));

        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(list.body(), "$.data.versions[0].updatedBy")).isNotBlank();
        assertThat(list.body()).as("never an identifier of a person").doesNotContain("updatedById")
                .doesNotContain("updated_by");
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.limits.maxImportBytes")).isEqualTo(5_000_000);
    }

    @Test
    void aPreviewAnswersWithTheReportAndSavesNothing() {
        Setting setting = setting();
        String owner = template(setting.admin());

        BytesResponse preview = upload(setting.admin(), owner, workbook(), "?format=xlsx&preview=true");

        assertThat(preview.status()).as(preview.text()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(preview.text(), "$.data.saved")).isFalse();
        assertThat(JsonPath.<java.util.Map<String, Object>>read(preview.text(), "$.data")).doesNotContainKey("version");
        assertThat(JsonPath.<Integer>read(preview.text(), "$.data.report.columns")).isEqualTo(2);
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    @Test
    void aTextFileWithoutLabelsMakesColumnsNamedByPosition() {
        Setting setting = setting();
        String owner = template(setting.admin());

        BytesResponse made = upload(setting.admin(), owner, csv("a;b\n1;2\n"),
                "?format=csv&firstRowLabels=false");

        assertThat(made.status()).as(made.text()).isEqualTo(201);
        var opened = read(setting.admin(), owner, 1);
        assertThat(JsonPath.<List<String>>read(opened.body(), "$.data.grid.columns[*].label"))
                .containsExactly("Column 1", "Column 2");
        assertThat(JsonPath.<List<Object>>read(opened.body(), "$.data.grid.rows")).hasSize(2);
    }

    @Test
    void aSheetThatHasVersionsKeepsItsShapeAndOnlyTheColumnNamesComeIn() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 1);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");

        BytesResponse made = xlsx(setting.admin(), owner, workbook());

        assertThat(made.status()).as(made.text()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.version.versionNo")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.version.dimension")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(made.text(), "$.data.report.rows")).isZero();
        assertThat(JsonPath.<List<String>>read(made.text(), "$.data.report.notes[*].code"))
                .contains("rows_not_kept");
        var opened = read(setting.admin(), owner, 2);
        assertThat(JsonPath.<List<String>>read(opened.body(), "$.data.grid.columns[*].label"))
                .containsExactly("Item", "Result");
    }

    // ---- what it refuses, and stores nothing ----

    @Test
    void aDraftThatExistsRefusesTheUploadAndNamesIt() {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);

        BytesResponse refused = xlsx(setting.admin(), owner, workbook());

        assertThat(refused.status()).as(refused.text()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.text(), "$.error.message")).contains("version 1");
        assertThat(countRows(setting.organization(), owner)).isEqualTo(1);
    }

    @Test
    void aFileThatIsNotWhatItsFormatSaysIsRefusedAndNothingIsStored() {
        Setting setting = setting();
        String owner = template(setting.admin());
        byte[] macros = new XlsxFile().row(1, XlsxFile.text("A1", "h")).part("xl/vbaProject.bin", new byte[] {1})
                .bytes();

        for (BytesResponse refused : List.of(xlsx(setting.admin(), owner, csv("plain text")),
                xlsx(setting.admin(), owner, macros),
                upload(setting.admin(), owner, workbook(), "?format=csv"),
                upload(setting.admin(), owner, csv("a\n"), "?format=ods"),
                upload(setting.admin(), owner, new byte[0], "?format=csv"),
                upload(setting.admin(), owner, csv("h\n<b>x</b>\n"), "?format=csv"))) {
            assertThat(refused.status()).as(refused.text()).isEqualTo(400);
            assertThat(JsonPath.<String>read(refused.text(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    @Test
    void aFileOverTheLimitOfASheetIsRefusedWithTheLimitAndNothingIsStored() {
        Setting setting = setting();
        String owner = template(setting.admin());

        BytesResponse refused = upload(setting.admin(), owner, csv("h\n" + "x\n".repeat(501)), "?format=csv");

        assertThat(refused.status()).as(refused.text()).isEqualTo(400);
        assertThat(JsonPath.<String>read(refused.text(), "$.error.fields.file[0]")).contains("501").contains("500");
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    @Test
    void aFileOverTheSizeLimitIsRefusedBeforeItIsReadWhetherOrNotItsLengthIsAnnounced() {
        Setting setting = setting();
        String owner = template(setting.admin());
        byte[] big = new byte[5_000_001];
        java.util.Arrays.fill(big, (byte) 'a');

        // The server may refuse by the announced length and close the connection before the body is sent.
        Integer announced;
        try {
            announced = upload(setting.admin(), owner, big, "?format=csv").status();
        } catch (IllegalStateException e) {
            announced = 413;
        }
        var chunked = setting.admin().chunked("POST", versions(owner) + "/import?format=csv", big,
                "Content-Type", OCTET);

        assertThat(announced).isEqualTo(413);
        assertThat(chunked.status()).as(chunked.body()).isEqualTo(413);
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    @Test
    void anUploadNotSentAsABinaryBodyIsRefused() {
        Setting setting = setting();
        String owner = template(setting.admin());

        BytesResponse refused = setting.admin().bytes("POST", versions(owner) + "/import?format=csv", csv("a\n"),
                "Content-Type", "application/json");

        assertThat(refused.status()).isEqualTo(415);
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    // ---- who may ----

    @Test
    void aMemberWhoMayOnlyReadCannotUploadAndIsToldSo() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser reader = as(setting.organization(), reader(setting, owner));

        BytesResponse refused = xlsx(reader, owner, workbook());
        BytesResponse preview = upload(reader, owner, workbook(), "?format=xlsx&preview=true");

        for (BytesResponse answer : List.of(refused, preview)) {
            assertThat(answer.status()).as(answer.text()).isEqualTo(403);
            assertThat(JsonPath.<String>read(answer.text(), "$.error.code")).isEqualTo("FORBIDDEN");
        }
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    @Test
    void aMemberWhoMayNotReadTheRecordGetsNotFoundLikeForARecordThatDoesNotExist() {
        Setting setting = setting();
        String owner = template(setting.admin());
        TestBrowser stranger = as(setting.organization(), plainMember(setting.organization()));

        BytesResponse theirs = xlsx(stranger, owner, workbook());
        BytesResponse nothing = xlsx(stranger, UUID.randomUUID().toString(), workbook());

        assertThat(theirs.status()).isEqualTo(404);
        assertThat(nothing.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(theirs.text(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(nothing.text(), "$.error.message"));
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    @Test
    void anotherOrganizationAnAnonymousVisitorAndAPlatformAdministratorCannotUpload() {
        Setting mine = setting();
        String owner = template(mine.admin());
        Setting theirs = setting();
        TestBrowser nobody = new TestBrowser(port, mine.organization().host());
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));

        assertThat(xlsx(theirs.admin(), owner, workbook()).status()).as("another organization").isEqualTo(404);
        assertThat(xlsx(platform, owner, workbook()).status()).as("platform administrator").isEqualTo(404);
        BytesResponse anonymous = xlsx(nobody, owner, workbook());
        assertThat(anonymous.status()).as(anonymous.text()).isEqualTo(401);
        assertThat(countRows(mine.organization(), owner)).isZero();
    }

    @Test
    void aForgedOrganizationInTheHeadersChangesNothing() {
        Setting mine = setting();
        Setting theirs = setting();
        String theirOwner = template(theirs.admin());

        BytesResponse forged = mine.admin().bytes("POST", versions(theirOwner) + "/import?format=xlsx", workbook(),
                "Content-Type", OCTET, "X-Tenant-Id", theirs.organization().id().value().toString(),
                "X-Forwarded-Host", theirs.organization().host());

        assertThat(forged.status()).isEqualTo(404);
        assertThat(countRows(theirs.organization(), theirOwner)).isZero();
    }

    @Test
    void aRequestWithoutTheForgeryProtectionHeaderIsRefused() {
        Setting setting = setting();
        String owner = template(setting.admin());

        BytesResponse refused = setting.admin().bytes("POST", versions(owner) + "/import?format=csv", csv("a\n"),
                "Content-Type", OCTET, "X-XSRF-TOKEN", "");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(countRows(setting.organization(), owner)).isZero();
    }

    // ---- a race ----

    @Test
    void twoUploadsAtTheSameMomentMakeOneDraftAndTheOtherIsToldItExists() throws Exception {
        Setting setting = setting();
        String owner = template(setting.admin());
        List<Member> authors = authors(setting, 2, owner);
        List<TestBrowser> browsers = new ArrayList<>();
        for (Member member : authors) {
            browsers.add(as(setting.organization(), member));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> uploads = new ArrayList<>();
            for (TestBrowser browser : browsers) {
                uploads.add(() -> xlsx(browser, owner, workbook()).status());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> done : pool.invokeAll(uploads)) {
                statuses.add(done.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(countRows(setting.organization(), owner)).isEqualTo(1);
    }
}
