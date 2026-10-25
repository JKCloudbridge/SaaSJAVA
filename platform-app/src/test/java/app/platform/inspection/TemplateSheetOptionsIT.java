package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * "View only", "Allow download", the Excel file and the footer row (FA7, ADR-0201): the two options are set when the
 * template is published and changed later by the people who may publish; a view-only template cannot be filled in; the
 * file is given only when the option is on (or to the people who publish), only for a template the caller reads, and
 * says the number and the revision in its footer. Nothing typed, no number and no file name reaches the audit trail.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateSheetOptionsIT extends FillSupport {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static Response publishWith(TestBrowser browser, String template, Boolean viewOnly, Boolean allowDownload) {
        return browser.postJson(TEMPLATES + "/" + template + "/publish", "{\"audiences\":" + audiences("PLANT1/PRD")
                + (viewOnly == null ? "" : ",\"viewOnly\":" + viewOnly)
                + (allowDownload == null ? "" : ",\"allowDownload\":" + allowDownload) + "}");
    }

    private static Response options(TestBrowser browser, String template, boolean viewOnly, boolean allowDownload) {
        return browser.request("PUT", TEMPLATES + "/" + template + "/options",
                "{\"viewOnly\":" + viewOnly + ",\"allowDownload\":" + allowDownload + "}");
    }

    private static BytesResponse download(TestBrowser browser, String template) {
        return browser.bytes("GET", TEMPLATES + "/" + template + "/download", null);
    }

    private static boolean flag(Response lifecycle, String name) {
        return JsonPath.read(lifecycle.body(), "$.data." + name);
    }

    private static String auditText(app.platform.testsupport.TestOrganizations.Organization organization, String type) {
        return String.join(" ", owner("select attributes::text from audit_record where event_type = ? and "
                + "context_tenant_id = ?", type, organization.id().value()));
    }

    private static String sheetXml(byte[] file) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("the workbook has no sheet");
    }

    // ---- the options ----

    @Test
    void bothOptionsStartOffAndPublishSavesThemAndLeavesAValueItIsNotGivenAlone() {
        World world = world();
        Response before = lifecycle(world.approver().browser(), world.template());
        assertThat(flag(before, "viewOnly")).isFalse();
        assertThat(flag(before, "allowDownload")).isFalse();

        Response both = publishWith(world.approver().browser(), world.template(), true, true);
        assertThat(both.status()).as(both.body()).isEqualTo(200);
        assertThat(flag(both, "viewOnly")).isTrue();
        assertThat(flag(both, "allowDownload")).isTrue();

        Response onlyOne = publishWith(world.approver().browser(), world.template(), null, false);
        assertThat(onlyOne.status()).as(onlyOne.body()).isEqualTo(200);
        assertThat(flag(onlyOne, "viewOnly")).as("a value left out keeps what the template has").isTrue();
        assertThat(flag(onlyOne, "allowDownload")).isFalse();
        assertThat(audited(world.setting().organization(), "inspection.template.published")).isEqualTo(3);
    }

    @Test
    void thePeopleWhoPublishChangeTheOptionsLaterAndEverybodyElseIsRefused() {
        World world = world();

        Response changed = options(world.approver().browser(), world.template(), true, true);
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(flag(changed, "viewOnly")).isTrue();
        assertThat(flag(changed, "allowDownload")).isTrue();
        assertThat(flag(changed, "canSetOptions")).isTrue();

        for (TestBrowser refused : List.of(world.filler().browser(), world.author().browser(),
                world.reviewer().browser())) {
            Response response = options(refused, world.template(), false, false);
            assertThat(response.status()).as("a member who sees the template but may not publish").isIn(403, 404);
        }
        assertThat(options(world.outsider().browser(), world.template(), false, false).status())
                .as("a member who cannot see the template").isEqualTo(404);
        assertThat(options(new TestBrowser(port, world.setting().organization().host()), world.template(), false, false)
                .status()).as("not signed in").isEqualTo(401);
        Response still = lifecycle(world.approver().browser(), world.template());
        assertThat(flag(still, "viewOnly")).as("nothing changed for the refused").isTrue();
        assertThat(audited(world.setting().organization(), "inspection.template.options.changed")).isEqualTo(1);
    }

    @Test
    void anotherOrganizationCannotChangeOrSeeTheOptions() {
        World world = world();
        Setting elsewhere = setting();
        Person stranger = approver(elsewhere);

        assertThat(options(stranger.browser(), world.template(), true, true).status()).isEqualTo(404);
        assertThat(lifecycle(stranger.browser(), world.template()).status()).isEqualTo(404);
        assertThat(download(stranger.browser(), world.template()).status()).isEqualTo(404);
    }

    // ---- view only ----

    @Test
    void aViewOnlyTemplateCannotBeFilledInAndAnUntickedOneCan() {
        World world = world();
        assertThat(options(world.approver().browser(), world.template(), true, false).status()).isEqualTo(200);

        Response refused = startFill(world.filler().browser(), world.template());
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message"))
                .isEqualTo("This template is view only, so no fill-in can be started from it.");
        assertThat(JsonPath.<Boolean>read(world.filler().browser().get(TEMPLATES + "/" + world.template()
                + "/fill-in-options").body(), "$.data.canStart")).isFalse();
        assertThat(lifecycle(world.filler().browser(), world.template()).status())
                .as("a view-only template can still be read").isEqualTo(200);

        assertThat(options(world.approver().browser(), world.template(), false, false).status()).isEqualTo(200);
        assertThat(startFill(world.filler().browser(), world.template()).status()).isEqualTo(201);
    }

    @Test
    void aFillInAlreadyStartedCanStillBeFinishedWhenTheTemplateBecomesViewOnly() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());

        assertThat(options(world.approver().browser(), world.template(), true, false).status()).isEqualTo(200);

        saved(world.filler().browser(), fill, complete());
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
        assertThat(sendFill(world.filler().browser(), fill, world.process()).status()).isEqualTo(201);
    }

    // ---- download ----

    @Test
    void downloadIsRefusedInFixedWordsWhileTheOptionIsOffExceptForThePeopleWhoPublish() throws Exception {
        World world = world();

        BytesResponse refused = download(world.filler().browser(), world.template());
        assertThat(refused.status()).isEqualTo(403);
        assertThat(new String(refused.body(), StandardCharsets.UTF_8)).contains("Download is not allowed for this "
                + "template.");

        BytesResponse checking = download(world.approver().browser(), world.template());
        assertThat(checking.status()).as("the people who publish may check their work").isEqualTo(200);
        assertThat(checking.headers().get("Content-Type")).contains(XLSX);
        assertThat(checking.headers().get("Content-Disposition").get(0)).contains("attachment")
                .contains("_Rev00.xlsx");
        assertThat(sheetXml(checking.body())).contains("Checklist").doesNotContain("<f>");
    }

    @Test
    void downloadIsGivenToEverybodyWhoReadsTheTemplateOnceTheOptionIsOnAndOnlyThem() throws Exception {
        World world = world();
        assertThat(options(world.approver().browser(), world.template(), false, true).status()).isEqualTo(200);

        BytesResponse given = download(world.filler().browser(), world.template());
        assertThat(given.status()).isEqualTo(200);
        assertThat(sheetXml(given.body())).contains("Checklist");
        assertThat(download(world.outsider().browser(), world.template()).status())
                .as("a member who cannot read the template").isEqualTo(404);
        assertThat(download(new TestBrowser(port, world.setting().organization().host()), world.template()).status())
                .as("not signed in").isEqualTo(401);
    }

    @Test
    void theFooterRowReadsTheNumberAndTheRevisionAndIsBlankBeforeTheTemplateIsNumbered() throws Exception {
        World world = world();
        assertThat(options(world.approver().browser(), world.template(), false, true).status()).isEqualTo(200);
        String number = JsonPath.read(lifecycle(world.approver().browser(), world.template()).body(),
                "$.data.number");

        BytesResponse file = download(world.filler().browser(), world.template());

        assertThat(sheetXml(file.body())).contains(number + "  Rev No.'00'");
        assertThat(file.headers().get("Content-Disposition").get(0))
                .contains(number.replace('/', '-') + "_Rev00.xlsx");

        // A template that has been written but never approved has no number: its footer row is there and blank.
        String fresh = templateWithColumns(world.author().browser(), 2, "FIXED_AND_REPEATABLE");
        assertThat(JsonPath.<Object>read(lifecycle(world.author().browser(), fresh).body(), "$.data.number"))
                .isNull();
        assertThat(options(world.approver().browser(), fresh, false, true).status()).isIn(200, 404);
    }

    // ---- what is never written down ----

    @Test
    void theAuditTrailHoldsTheIdentifierTheRevisionAndTheSizeAndNeverTheNumberOrTheFileName() {
        World world = world();
        assertThat(options(world.approver().browser(), world.template(), false, true).status()).isEqualTo(200);
        String number = JsonPath.read(lifecycle(world.approver().browser(), world.template()).body(),
                "$.data.number");
        assertThat(download(world.filler().browser(), world.template()).status()).isEqualTo(200);

        assertThat(audited(world.setting().organization(), "inspection.template.downloaded")).isEqualTo(1);
        assertThat(auditText(world.setting().organization(), "inspection.template.downloaded"))
                .doesNotContain(number).doesNotContain(number.replace('/', '-')).doesNotContain("Checklist")
                .contains("version_no", "bytes");
        assertThat(auditText(world.setting().organization(), "inspection.template.options.changed"))
                .doesNotContain(number).contains("view_only", "allow_download");
    }
}
