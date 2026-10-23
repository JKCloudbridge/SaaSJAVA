package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the fill-ins (FF1, ADR-0012, ADR-0192): fill-ins are started, filled in, refused in every way they
 * can
 * be, sent, approved and corrected, with the application and the security framework logging at debug level. What a
 * person
 * typed into a cell, a column key or a row identifier they invented is never logged, never written to the audit trail,
 * never put in an event, never kept in a notice and never echoed by a refusal. The audit trail does show that a fill-in
 * changed, by identifiers, sizes, counts and the fingerprint.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + FillInsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class FillInsLogsAreCleanIT extends FillSupport {

    static final String LOG_FILE = "target/it-logs/fill-ins-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedWrittenToTheTrailKeptInAnEventOrANoticeOrEchoedByARefusal() throws IOException {
        World world = world();
        String finding = typed("finding");
        String other = typed("other-finding");
        String corrected = typed("corrected-finding");
        String markup = typed("markup-attempt");
        String badNumber = typed("not-a-number");
        String madeUpColumn = typed("made-up-column");
        String madeUpRow = typed("made-up-row");

        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));
        List<Response> refusals = new ArrayList<>();
        refusals.add(saveCells(world.filler().browser(), fill, version, cells(row("r1", "\"t\":\"<b>" + markup
                + "</b>\""))));
        refusals.add(saveCells(world.filler().browser(), fill, version, cells(row("r1", "\"n\":\"" + badNumber
                + "\""))));
        refusals.add(saveCells(world.filler().browser(), fill, version, cells(row("r1", "\"" + madeUpColumn
                + "\":\"" + finding + "\""))));
        refusals.add(saveCells(world.filler().browser(), fill, version, cells(row("r1", "\"d\":\"" + finding
                + "\""))));
        refusals.add(saveCells(world.colleague().browser(), fill, version, cells(row("r1", "\"t\":\"" + finding
                + "\""))));
        refusals.add(saveCells(world.filler().browser(), fill, version, "{\"formatVersion\":1,\"rows\":["
                + finding));
        refusals.add(saveCells(world.filler().browser(), fill, version, "{\"" + madeUpRow + "\":\"" + finding
                + "\"}"));
        refusals.add(saveCells(world.filler().browser(), fill, version, cells(row("r1", "\"t\":\"" + finding
                + "\",\"t\":\"" + other + "\""))));

        Response ok = saveCells(world.filler().browser(), fill, version, cells(row("r1", "\"t\":\"" + finding
                + "\""), row(madeUpRow, "\"t\":\"" + other + "\"")));
        Response incomplete = sendFill(world.filler().browser(), fill, world.process());
        long next = fillVersion(fillView(world.filler().browser(), fill));
        Response complete = saveCells(world.filler().browser(), fill, next, cells(row("r1", "\"t\":\"" + finding
                + "\""), row("r2", "\"t\":\"" + other + "\"")));
        String request = sent(world.filler().browser(), fill, world.process());
        Response correction = saveCells(world.approver().browser(), fill, fillVersion(fillView(
                world.approver().browser(), fill)), cells(row("r1", "\"t\":\"" + corrected + "\""),
                row("r2", "\"t\":\"" + other + "\"")));
        Response approved = approve(world.checker().browser(), request);
        refusals.add(sendFill(world.filler().browser(), fill, world.process()));
        refusals.add(fillView(world.outsider().browser(), fill));
        Response list = world.filler().browser().get(FILL_INS);

        String trail = world.setting().admin().get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        List<String> secrets = List.of(finding, other, corrected, markup, badNumber, madeUpColumn, madeUpRow);

        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(incomplete.status()).isEqualTo(409);
        assertThat(complete.status()).as(complete.body()).isEqualTo(200);
        assertThat(correction.status()).as(correction.body()).isEqualTo(200);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : secrets) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            assertThat(list.body()).as("not in a list: " + text).doesNotContain(text);
            assertThat(incomplete.body()).as("a refusal does not echo: " + text).doesNotContain(text);
        }
        for (Response refused : refusals) {
            assertThat(refused.status()).as(refused.body()).isGreaterThanOrEqualTo(400);
            for (String text : secrets) {
                assertThat(refused.body()).as("a refusal does not echo: " + text).doesNotContain(text);
            }
        }
        assertThat(JsonPath.<List<String>>read(trail, "$.data[*].type")).as("the trail shows what changed")
                .contains("inspection.fill.started", "sheets.fill.started", "sheets.fill.saved",
                        "sheets.fill.submitted", "sheets.fill.corrected", "sheets.fill.reviewed");
        assertThat(owner("select string_agg(attributes::text, ' ') from audit_record where event_type like "
                + "'sheets.fill.%' and context_tenant_id = ?", world.setting().organization().id().value()).get(0))
                .contains("hash").contains("bytes").contains("rows");
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                world.setting().organization().id().value()));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?",
                world.setting().organization().id().value()));
        for (String text : secrets) {
            assertThat(events).as("not in an event: " + text).doesNotContain(text);
            assertThat(notices).as("not in a notice: " + text).doesNotContain(text);
        }
    }
}
