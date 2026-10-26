package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The home page of the Quality Management System (FA9, ADR-0203): every number is what the lists show for the same
 * person (a differential check against the list endpoints on the same data), a person who cannot read an object gets no
 * section for it (absent, never zero), the filters narrow or are refused, other organizations and other addresses count
 * nothing, and the two small records behind the page are written by their acts and kept as written.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class QualityHomeIT extends FillSupport {

    private static final String HOME = "/api/v1/quality-home/summary";

    private static Response home(TestBrowser browser) {
        return browser.postJson(HOME, "{}");
    }

    private static Response home(TestBrowser browser, String body) {
        return browser.postJson(HOME, body);
    }

    private static double number(Response answer, String path) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return JsonPath.<Number>read(answer.body(), "$.data." + path).doubleValue();
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

    private static List<Integer> days(Response answer) {
        return JsonPath.read(answer.body(), "$.data.charts.fillsPerDay.days[*].count");
    }

    private static void sendForGood(TestBrowser filler, String fill) {
        saved(filler, fill, complete());
        Response sent = filler.postJson(FILL_INS + "/" + fill + "/submit", "{}");
        assertThat(sent.status()).as(sent.body()).isEqualTo(200);
    }

    private static int rowsOf(Response page) {
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.<List<Object>>read(page.body(), "$.data.rows").size();
    }

    // ---- the numbers equal the lists ----

    @Test
    void everyNumberAFillInTileCountsEqualsWhatTheListsShowForTheSamePerson() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String first = started(world.filler().browser(), world.template());
        String second = started(world.filler().browser(), world.template());
        started(world.filler().browser(), world.template());
        String colleagues = started(world.colleague().browser(), world.template());
        sendForGood(world.filler().browser(), first);
        sendForGood(world.filler().browser(), second);
        sendForGood(world.colleague().browser(), colleagues);

        Response forApprover = home(world.approver().browser());
        Response forFiller = home(world.filler().browser());
        Response forColleague = home(world.colleague().browser());

        assertThat(number(forApprover, "tiles.fillsSubmitted.value")).as("an approver views every fill-in")
                .isEqualTo(listedFills(world.approver().browser(), "status=SUBMITTED").size()).isEqualTo(3);
        assertThat(number(forFiller, "tiles.fillsSubmitted.value"))
                .isEqualTo(listedFills(world.filler().browser(), "status=SUBMITTED").size()).isEqualTo(2);
        assertThat(number(forColleague, "tiles.fillsSubmitted.value"))
                .isEqualTo(listedFills(world.colleague().browser(), "status=SUBMITTED").size()).isEqualTo(1);
        assertThat(days(forApprover).stream().mapToInt(Integer::intValue).sum()).as("the days add up to the tile")
                .isEqualTo(3);
        assertThat(JsonPath.<Number>read(forApprover.body(), "$.data.charts.fillsPerDay.total").intValue())
                .isEqualTo(3);
        assertThat(number(forApprover, "tiles.fillsSubmitted.previous")).isZero();
    }

    @Test
    void theTemplateTilesEqualTheListOfTheSamePersonAndAnAuthorSeesOnlyTheirOwn() {
        Setting setting = setting();
        Person authorA = author(setting);
        Person authorB = author(setting);
        Person approver = approver(setting);
        Person reader = reader(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        department(setting.admin(), "PLANT1", "PRD", group(setting.admin(), "dept-" + System.nanoTime(), reader));
        String active = templateWithSheet(authorA.browser(), "WI", "PRD");
        String waiting = templateWithSheet(authorA.browser(), "SOP", "PRD");
        templateWithSheet(authorA.browser(), "WI", "QLT");
        String other = templateWithSheet(authorB.browser(), "WI", "PRD");
        assertThat(approve(approver.browser(), submitted(authorA.browser(), active, process)).status()).isEqualTo(200);
        assertThat(publish(approver.browser(), active, null, "PLANT1/PRD").status()).isEqualTo(200);
        submitted(authorA.browser(), waiting, process);
        submitted(authorB.browser(), other, process);

        Response forApprover = home(approver.browser());
        Response forA = home(authorA.browser());
        Response forB = home(authorB.browser());

        assertThat(number(forApprover, "tiles.waitingApproval.value"))
                .isEqualTo(rowsOf(approver.browser().get(TEMPLATES + "?state=IN_REVIEW"))).isEqualTo(2);
        assertThat(number(forA, "tiles.waitingApproval.value"))
                .isEqualTo(rowsOf(authorA.browser().get(TEMPLATES + "?state=IN_REVIEW"))).isEqualTo(1);
        assertThat(number(forB, "tiles.waitingApproval.value")).isEqualTo(1);
        assertThat(number(forApprover, "tiles.activeTemplates.value"))
                .isEqualTo(rowsOf(approver.browser().get(TEMPLATES + "?state=ACTIVE"))).isEqualTo(1);
        assertThat(number(forB, "tiles.activeTemplates.value")).as("B sees none of A's").isZero();
        assertThat(number(forApprover, "tiles.waitingApproval.extra")).isGreaterThanOrEqualTo(0);
        assertThat(JsonPath.<java.util.List<Integer>>read(forApprover.body(),
                "$.data.charts.templatesByStatus[?(@.key=='DRAFT')].count")).containsExactly(1);
    }

    @Test
    void aPersonWhoCannotReadAnObjectGetsNoSectionForItAndNeverAZero() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String fill = started(world.filler().browser(), world.template());
        sendForGood(world.filler().browser(), fill);

        Response author = home(world.author().browser());

        assertThat(absent(author, "tiles.fillsSubmitted")).isTrue();
        assertThat(absent(author, "tiles.fillsWaiting")).isTrue();
        assertThat(absent(author, "charts.fillsPerDay")).isTrue();
        assertThat(absent(author, "charts.fillsByTemplate")).isTrue();
        assertThat(absent(author, "charts.fillsByDepartment")).isTrue();
        assertThat(absent(author, "tiles.activeTemplates")).as("their templates are theirs to see").isFalse();
        assertThat(JsonPath.<List<String>>read(author.body(), "$.data.problems")).isEmpty();
        assertThat(absent(home(world.outsider().browser()), "tiles.activeTemplates")).isTrue();
    }

    @Test
    void publishingAndDecidingAreCountedAndTheRejectedShareFollowsTheDecisions() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        Response first = home(world.approver().browser());

        assertThat(number(first, "tiles.templatesPublished.value")).isEqualTo(1);
        assertThat(number(first, "tiles.averageApprovalDays.value")).isGreaterThanOrEqualTo(0);
        assertThat(number(first, "tiles.rejectedShare.value")).isZero();
        assertThat(JsonPath.<List<Number>>read(first.body(), "$.data.charts.publishedPerMonth[*].count")
                .stream().mapToInt(Number::intValue).sum()).isEqualTo(1);
        assertThat(JsonPath.<String>read(first.body(), "$.data.charts.recentActivity[0].kind"))
                .isIn("PUBLISHED", "SUBMITTED");
        assertThat(JsonPath.<List<String>>read(first.body(), "$.data.charts.approvalStepDays[*].name"))
                .containsExactly("Only");

        String second = templateWithSheet(world.author().browser(), "SOP", "QLT");
        String request = submitted(world.author().browser(), second, world.templateProcess());
        assertThat(reject(world.approver().browser(), request).status()).isEqualTo(200);

        Response after = home(world.approver().browser());
        assertThat(number(after, "tiles.rejectedShare.value")).isEqualTo(50.0);
        assertThat(number(after, "tiles.waitingApproval.value")).isZero();
    }

    @Test
    void thePublishedTemplateIsInTheActivityOfThePeopleWhoMaySeeItAndOfNoOneElse() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);

        Response approver = home(world.approver().browser());
        Response outsider = home(world.outsider().browser());

        assertThat(JsonPath.<List<String>>read(approver.body(), "$.data.charts.recentActivity[*].kind"))
                .contains("PUBLISHED");
        assertThat(JsonPath.<List<String>>read(approver.body(), "$.data.charts.recentActivity[*].about"))
                .contains("template");
        assertThat(JsonPath.<List<Object>>read(outsider.body(), "$.data.charts.recentActivity")).isEmpty();
    }

    // ---- the filters ----

    @Test
    void aDepartmentOrALocationNarrowsTheFillInsAndAFilterThatIsNotOfferedIsRefused() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String fill = started(world.filler().browser(), world.template());
        sendForGood(world.filler().browser(), fill);
        TestBrowser approver = world.approver().browser();

        assertThat(number(home(approver, "{\"department\":\"PRD\"}"), "tiles.fillsSubmitted.value")).isEqualTo(1);
        assertThat(number(home(approver, "{\"location\":\"PLANT1\"}"), "tiles.fillsSubmitted.value")).isEqualTo(1);
        assertThat(number(home(approver, "{\"location\":\"PLANT1\",\"department\":\"PRD\"}"),
                "tiles.fillsSubmitted.value")).isEqualTo(1);
        Response none = home(approver, "{\"department\":\"ZZZZ9\"}");
        assertThat(none.status()).as(none.body()).isEqualTo(400);
        assertThat(home(approver, "{\"location\":\"NOWHERE\"}").status()).isEqualTo(400);
        assertThat(home(approver, "{\"period\":\"yesterday\"}").status()).isEqualTo(400);
        assertThat(home(approver, "{\"location\":\"PLANT1\",\"department\":\"NOTHERE\"}").status()).isEqualTo(400);
        Response body = home(approver, "{\"department\":\"PRD\"}");
        assertThat(JsonPath.<Boolean>read(body.body(), "$.data.tiles.fillsSubmitted.byDepartment")).isTrue();
        assertThat(JsonPath.<Boolean>read(body.body(), "$.data.tiles.waitingApproval.byDepartment")).isFalse();
        assertThat(JsonPath.<String>read(body.body(), "$.data.applied.department")).isEqualTo("PRD");
        assertThat(JsonPath.<List<String>>read(body.body(), "$.data.filters.locations[*].code")).contains("PLANT1");
    }

    @Test
    void everyPeriodIsAnsweredAndAnEmptyBodyIsThirtyDays() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        TestBrowser approver = world.approver().browser();
        int[] lengths = {7, 30, 90, 365};
        String[] periods = {"7d", "30d", "90d", "12m"};

        for (int i = 0; i < periods.length; i++) {
            Response answer = home(approver, "{\"period\":\"" + periods[i] + "\"}");
            assertThat(days(answer)).as(periods[i]).hasSize(lengths[i]);
            assertThat(JsonPath.<String>read(answer.body(), "$.data.period.key")).isEqualTo(periods[i]);
        }
        assertThat(days(approver.postJson(HOME, "{}"))).hasSize(30);
        assertThat(JsonPath.<String>read(home(approver, "{\"period\":\"90d\"}").body(),
                "$.data.charts.fillsPerDay.days[0].day")).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    // ---- other organizations, other addresses, no feature ----

    @Test
    void anotherOrganizationCountsNothingOfMineAndAForgedHeaderChangesNothing() {
        World mine = world(2, "FIXED_AND_REPEATABLE", false);
        World theirs = world(2, "FIXED_AND_REPEATABLE", false);
        String fill = started(mine.filler().browser(), mine.template());
        sendForGood(mine.filler().browser(), fill);
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        Response theirView = home(theirs.approver().browser());
        Response forgedView = mine.approver().browser().postJson(HOME, "{}", forged);

        assertThat(number(theirView, "tiles.fillsSubmitted.value")).isZero();
        assertThat(number(theirView, "tiles.activeTemplates.value")).isEqualTo(1);
        assertThat(number(forgedView, "tiles.fillsSubmitted.value")).as("still mine").isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(theirView.body(), "$.data.charts.fillsByTemplate[*].templateId"))
                .doesNotContain(mine.template());
    }

    @Test
    void thePlatformAddressAndAPersonWhoIsNotSignedInAreAnsweredLikeAnythingElseOfTheCluster() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());

        assertThat(home(platform).status()).isEqualTo(404);
        Response anonymous = home(nobody);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(anonymous.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void anOrganizationWithoutTheFeatureHasNoHomePageAndItComesBackWithIt() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        switchOff(world.setting().organization());

        assertThat(home(world.approver().browser()).status()).isEqualTo(404);

        switchOn(world.setting().organization());
        assertThat(home(world.approver().browser()).status()).as("it all comes back").isEqualTo(200);
    }

    @Test
    void aStateChangingRequestWithoutTheForgeryProtectionHeaderIsRefused() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);

        Response refused = world.approver().browser().postJsonWithoutCsrfHeader(HOME, "{}");

        assertThat(refused.status()).isEqualTo(403);
    }

    // ---- the two small records ----

    @Test
    void theDepartmentsOfAFillerAreKeptOnceWhenTheFillInIsStartedAndNeverChange() throws SQLException {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String fill = started(world.filler().browser(), world.template());
        UUID id = UUID.fromString(fill);

        assertThat(scalar(world.setting().organization(), "select count(*) from inspection_fill_department "
                + "where fill_id = ? and location = 'PLANT1' and department = 'PRD'", id)).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> TenantFixtures.asTenant(world.setting().organization().id(), connection -> TenantFixtures.update(
                        connection, "insert into inspection_fill_department (tenant_id, fill_id, location, "
                                + "department, created_by, updated_by) values (?, ?, 'PLANT1', 'QLT', ?, ?)",
                        world.setting().organization().id().value(), id,
                        app.platform.sharedkernel.ActorId.SYSTEM.value(),
                        app.platform.sharedkernel.ActorId.SYSTEM.value())));
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> TenantFixtures.asTenant(world.setting().organization().id(), connection -> TenantFixtures.update(
                        connection, "update inspection_fill_department set department = 'QLT' where fill_id = ?",
                        id)));
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> TenantFixtures.asTenant(world.setting().organization().id(), connection -> TenantFixtures.update(
                        connection, "delete from inspection_fill_department where fill_id = ?", id)));
    }

    @Test
    void whatHappenedToATemplateIsWrittenByTheActAndKeptAsItWas() throws SQLException {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        UUID template = UUID.fromString(world.template());
        assertThat(scalar(world.setting().organization(), "select count(*) from inspection_template_event "
                + "where template_id = ? and kind in ('PUBLISHED', 'REVISED')", template)).isEqualTo(1);

        assertThat(unpublish(world.approver().browser(), world.template()).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(retire(world.approver().browser(), world.template()).status()).isEqualTo(200);

        assertThat(scalar(world.setting().organization(), "select count(*) from inspection_template_event "
                + "where template_id = ?", template)).isEqualTo(4);
        assertThat(scalar(world.setting().organization(), "select count(*) from inspection_template_event "
                + "where template_id = ? and kind = 'RETIRED'", template)).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> TenantFixtures.asTenant(world.setting().organization().id(), connection -> TenantFixtures.update(
                        connection, "update inspection_template_event set kind = 'PUBLISHED' where template_id = ?",
                        template)));
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> TenantFixtures.asTenant(world.setting().organization().id(), connection -> TenantFixtures.update(
                        connection, "delete from inspection_template_event where template_id = ?", template)));
    }

    @Test
    void aPublishThatChangesNothingWritesNoEventAndARetiredTemplateLeavesTheCountOfActiveOnes() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        assertThat(publish(world.approver().browser(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(200);

        assertThat(scalar(world.setting().organization(), "select count(*) from inspection_template_event "
                + "where template_id = ?", UUID.fromString(world.template()))).as("the same revision again")
                .isEqualTo(1);
        assertThat(retire(world.approver().browser(), world.template()).status()).isEqualTo(200);
        assertThat(number(home(world.approver().browser()), "tiles.activeTemplates.value")).isZero();
    }
}
