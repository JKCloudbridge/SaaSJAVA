package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Reportable columns, the report and the files of a fill-in, end to end (Functional track FF2, ADR-0193): the values of
 * the flagged columns are copied in the transaction of every save, in their own types; a report finds the fill-ins that
 * hold a value and shows each person exactly the fill-ins they see in the list; a file follows the cells. The answers
 * of
 * the report are compared with what was typed, which is computed here and not by the report's own code.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class FillReportIT extends FillSupport {

    private static final String REPORT = FILL_INS + "/report";

    /** A new version of the template in which the number, list and yes or no columns are reportable, in use. */
    private static int reportableVersion(World world) {
        int draft = newVersion(world.author().browser(), world.template());
        String grid = sheet(2, "FIXED_AND_REPEATABLE")
                .replace("\"type\":\"number\"", "\"type\":\"number\",\"reportable\":true")
                .replace("\"type\":\"boolean\"", "\"type\":\"boolean\",\"reportable\":true")
                .replace("\"type\":\"picklist\"", "\"type\":\"picklist\",\"reportable\":true");
        saveSheet(world.author().browser(), world.template(), draft, grid);
        String request = submitted(world.author().browser(), world.template(), world.templateProcess());
        assertThat(approve(world.approver().browser(), request).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), world.template(), null, "PLANT1/PRD").status())
                .isEqualTo(200);
        return draft;
    }

    private static Response report(TestBrowser browser, World world, String column, String operator, String body) {
        return browser.postJson(REPORT, "{\"templateId\":\"" + world.template() + "\",\"columnId\":\"" + column
                + "\",\"operator\":\"" + operator + "\"," + body + "}");
    }

    private static List<String> found(Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.data.rows[*].fillId");
    }

    private static String filled(TestBrowser browser, World world, String values) {
        String fill = started(browser, world.template());
        saved(browser, fill, cells(row("r1", values)));
        return fill;
    }

    private static long values(World world, String fill) {
        return scalar(world.setting().organization(), "select count(*) from sheet_fill_value where fill_id = ?",
                UUID.fromString(fill));
    }

    // ---- the copy ----

    @Test
    void theFlaggedColumnsAreOfferedAndTheirValuesAreCopiedInTheirOwnTypesByEverySave() {
        World world = world();
        reportableVersion(world);
        Response columns = world.filler().browser().get(FILL_INS + "/report/columns?templateId=" + world.template());
        assertThat(columns.status()).as(columns.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(columns.body(), "$.data.columns[*].id"))
                .containsExactly("n", "p", "b");

        String fill = filled(world.filler().browser(), world, "\"t\":\"x\",\"n\":\"12.5\",\"p\":\"Pass\",\"b\":false");

        assertThat(values(world, fill)).as("a number, a listed value and a no").isEqualTo(3);
        saved(world.filler().browser(), fill, cells(row("r1", "\"t\":\"x\",\"p\":\"Fail\"")));
        assertThat(values(world, fill)).as("replaced as a whole, not added to").isEqualTo(1);
    }

    @Test
    void aRefusedSaveLeavesTheCopyAsItWas() {
        World world = world();
        reportableVersion(world);
        String fill = filled(world.filler().browser(), world, "\"t\":\"x\",\"n\":\"7\",\"p\":\"Pass\"");
        long before = values(world, fill);

        Response view = fillView(world.filler().browser(), fill);
        Response refused = saveCells(world.filler().browser(), fill, fillVersion(view),
                cells(row("r1", "\"t\":\"x\",\"n\":\"not a number\"")));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(values(world, fill)).isEqualTo(before);
    }

    @Test
    void aVersionThatFlagsNothingCopiesNothing() {
        World world = world();
        String fill = filled(world.filler().browser(), world, "\"t\":\"x\",\"n\":\"7\"");

        assertThat(values(world, fill)).isZero();
    }

    // ---- the report ----

    @Test
    void theReportFindsExactlyWhatWasTypedAndShowsEachPersonTheirOwnFillIns() {
        World world = world();
        reportableVersion(world);
        String pass = filled(world.filler().browser(), world, "\"t\":\"a\",\"n\":\"12.5\",\"p\":\"Pass\",\"b\":true");
        String fail = filled(world.colleague().browser(), world, "\"t\":\"b\",\"n\":\"3\",\"p\":\"Fail\",\"b\":false");
        String failToo = filled(world.filler().browser(), world, "\"t\":\"c\",\"n\":\"30\",\"p\":\"Fail\"");

        // the person who may view every fill-in
        assertThat(found(report(world.approver().browser(), world, "p", "EQUALS", "\"values\":[\"Fail\"]")))
                .containsExactlyInAnyOrder(fail, failToo);
        assertThat(found(report(world.approver().browser(), world, "p", "ONE_OF",
                "\"values\":[\"Pass\",\"Fail\"]"))).containsExactlyInAnyOrder(pass, fail, failToo);
        assertThat(found(report(world.approver().browser(), world, "n", "BETWEEN", "\"from\":\"10\",\"to\":\"20\"")))
                .containsExactly(pass);
        assertThat(found(report(world.approver().browser(), world, "n", "BETWEEN", "\"from\":\"10\"")))
                .containsExactlyInAnyOrder(pass, failToo);
        assertThat(found(report(world.approver().browser(), world, "b", "EQUALS", "\"values\":[\"false\"]")))
                .containsExactly(fail);
        assertThat(found(report(world.approver().browser(), world, "n", "EQUALS", "\"values\":[\"12.50\"]")))
                .as("a number is compared as a number").containsExactly(pass);

        // a filler sees the same fill-ins as in the list, no more
        assertThat(found(report(world.filler().browser(), world, "p", "EQUALS", "\"values\":[\"Fail\"]")))
                .containsExactly(failToo);
        assertThat(found(report(world.colleague().browser(), world, "p", "EQUALS", "\"values\":[\"Fail\"]")))
                .containsExactly(fail);
        assertThat(report(world.outsider().browser(), world, "p", "EQUALS", "\"values\":[\"Fail\"]").status())
                .as("a person who cannot see the template is told it does not exist").isEqualTo(404);
    }

    @Test
    void aColumnThatIsNotReportableAndAConditionThatDoesNotFitAreRefusedWithoutRepeatingTheValue() {
        World world = world();
        reportableVersion(world);

        Response notFlagged = report(world.approver().browser(), world, "t", "EQUALS", "\"values\":[\"SECRETMARK\"]");
        Response wrongType = report(world.approver().browser(), world, "n", "EQUALS", "\"values\":[\"SECRETMARK\"]");
        Response outOfList = report(world.approver().browser(), world, "p", "EQUALS", "\"values\":[\"SECRETMARK\"]");
        Response noOperator = report(world.approver().browser(), world, "p", "NEVER", "\"values\":[\"x\"]");

        for (Response refused : List.of(notFlagged, wrongType, outOfList, noOperator)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).doesNotContain("SECRETMARK");
        }
    }

    @Test
    void theReportNeedsASignedInMemberAndANotFoundTemplateIsNotFound() {
        World world = world();
        reportableVersion(world);

        Response unknown = world.approver().browser().postJson(REPORT, "{\"templateId\":\"" + UUID.randomUUID()
                + "\",\"columnId\":\"p\",\"operator\":\"EQUALS\",\"values\":[\"Fail\"]}");

        assertThat(unknown.status()).isEqualTo(404);
    }

    // ---- the files ----

    private static BytesResponse upload(TestBrowser browser, String fill, String name) {
        byte[] bytes = new byte[2_000];
        bytes[0] = 'P';
        bytes[1] = 'K';
        return browser.bytes("POST", FILL_RECORDS + "/" + fill + "/files", bytes, "Content-Type", "image/png",
                "X-File-Name", URLEncoder.encode(name, StandardCharsets.UTF_8));
    }

    @Test
    void aFileFollowsTheCellsOnlyTheFillerWhileItIsADraftThenOnlyThoseWhoMayCorrect() {
        World world = world();
        String fill = filled(world.filler().browser(), world, "\"t\":\"x\"");

        assertThat(upload(world.filler().browser(), fill, "photo.png").status()).as("the filler").isEqualTo(201);
        assertThat(upload(world.colleague().browser(), fill, "other.png").status()).as("cannot even see it")
                .isEqualTo(404);
        Response listed = world.filler().browser().get(FILL_RECORDS + "/" + fill + "/files");
        assertThat(JsonPath.<List<String>>read(listed.body(), "$.data[*].id")).hasSize(1);

        saved(world.filler().browser(), fill, complete());
        sent(world.filler().browser(), fill, world.process());
        assertThat(upload(world.filler().browser(), fill, "late.png").status()).as("sent: locked for the filler")
                .isNotEqualTo(201);
        assertThat(upload(world.approver().browser(), fill, "fix.png").status()).as("a person who may correct")
                .isEqualTo(201);
        assertThat(audited(world.setting().organization(), "inspection.fill.file.corrected")).isEqualTo(1);
    }

    @Test
    void aFileIsDownloadedAsAnAttachmentAndItsNameIsNeverAudited() {
        World world = world();
        String fill = filled(world.filler().browser(), world, "\"t\":\"x\"");
        BytesResponse stored = upload(world.filler().browser(), fill, "NAMEMARK.png");
        String id = JsonPath.read(new String(stored.body(), StandardCharsets.UTF_8), "$.data.id");

        BytesResponse content = world.filler().browser().bytes("GET", FILL_RECORDS + "/" + fill + "/files/" + id
                + "/content", new byte[0]);

        assertThat(content.status()).isEqualTo(200);
        assertThat(content.headers().get("content-disposition").get(0)).startsWith("attachment");
        assertThat(owner("select count(*) from audit_record where attributes::text like ?", "%NAMEMARK%").get(0))
                .isEqualTo("0");
    }
}
