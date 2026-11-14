package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A fill-in on a sheet in format version 2 through the real application, database and object store (SH5, ADR-0258,
 * ADR-0254): start, name files in an attachment cell, a file of nowhere refused, the other class's cell refused, a file
 * removed takes its identifier out of the cell, Submit refused while a required attachment has no file, sent, the
 * filler locked out, the person asked to review fills the reviewer's cell without the right to read the header, and
 * cannot touch the filler's cells; an outsider sees nothing.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class FillFormatThreeFlowIT extends FillSupport {

    private static final String SHEET = "{\"formatVersion\":2,\"sheets\":[{\"key\":\"main\",\"dimension\":2,"
            + "\"rowsMode\":\"FIXED\",\"columns\":[{\"id\":\"c1\",\"label\":\"Item\",\"type\":\"text\"},"
            + "{\"id\":\"c2\",\"label\":\"Amount\",\"type\":\"number\"}],\"headerRows\":[{\"id\":\"h1\"}],"
            + "\"rows\":[{\"id\":\"r1\",\"label\":\"First\"},{\"id\":\"r2\",\"label\":\"Second\"}],"
            + "\"cells\":{\"h1:c1\":{\"value\":\"Plant\"}},\"merges\":[],\"inputs\":{"
            + "\"r1:c1\":{\"type\":\"attachment\",\"required\":true,\"maxFiles\":2},"
            + "\"r2:c1\":{\"class\":\"reviewer\"}}}]}";

    private record Flow(Person filler, Person checker, Person outsider, String fill, String process) {
    }

    private Flow flow() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        Person filler = holding(setting, "Filler");
        Person checker = outsider(setting);
        app.platform.testsupport.TestMembers.giveLicence(setting.organization().id(), checker.member().membership(),
                "user");
        Person stranger = outsider(setting);
        String templateProcess = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        String fillProcess = fillProcess(setting.admin(), "FillReview", step("Review", user(approver.userId()),
                user(checker.userId())));
        department(setting.admin(), "PLANT1", "PRD", group(setting.admin(), "dept-" + System.nanoTime(), filler));
        String template = template(author.browser(), "WI", "PRD");
        int number = firstVersion(author.browser(), template);
        saveSheet(author.browser(), template, number, SHEET);
        assertThat(approve(approver.browser(), submitted(author.browser(), template, templateProcess)).status())
                .isEqualTo(200);
        assertThat(publish(approver.browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);
        return new Flow(filler, checker, stranger, started(filler.browser(), template), fillProcess);
    }

    private static String files(String fill) {
        return FILL_RECORDS + "/" + fill + "/files";
    }

    private static byte[] bytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        bytes[0] = 'P';
        bytes[1] = 'K';
        return bytes;
    }

    private static String attach(app.platform.testsupport.TestBrowser browser, String fill, String name, int size) {
        BytesResponse stored = browser.bytes("POST", files(fill), bytes(size), "Content-Type", "application/zip",
                "X-File-Name", name);
        assertThat(stored.status()).as(stored.text()).isBetween(200, 299);
        return JsonPath.read(stored.text(), "$.data.id");
    }

    private static String three(String rows) {
        return "{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[" + rows + "]}]}";
    }

    private static String filesCell(String... ids) {
        return "\"c1\":[\"" + String.join("\",\"", ids) + "\"]";
    }

    @Test
    void aFillInOnTheNewFormatLivesItsWholeLifeWithFilesAndAReviewer() {
        Flow flow = flow();
        var filler = flow.filler().browser();

        Response view = fillView(filler, flow.fill());
        assertThat(view.status()).as(view.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(view.body(), "$.data.cells.formatVersion")).isEqualTo(3);
        assertThat(JsonPath.<String>read(view.body(), "$.data.editAs")).isEqualTo("FILLER");

        String one = attach(filler, flow.fill(), "one.zip", 500);
        String two = attach(filler, flow.fill(), "two.zip", 600);
        String third = attach(filler, flow.fill(), "three.zip", 700);
        long version = saved(filler, flow.fill(), three(row("r1", filesCell(one, two))));
        assertThat(JsonPath.<Integer>read(fillView(filler, flow.fill()).body(),
                "$.data.cells.sheets[0].rows[0].cells.c1.length()")).isEqualTo(2);

        // More files than the cell holds, a file that is nowhere, and a cell that is the reviewer's: refused.
        assertThat(saveCells(filler, flow.fill(), version, three(row("r1", filesCell(one, two, third)))).status())
                .isEqualTo(400);
        String nowhere = UUID.randomUUID().toString();
        Response unknown = saveCells(filler, flow.fill(), version, three(row("r1", filesCell(nowhere))));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body()).doesNotContain(nowhere);
        Response otherClass = saveCells(filler, flow.fill(), version,
                three(row("r1", filesCell(one, two)) + "," + row("r2", "\"c1\":\"mine\"")));
        assertThat(otherClass.status()).as(otherClass.body()).isEqualTo(400);

        // Taking a file off takes its identifier out of the cell, in the same transaction.
        assertThat(filler.bytes("DELETE", files(flow.fill()) + "/" + one, null).status()).isBetween(200, 299);
        Response afterOne = fillView(filler, flow.fill());
        assertThat(afterOne.body()).doesNotContain(one).contains(two);
        assertThat(filler.bytes("DELETE", files(flow.fill()) + "/" + two, null).status()).isBetween(200, 299);
        assertThat(fillView(filler, flow.fill()).body()).doesNotContain(two);

        // A required attachment with no file keeps the fill-in from being sent, and says which cell.
        Response refused = sendFill(filler, flow.fill(), flow.process());
        assertThat(refused.status()).as(refused.body()).isBetween(400, 499);
        assertThat(refused.body()).contains("c1");
        assertThat(fillStatus(filler, flow.fill())).isEqualTo("DRAFT");

        saved(filler, flow.fill(), three(row("r1", filesCell(third))));
        sent(filler, flow.fill(), flow.process());
        assertThat(fillStatus(filler, flow.fill())).isEqualTo("SUBMITTED");
        assertThat(JsonPath.<Boolean>read(fillView(filler, flow.fill()).body(), "$.data.canEdit")).isFalse();
        assertThat(saveCells(filler, flow.fill(), fillVersion(fillView(filler, flow.fill())),
                three(row("r1", filesCell(third)))).status()).isEqualTo(403);

        // The person asked to review fills the reviewer's cell and nothing else; the review stays open.
        var checker = flow.checker().browser();
        Response asked = fillView(checker, flow.fill());
        assertThat(asked.status()).as(asked.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(asked.body(), "$.data.editAs")).isEqualTo("REVIEWER");
        long reviewVersion = fillVersion(asked);
        Response filledFiller = saveCells(checker, flow.fill(), reviewVersion,
                three(row("r1", "\"c1\":[]") + "," + row("r2", "\"c1\":\"checked\"")));
        assertThat(filledFiller.status()).as("a reviewer cannot take the filler's files away").isBetween(400, 499);
        Response reviewed = saveCells(checker, flow.fill(), reviewVersion,
                three(row("r1", filesCell(third)) + "," + row("r2", "\"c1\":\"checked\"")));
        assertThat(reviewed.status()).as(reviewed.body()).isEqualTo(200);
        assertThat(fillStatus(filler, flow.fill())).as("not returned, review still open").isEqualTo("SUBMITTED");
        Response back = fillView(filler, flow.fill());
        assertThat(back.body()).contains("checked");
        assertThat(fillVersion(back)).isGreaterThan(reviewVersion);
        assertThat(saveCells(checker, flow.fill(), reviewVersion, three(row("r1", filesCell(third)))).status())
                .as("a stale save over a newer version").isEqualTo(409);

        // The answers of the formulas are asked of the server; this sheet has none.
        assertThat(filler.get(FILLS + flow.fill() + "/calculation").status()).isEqualTo(200);
        assertThat(flow.outsider().browser().get(FILLS + flow.fill()).status()).isEqualTo(404);
    }
}
