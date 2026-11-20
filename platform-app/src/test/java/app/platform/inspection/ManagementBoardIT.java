package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The plant board of the Management Core Home page (ADR-0266, Option 1) through the real application and database:
 * the numbers equal the lists after every step of a whole cycle (a fill-in, the reviewer's Submit, a correction up and
 * down, the Action plan, its send and its review), the two days are stamped once from the platform's clock, the due
 * days are a setting of Setup, a plant head sees one plant, a person who reads no fill-in gets no section (never a
 * zero), and the addresses and refusals are those of the rest of the cluster. The back-dating of a stamp by a
 * statement is a fixture of this test only: nothing of the product does it.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ManagementBoardIT extends ManagementHomeSupport {

    private static final String QUERY = FILL_INS + "/query?application=" + CORE;

    private static Response board(TestBrowser browser) {
        return browser.postJson(BOARD, "{\"span\":3}");
    }

    private static Response board(TestBrowser browser, String body) {
        return browser.postJson(BOARD, body);
    }

    private static <T> T read(Response answer, String path) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return JsonPath.read(answer.body(), "$.data." + path);
    }

    private static String str(Response answer, String path) {
        return read(answer, path);
    }

    private static Integer integer(Response answer, String path) {
        return read(answer, path);
    }

    private static double decimal(Response answer, String path) {
        Number value = read(answer, path);
        return value.doubleValue();
    }

    private static List<String> strings(Response answer, String path) {
        return read(answer, path);
    }

    private static List<Object> objects(Response answer, String path) {
        return read(answer, path);
    }

    private static boolean absent(Response answer, String path) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        try {
            return JsonPath.read(answer.body(), "$.data." + path) == null;
        } catch (PathNotFoundException left) {
            return true;
        }
    }

    private static int listed(TestBrowser browser, String filter) {
        Response page = browser.postJson(QUERY, "{\"filter\":" + filter + "}");
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.<List<Object>>read(page.body(), "$.data.rows").size();
    }

    private static String equal(String field, String value) {
        return "{\"op\":\"EQ\",\"field\":\"" + field + "\",\"values\":[\"" + value + "\"]}";
    }

    private static String notEmpty(String field) {
        return "{\"op\":\"IS_NOT_EMPTY\",\"field\":\"" + field + "\"}";
    }

    private static String empty(String field) {
        return "{\"op\":\"IS_EMPTY\",\"field\":\"" + field + "\"}";
    }

    private static String and(String... items) {
        return "{\"op\":\"AND\",\"items\":[" + String.join(",", items) + "]}";
    }

    private static void ownerRuns(String sql) {
        try (Connection owner = TestDatabase.ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("The statement failed: " + e.getSQLState(), e);
        }
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    // ---- the numbers equal the lists, after every step ----

    /** What the board says about the first plant and the first plan, and what the lists say, must agree. */
    private static void agrees(TestBrowser approver, int plansSent, int plansSubmitted) {
        Response answer = board(approver);
        assertThat(integer(answer, "totals.plansSent")).as("plans sent").isEqualTo(plansSent);
        assertThat(integer(answer, "totals.plansSubmitted")).as("plans submitted").isEqualTo(plansSubmitted);
        assertThat(listed(approver, and(equal("result", "ACTION_PLAN"), equal("status", "APPROVED"),
                empty("parentFill"), notEmpty("scoredOn")))).as("the list of the plans sent").isEqualTo(plansSent);
        assertThat(listed(approver, notEmpty("planSentOn"))).as("the list of the plans the plant heads sent")
                .isEqualTo(plansSubmitted);
    }

    @Test
    void theBoardEqualsTheListsAfterEveryStepOfAWholeCycle() {
        Plants plants = scored();
        TestBrowser approver = plants.world().approver().browser();
        String oneStep = oneStepProcess(plants);
        agrees(approver, 0, 0);

        // A fill-in sent and not yet decided is no score and no plan.
        String fill = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", PREVIOUS);
        saved(plants.head1().browser(), fill, values("80", "100"));
        String request = sent(plants.head1().browser(), fill, oneStep);
        Response waiting = board(approver);
        assertThat(strings(waiting, "pending[*].fillId")).as("sent and waiting for the reviewer")
                .containsExactly(fill);
        assertThat(integer(waiting, "pending[0].daysWaiting")).isZero();
        assertThat(str(waiting, "grid[0].plant")).isEqualTo("PLANT1");
        assertThat(str(waiting, "grid[0].cells[0].status")).isEqualTo("NO_SCORE");
        assertThat(absent(waiting, "plants[0].latestScore")).isTrue();
        agrees(approver, 0, 0);

        // The reviewer's Submit stamps the day, from the platform's clock, and the plan is owed within the due days.
        assertThat(approve(plants.reviewerA().browser(), request).status()).isEqualTo(200);
        assertThat(headerField(approver, fill, "scoredOn")).isEqualTo(today());
assertThat(objects(board(approver), "pending")).as("decided, so no longer waiting").isEmpty();
        Response owed = board(approver);
        assertThat(decimal(owed, "plants[0].latestScore")).isEqualTo(80.0);
        assertThat(str(owed, "grid[0].cells[0].status")).isEqualTo("AWAITING");
        assertThat(str(owed, "plans[0].status")).isEqualTo("AWAITING");
        assertThat(str(owed, "plans[0].sentOn")).isEqualTo(today());
        assertThat(str(owed, "plans[0].dueOn")).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(10)
                .toString());
        assertThat(absent(owed, "plans[0].planId")).isTrue();
        assertThat(integer(owed, "dueDays")).isEqualTo(10);
        assertThat(integer(owed, "limit")).isEqualTo(85);
        agrees(approver, 1, 0);

        // A correction up needs no plan and a correction down needs one again; the day the score was stamped is never
        // moved, and the status follows the score.
        long version = fillVersion(fillView(approver, fill));
        assertThat(saveCells(approver, fill, version, values("95", "100")).status()).isEqualTo(200);
        assertThat(headerField(approver, fill, "scoredOn")).as("not moved").isEqualTo(today());
        Response up = board(approver);
        assertThat(str(up, "grid[0].cells[0].status")).isEqualTo("NO_PLAN");
        assertThat(objects(up, "plans")).isEmpty();
        agrees(approver, 0, 0);
        version = fillVersion(fillView(approver, fill));
        assertThat(saveCells(approver, fill, version, values("70", "100")).status()).isEqualTo(200);
        assertThat(headerField(approver, fill, "scoredOn")).as("still not moved").isEqualTo(today());
        assertThat(str(board(approver), "plans[0].status")).isEqualTo("AWAITING");
        agrees(approver, 1, 0);

        // The plant head starts the Action plan: nothing is sent yet.
        Response made = plants.head1().browser().postJson(FILL_INS + "/" + fill + "/action-plan", "{}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        String plan = JsonPath.read(made.body(), "$.data.fillId");
        Response started = board(approver);
        assertThat(str(started, "plans[0].planId")).isEqualTo(plan);
        assertThat(str(started, "plans[0].status")).isEqualTo("AWAITING");
        assertThat(headerField(approver, plan, "planSentOn")).isNull();
        agrees(approver, 1, 0);

        // The plant head sends it: the day is stamped, once.
        saved(plants.head1().browser(), plan, three(row("r1", "\"t\":\"Fix the door\"")));
        String planRequest = sent(plants.head1().browser(), plan, oneStep);
        assertThat(headerField(approver, plan, "planSentOn")).isEqualTo(today());
        Response submitted = board(approver);
        assertThat(str(submitted, "plans[0].status")).isEqualTo("SUBMITTED");
        assertThat(str(submitted, "plans[0].submittedOn")).isEqualTo(today());
        assertThat(str(submitted, "grid[0].cells[0].status")).isEqualTo("SUBMITTED");
        assertThat(integer(submitted, "plants[0].plansSubmitted")).isEqualTo(1);
        agrees(approver, 1, 1);

        // Its review changes nothing about the days.
        assertThat(approve(plants.reviewerA().browser(), planRequest).status()).isEqualTo(200);
        assertThat(headerField(approver, plan, "planSentOn")).isEqualTo(today());
        agrees(approver, 1, 1);
        assertThat(integer(board(approver), "totals.overdue")).isZero();
    }

    // ---- overdue, and the days are a setting ----

    @Test
    void aPlanNotSentAfterItsDueDayIsOverdueByWholeDaysAndTheDueDaysAreASettingOfSetup() {
        Plants plants = scored();
        TestBrowser approver = plants.world().approver().browser();
        var admin = plants.world().setting().admin();
        String oneStep = oneStepProcess(plants);
        String fill = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", PREVIOUS);
        saved(plants.head1().browser(), fill, values("60", "100"));
        assertThat(approve(plants.reviewerA().browser(), sent(plants.head1().browser(), fill, oneStep)).status())
                .isEqualTo(200);
        // A fixture of this test: the score was stamped 15 days ago.
        ownerRuns("update object_record set payload = jsonb_set(payload, '{scoredOn}', to_jsonb('"
                + LocalDate.now(ZoneOffset.UTC).minusDays(15) + "'::text)), version = version + 1 where id = '"
                + fill + "'");

        Response late = board(approver);
        assertThat(str(late, "plans[0].status")).isEqualTo("OVERDUE");
        assertThat(integer(late, "plans[0].overdueDays")).isEqualTo(5);
        assertThat(str(late, "plans[0].statusText")).isEqualTo("Overdue 5 days");
        assertThat(integer(late, "totals.overdue")).isEqualTo(1);
        assertThat(integer(late, "plants[0].overdue")).isEqualTo(1);
        assertThat(str(late, "grid[0].cells[0].word")).isEqualTo("Overdue");

        // The day after the due day is overdue and the due day itself is not: 20 days from 15 days ago is in 5 days.
        Response chosen = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"actionPlanDueDays\",\"value\":15}");
        assertThat(chosen.status()).as(chosen.body()).isEqualTo(200);
        assertThat(str(board(approver), "plans[0].status")).as("due today, on time").isEqualTo("AWAITING");
        Response shorter = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"actionPlanDueDays\",\"value\":14,\"version\":0}");
        assertThat(shorter.status()).as(shorter.body()).isEqualTo(200);
        Response yesterday = board(approver);
        assertThat(str(yesterday, "plans[0].status")).isEqualTo("OVERDUE");
        assertThat(integer(yesterday, "plans[0].overdueDays")).isEqualTo(1);

        Response outOfRange = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"actionPlanDueDays\",\"value\":366,\"version\":1}");
        assertThat(outOfRange.status()).isEqualTo(400);
        Response zero = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"actionPlanDueDays\",\"value\":0,\"version\":1}");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(plants.head1().browser().request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"actionPlanDueDays\",\"value\":30}").status())
                .as("only those who manage settings").isEqualTo(403);
    }

    @Test
    void aFillInThatWasScoredBeforeTheDaysWereStampedHasADashAndIsNeverCountedAsDueOrOverdue() {
        Plants plants = scored();
        TestBrowser approver = plants.world().approver().browser();
        String oneStep = oneStepProcess(plants);
        String fill = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", PREVIOUS);
        saved(plants.head1().browser(), fill, values("60", "100"));
        assertThat(approve(plants.reviewerA().browser(), sent(plants.head1().browser(), fill, oneStep)).status())
                .isEqualTo(200);
        // A fixture: take the stamp away, as it is on a fill-in made before the stamps existed.
        ownerRuns("update object_record set payload = payload - 'scoredOn', version = version + 1 where id = '"
                + fill + "'");

        Response answer = board(approver);
        assertThat(str(answer, "plans[0].status")).isEqualTo("NO_DATE");
        assertThat(absent(answer, "plans[0].sentOn")).isTrue();
        assertThat(absent(answer, "plans[0].dueOn")).isTrue();
        assertThat(str(answer, "grid[0].cells[0].word")).isEqualTo("No date");
        assertThat(integer(answer, "totals.plansSent")).isZero();
        assertThat(integer(answer, "totals.overdue")).isZero();
        assertThat(decimal(answer, "plants[0].latestScore")).as("its score is still shown")
                .isEqualTo(60.0);
    }

    // ---- who sees what ----

    @Test
    void aPlantHeadSeesTheirPlantAndNothingOfAnotherAndAPersonWhoReadsNoFillInGetsNoSectionAndNoZero() {
        Plants plants = scored();
        var setting = plants.world().setting();
        String oneStep = oneStepProcess(plants);
        String one = startedMonth(plants.head1().browser(), plants.template(), "PLANT1", PREVIOUS);
        saved(plants.head1().browser(), one, values("70", "100"));
        assertThat(approve(plants.reviewerA().browser(), sent(plants.head1().browser(), one, oneStep)).status())
                .isEqualTo(200);
        String two = startedMonth(plants.head2().browser(), plants.template(), "PLANT2", PREVIOUS);
        saved(plants.head2().browser(), two, values("90", "100"));
        assertThat(approve(plants.reviewerC().browser(), sent(plants.head2().browser(), two, oneStep)).status())
                .isEqualTo(200);

        Response all = board(plants.world().approver().browser());
        Response mine = board(plants.head1().browser());
        Response nobody = board(outsider(setting).browser());

        assertThat(strings(all, "plants[*].plant")).containsExactly("PLANT1", "PLANT2");
        assertThat(strings(mine, "plants[*].plant")).containsExactly("PLANT1");
        assertThat(mine.body()).doesNotContain("PLANT2");
        assertThat(integer(all, "totals.plansSent")).isEqualTo(1);
        for (String path : List.of("plants", "grid", "plans", "totals")) {
            assertThat(absent(nobody, path)).as(path + " is not drawn for a person who reads no fill-in").isTrue();
        }
        assertThat(objects(nobody, "problems")).isEmpty();
        assertThat(board(plants.head1().browser(), "{\"plant\":\"PLANT2\"}").status())
                .as("a plant a person cannot see is not offered").isEqualTo(400);
        assertThat(board(plants.world().approver().browser(), "{\"span\":5}").status()).isEqualTo(400);
        assertThat(board(plants.world().approver().browser(), "{\"month\":\"2999-01\"}").status()).isEqualTo(400);
    }

    @Test
    void aPlantKeepsItsColourPlaceWhateverTheFilter() {
        Plants plants = scored();
        TestBrowser approver = plants.world().approver().browser();
        String oneStep = oneStepProcess(plants);
        for (String[] each : new String[][] {{"PLANT1", "1"}, {"PLANT2", "2"}}) {
            Person head = each[0].equals("PLANT1") ? plants.head1() : plants.head2();
            Person reviewer = each[0].equals("PLANT1") ? plants.reviewerA() : plants.reviewerC();
            String fill = startedMonth(head.browser(), plants.template(), each[0], PREVIOUS);
            saved(head.browser(), fill, values("90", "100"));
            assertThat(approve(reviewer.browser(), sent(head.browser(), fill, oneStep)).status()).isEqualTo(200);
        }

        Response narrowed = board(approver, "{\"span\":3,\"plant\":\"PLANT2\"}");

        assertThat(strings(narrowed, "plants[*].plant")).containsExactly("PLANT2");
        assertThat(integer(narrowed, "plants[0].colour")).as("still the second place").isEqualTo(2);
    }

    @Test
    void anotherOrganizationCountsNothingOfMineAndAForgedHeaderChangesNothing() {
        Plants mine = scored();
        String oneStep = oneStepProcess(mine);
        String fill = startedMonth(mine.head1().browser(), mine.template(), "PLANT1", PREVIOUS);
        saved(mine.head1().browser(), fill, values("60", "100"));
        assertThat(approve(mine.reviewerA().browser(), sent(mine.head1().browser(), fill, oneStep)).status())
                .isEqualTo(200);
        World theirs = world(2, "FIXED_AND_REPEATABLE", false);
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        Response theirView = board(theirs.approver().browser());
        Response forgedView = mine.world().approver().browser().postJson(BOARD, "{\"span\":3}", forged);

        assertThat(objects(theirView, "plans")).isEmpty();
        assertThat(integer(theirView, "totals.plansSent")).isZero();
        assertThat(integer(forgedView, "totals.plansSent")).as("still mine").isEqualTo(1);
    }

    @Test
    void thePlatformAddressTheUnsignedAndTheApplicationsWithoutThisPageAreAnsweredLikeAnythingElseOfTheCluster() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());

        assertThat(board(platform).status()).isEqualTo(404);
        Response anonymous = board(nobody);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(anonymous.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        for (String application : List.of("inspection", "document-management", "no-such-application")) {
            Response refused = world.approver().browser().postJson(
                    "/api/v1/management-home/plant-board?application=" + application, "{}");
            assertThat(refused.status()).as(application).isEqualTo(404);
        }
        assertThat(world.approver().browser().postJsonWithoutCsrfHeader(BOARD, "{}").status()).isEqualTo(403);
    }
}
