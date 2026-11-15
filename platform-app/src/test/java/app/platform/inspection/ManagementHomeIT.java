package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The Home page that compares the plants (MC3, ADR-0262) through the real application, database and object store: every
 * number a tile counts equals what the list it opens shows for the same person, a plant head sees one plant and nothing
 * of another, a person with no right to read fill-ins gets no section (never a zero), a month a plant did not report is
 * a gap, the bands and the limit are the numbers of Setup, and every address and every refusal of the cluster is
 * answered as everywhere else.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ManagementHomeIT extends ManagementHomeSupport {

    private static final String QUERY = FILL_INS + "/query?application=" + CORE;

    private static Response home(TestBrowser browser) {
        return browser.postJson(HOME, "{}");
    }

    private static Response home(TestBrowser browser, String body) {
        return browser.postJson(HOME, body);
    }

    private static Double number(Response answer, String path) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        try {
            Number value = JsonPath.read(answer.body(), "$.data." + path);
            return value == null ? null : value.doubleValue();
        } catch (PathNotFoundException left) {
            return null;
        }
    }

    /** Whether the answer has nothing at the path: left out, or null. */
    private static boolean absent(Response answer, String path) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        try {
            return JsonPath.read(answer.body(), "$.data." + path) == null;
        } catch (PathNotFoundException left) {
            return true;
        }
    }

    /** How many fill-ins the list shows for this question (a filter and a step, as the address of a tile asks). */
    private static int listed(TestBrowser browser, String body) {
        Response page = browser.postJson(QUERY, body);
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.<List<Object>>read(page.body(), "$.data.rows").size();
    }

    private static String equal(String field, String value) {
        return "{\"op\":\"EQ\",\"field\":\"" + field + "\",\"values\":[\"" + value + "\"]}";
    }

    private static String sentStatus() {
        return "{\"op\":\"IN\",\"field\":\"status\",\"values\":[\"SUBMITTED\",\"APPROVED\"]}";
    }

    private static String and(String... items) {
        return "{\"op\":\"AND\",\"items\":[" + String.join(",", items) + "]}";
    }

    private static String no(String field) {
        return "{\"op\":\"IS_EMPTY\",\"field\":\"" + field + "\"}";
    }

    // ---- the numbers equal the lists ----

    @Test
    void everyNumberATileCountsEqualsWhatTheListItOpensShowsForTheSamePerson() {
        Filled filled = filled();
        TestBrowser approver = filled.plants().world().approver().browser();

        Response answer = home(approver);

        assertThat(number(answer, "tiles.plantsSubmitted.value")).isEqualTo(2);
        assertThat(number(answer, "tiles.plantsSubmitted.whole")).as("both plants the document is published to")
                .isEqualTo(2);
        assertThat(number(answer, "tiles.plantsSubmitted.previous")).isEqualTo(2);
        assertThat(number(answer, "tiles.averageScore.value")).as("only the approved fill-in counts").isEqualTo(90.0);
        assertThat(number(answer, "tiles.averageScore.previous")).as("nothing was approved last month").isNull();
        assertThat(number(answer, "tiles.plantsBelowLimit.value")).isEqualTo(1);
        assertThat(number(answer, "tiles.plantsBelowLimit.extra")).as("one has no Action plan yet").isEqualTo(1);
        assertThat(number(answer, "tiles.waitingForReview.value")).isEqualTo(1);
        assertThat(number(answer, "tiles.waitingForTopManagement.value")).isEqualTo(2);
        assertThat(number(answer, "tiles.actionPlansOpen.value")).isEqualTo(1);

        // The list of each tile, by the very slice the tile's address names.
        String month = equal("period", CURRENT);
        assertThat(listed(approver, "{\"filter\":" + and(month, sentStatus(), no("parentFill")) + "}"))
                .isEqualTo(2);
        assertThat(listed(approver, "{\"filter\":" + and(month, equal("status", "APPROVED"),
                "{\"op\":\"IS_NOT_EMPTY\",\"field\":\"score\"}", no("parentFill")) + "}")).isEqualTo(1);
        assertThat(listed(approver, "{\"filter\":" + and(month, equal("result", "ACTION_PLAN"), sentStatus(),
                no("parentFill")) + "}")).isEqualTo(1);
        assertThat(listed(approver, "{\"waiting\":\"review\"}")).isEqualTo(1);
        assertThat(listed(approver, "{\"waiting\":\"top\"}")).isEqualTo(2);
        assertThat(listed(approver, "{\"filter\":" + and("{\"op\":\"IS_NOT_EMPTY\",\"field\":\"parentFill\"}",
                "{\"op\":\"NE\",\"field\":\"status\",\"values\":[\"APPROVED\"]}") + "}")).isEqualTo(1);
    }

    @Test
    void theGraphsSayTheSameThingAsTheTilesAndAMonthNobodyReportedIsAGapNotAZero() {
        Filled filled = filled();

        Response answer = home(filled.plants().world().approver().browser());

        assertThat(JsonPath.<String>read(answer.body(), "$.data.month")).isEqualTo(CURRENT);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.months")).hasSize(6).endsWith(CURRENT);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.charts.scoresByPlant[*].plant"))
                .containsExactly("PLANT1", "PLANT2");
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.charts.scoresByPlant[0].score").doubleValue())
                .isEqualTo(90.0);
        assertThat(JsonPath.<String>read(answer.body(), "$.data.charts.scoresByPlant[0].bandWord"))
                .isEqualTo("Excellent");
        assertThat(JsonPath.<Integer>read(answer.body(), "$.data.charts.scoresByPlant[0].rank")).isEqualTo(1);
        assertThat(absent(answer, "charts.scoresByPlant[1].score")).as("PLANT2 has none approved: a gap").isTrue();
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.charts.scoresByPlant[1].needsActionPlan"))
                .isTrue();
        // Only PLANT1 has a score in any month, and only this month: the months before are gaps.
        List<String> series = JsonPath.read(answer.body(), "$.data.charts.plantsOverMonths[*].plant");
        assertThat(series).containsExactly("PLANT1");
        assertThat(JsonPath.<List<Object>>read(answer.body(), "$.data.charts.plantsOverMonths[0].points"))
                .hasSize(6);
        for (int month = 0; month < 5; month++) {
            assertThat(absent(answer, "charts.plantsOverMonths[0].points[" + month + "].score"))
                    .as("a gap, not a zero, in month " + month).isTrue();
            assertThat(absent(answer, "charts.averageByMonth[" + month + "].average")).isTrue();
        }
        assertThat(number(answer, "charts.plantsOverMonths[0].points[5].score")).isEqualTo(90.0);
        assertThat(number(answer, "charts.averageByMonth[5].average")).isEqualTo(90.0);
        // The fill-ins of the two months by status, Action plans left out.
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.charts.fillsByMonth[5].approved").intValue())
                .isEqualTo(1);
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.charts.fillsByMonth[5].submitted").intValue())
                .isEqualTo(1);
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.charts.fillsByMonth[4].submitted").intValue())
                .isEqualTo(2);
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.charts.actionPlansByPlant[0].open").intValue())
                .isEqualTo(1);
        assertThat(JsonPath.<String>read(answer.body(), "$.data.charts.actionPlansByPlant[0].plant"))
                .isEqualTo("PLANT1");
    }

    @Test
    void theMonthTheSpanAndTheFilterChooseWhatIsCountedAndAFormOfAnotherDocumentCountsNothing() {
        Filled filled = filled();
        TestBrowser approver = filled.plants().world().approver().browser();

        Response last = home(approver, "{\"month\":\"last\",\"span\":3}");
        assertThat(JsonPath.<String>read(last.body(), "$.data.month")).isEqualTo(PREVIOUS);
        assertThat(JsonPath.<List<String>>read(last.body(), "$.data.months")).hasSize(3).endsWith(PREVIOUS);
        assertThat(number(last, "tiles.plantsSubmitted.value")).isEqualTo(2);
        assertThat(number(last, "tiles.averageScore.value")).as("nothing approved in that month").isNull();

        Response chosen = home(approver, "{\"month\":\"" + PREVIOUS + "\",\"plant\":\"PLANT2\"}");
        assertThat(number(chosen, "tiles.plantsSubmitted.value")).isEqualTo(1);
        assertThat(number(chosen, "tiles.plantsSubmitted.whole")).isEqualTo(1);
        assertThat(number(chosen, "tiles.waitingForReview.value")).isEqualTo(1);
        assertThat(number(chosen, "tiles.waitingForTopManagement.value")).as("PLANT2's, this and no other month")
                .isEqualTo(1);

        Response form = home(approver, "{\"form\":\"" + filled.plants().template() + "\"}");
        assertThat(number(form, "tiles.plantsSubmitted.value")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(form.body(), "$.data.filters.forms[*].id"))
                .contains(filled.plants().template());
    }

    @Test
    void aValueTheServerDoesNotOfferIsRefusedInFixedWordsAndNeverEchoed() {
        Filled filled = filled();
        TestBrowser approver = filled.plants().world().approver().browser();
        String junk = "zz" + System.nanoTime();
        String future = YearMonth.now(ZoneOffset.UTC).plusMonths(2).toString();

        for (String body : List.of("{\"month\":\"" + future + "\"}", "{\"month\":\"" + junk + "\"}",
                "{\"span\":5}", "{\"plant\":\"" + junk + "\"}",
                "{\"form\":\"00000000-0000-4000-8000-000000000000\"}")) {
            Response refused = home(approver, body);
            assertThat(refused.status()).as(body).isEqualTo(400);
            assertThat(refused.body()).doesNotContain(junk).doesNotContain(future);
        }
    }

    // ---- who sees what ----

    @Test
    void aPlantHeadSeesOnePlantInEveryTileAndChartAndTheOtherPlantsHeadSeesTheOther() {
        Filled filled = filled();

        Response one = home(filled.plants().head1().browser());
        Response two = home(filled.plants().head2().browser());

        assertThat(number(one, "tiles.plantsSubmitted.value")).isEqualTo(1);
        assertThat(number(one, "tiles.plantsSubmitted.whole")).as("not 1 of 2: that would say what PLANT2 did")
                .isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(one.body(), "$.data.filters.plants[*].code")).containsExactly("PLANT1");
        assertThat(number(one, "tiles.waitingForTopManagement.value")).isEqualTo(1);
        assertThat(number(one, "tiles.waitingForReview.value")).isZero();
        assertThat(one.body()).doesNotContain("PLANT2");
        assertThat(number(two, "tiles.waitingForReview.value")).isEqualTo(1);
        assertThat(two.body()).doesNotContain("PLANT1");
        assertThat(home(filled.plants().head1().browser(), "{\"plant\":\"PLANT2\"}").status())
                .as("a plant a person cannot see is not offered").isEqualTo(400);
    }

    @Test
    void aPersonWhoMayViewEveryFillInSeesEveryPlantAndAPersonWithNoRightToReadThemSeesNoSectionAndNoZero() {
        Filled filled = filled();
        var setting = filled.plants().world().setting();
        Person observer = holding(setting, "Observer");
        Person nobody = outsider(setting);

        Response all = home(observer.browser());
        Response none = home(nobody.browser());

        assertThat(number(all, "tiles.plantsSubmitted.value")).isEqualTo(2);
        assertThat(number(all, "tiles.plantsSubmitted.whole")).isEqualTo(2);
        assertThat(none.status()).as(none.body()).isEqualTo(200);
        for (String path : List.of("tiles.plantsSubmitted", "tiles.averageScore", "tiles.plantsBelowLimit",
                "tiles.waitingForReview", "tiles.waitingForTopManagement", "tiles.actionPlansOpen",
                "charts.scoresByPlant", "charts.plantsOverMonths", "charts.actionPlansByPlant",
                "charts.fillsByMonth")) {
            assertThat(absent(none, path)).as(path + " is not drawn for a person who reads no fill-in").isTrue();
        }
        assertThat(JsonPath.<List<Object>>read(none.body(), "$.data.problems")).isEmpty();
    }

    @Test
    void anotherOrganizationCountsNothingOfMineAndAForgedHeaderChangesNothing() {
        Filled mine = filled();
        World theirs = world(2, "FIXED_AND_REPEATABLE", false);
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        Response theirView = home(theirs.approver().browser());
        Response forgedView = mine.plants().world().approver().browser().postJson(HOME, "{}", forged);

        assertThat(number(theirView, "tiles.plantsSubmitted.value")).isZero();
        assertThat(number(theirView, "tiles.plantsSubmitted.whole")).isZero();
        assertThat(number(forgedView, "tiles.plantsSubmitted.value")).as("still mine").isEqualTo(2);
    }

    @Test
    void thePlatformAddressTheUnsignedAndTheApplicationsWithoutThisPageAreAnsweredLikeAnythingElseOfTheCluster() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());

        assertThat(home(platform).status()).isEqualTo(404);
        Response anonymous = home(nobody);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(anonymous.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        for (String application : List.of("inspection", "document-management", "no-such-application")) {
            Response refused = world.approver().browser().postJson(
                    "/api/v1/management-home/summary?application=" + application, "{}");
            assertThat(refused.status()).as(application).isEqualTo(404);
        }
        assertThat(world.approver().browser().postJsonWithoutCsrfHeader(HOME, "{}").status()).isEqualTo(403);
    }

    @Test
    void anOrganizationWithoutTheFeatureHasNoSuchPageAndItComesBackWithIt() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        entitlements.override(world.setting().organization().id(), CORE, false, ActorId.SYSTEM);

        assertThat(home(world.approver().browser()).status()).isEqualTo(404);

        entitlements.override(world.setting().organization().id(), CORE, true, ActorId.SYSTEM);
        assertThat(home(world.approver().browser()).status()).as("it all comes back").isEqualTo(200);
    }

    // ---- the limit and the words are Setup's ----

    @Test
    void theLegendAndTheLimitAreTheNumbersOfSetupAndTheBandsCannotCrossEachOther() {
        Filled filled = filled();
        var admin = filled.plants().world().setting().admin();
        TestBrowser approver = filled.plants().world().approver().browser();

        Response before = home(approver);
        assertThat(number(before, "legend.limit")).isEqualTo(85);
        assertThat(number(before, "legend.excellentAbove")).isEqualTo(85);
        assertThat(number(before, "legend.goodAbove")).isEqualTo(65);
        assertThat(JsonPath.<String>read(before.body(), "$.data.charts.scoresByPlant[0].bandWord"))
                .isEqualTo("Excellent");

        Response stricter = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"excellentAbove\",\"value\":95}");
        assertThat(stricter.status()).as(stricter.body()).isEqualTo(200);
        Response after = home(approver);
        assertThat(number(after, "legend.excellentAbove")).isEqualTo(95);
        assertThat(JsonPath.<String>read(after.body(), "$.data.charts.scoresByPlant[0].bandWord"))
                .as("90 is no longer above the line").isEqualTo("Good");
        assertThat(number(after, "legend.limit")).as("the limit is another number").isEqualTo(85);

        Response crossed = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"goodAbove\",\"value\":96}");
        assertThat(crossed.status()).as(crossed.body()).isEqualTo(400);
        assertThat(crossed.body()).contains("lower than");
        Response tooLow = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"excellentAbove\",\"value\":60,\"version\":0}");
        assertThat(tooLow.status()).as(tooLow.body()).isEqualTo(400);
        assertThat(tooLow.body()).contains("higher than");

        Response limit = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"scoreLimit\",\"value\":92}");
        assertThat(limit.status()).as(limit.body()).isEqualTo(200);
        Response moved = home(approver);
        assertThat(number(moved, "legend.limit")).isEqualTo(92);
        assertThat(JsonPath.<Boolean>read(moved.body(), "$.data.charts.scoresByPlant[0].belowCurrentLimit"))
                .as("90 is now under the limit; its review decided under the earlier one").isTrue();
        assertThat(JsonPath.<Boolean>read(moved.body(), "$.data.charts.scoresByPlant[0].needsActionPlan"))
                .as("the stored decision did not change").isFalse();
    }
}
