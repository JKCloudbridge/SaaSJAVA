package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The score, the limit, the Action plan and the last step (MC2B, ADR-0261) through the real application, database and
 * object store: the score is calculated from the cells by the sheet's own formula at every approval and written on the
 * header, a score equal to the limit passes and one below it needs an Action plan, the plant head's colleague (and no
 * one
 * of another plant) starts the Action plan from the low-scoring fill-in, it is reviewed by the reviewers of that plant
 * and not of another, the last step of a low-scoring fill-in is refused until the Action plan is sent, a score that
 * cannot be calculated keeps the review open, a change of the limit in Setup is used by the next approval, and the
 * score never reaches a log.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ScoreAndActionPlanIT extends PlantsSupport {

    /** Two named rows of numbers and a third whose cell is the score: achieved over possible, in percent points. */
    private static final String SCORED = "{\"formatVersion\":2,\"sheets\":[{\"key\":\"main\",\"score\":\"r3:n\","
            + "\"dimension\":2,\"rowsMode\":\"FIXED\",\"columns\":[{\"id\":\"t\",\"label\":\"Item\",\"type\":\"text\"},"
            + "{\"id\":\"n\",\"label\":\"Value\",\"type\":\"number\"}],\"headerRows\":[{\"id\":\"h1\"}],"
            + "\"rows\":[{\"id\":\"r1\",\"label\":\"Achieved\"},{\"id\":\"r2\",\"label\":\"Possible\"},"
            + "{\"id\":\"r3\",\"label\":\"Score\"}],\"cells\":{\"h1:t\":{\"value\":\"Plant activity\"},"
            + "\"r3:n\":{\"formula\":\"@{n,r1}/@{n,r2}*100\"}},\"merges\":[]}]}";

    /** The form of the Action plan: a sheet with one text row to fill. */
    private static final String PLAN = "{\"formatVersion\":2,\"sheets\":[{\"key\":\"main\",\"dimension\":2,"
            + "\"rowsMode\":\"FIXED\",\"columns\":[{\"id\":\"t\",\"label\":\"Action\",\"type\":\"text\"}],"
            + "\"headerRows\":[{\"id\":\"h1\"}],\"rows\":[{\"id\":\"r1\",\"label\":\"First\"}],"
            + "\"cells\":{\"h1:t\":{\"value\":\"Action plan\"}},\"merges\":[]}]}";

    private record Scored(Plants plants, String form) {
        TestBrowser creator() {
            return plants.creator();
        }
    }

    private static String three(String... rows) {
        return "{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[" + String.join(",", rows) + "]}]}";
    }

    private static String values(String achieved, String possible) {
        return three(row("r1", "\"n\":\"" + achieved + "\""), row("r2", "\"n\":\"" + possible + "\""));
    }

    /** A published document with a score, an Action plan form named for it, two plants and their reviewers. */
    private Scored scored() {
        Plants plants = plants();
        var world = plants.world();
        NextRevision.inUse(world.setting(), plants.creator(), world.approver(), plants.template(), SCORED);
        String form = template(plants.creator(), "WI", "PRD");
        saveSheet(plants.creator(), form, firstVersion(plants.creator(), form), PLAN);
        assertThat(approve(world.approver().browser(), submitted(plants.creator(), form, world.templateProcess()))
                .status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), form, null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(publishToPlants(plants.creator(), plants.template(),
                plant("PLANT1", reviewerUser(plants.reviewerA()), reviewerUser(plants.reviewerB())),
                plant("PLANT2", reviewerGroup(plants.reviewerGroup()))).status()).isEqualTo(200);
        Response named = plants.creator().request("PUT", TEMPLATES + "/" + plants.template()
                + "/plants/action-plan-form", "{\"actionTemplateId\":\"" + form + "\"}");
        assertThat(named.status()).as(named.body()).isEqualTo(200);
        return new Scored(plants, form);
    }

    /** A month filled with these numbers and sent; returns the fill-in and its request. */
    private String[] month(Plants plants, Person head, String plant, String period, String achieved,
            String possible) {
        String fill = startedMonth(head.browser(), plants.template(), plant, period);
        saved(head.browser(), fill, values(achieved, possible));
        return new String[] {fill, sent(head.browser(), fill, plants.process())};
    }

    private static BigDecimal score(TestBrowser browser, String fill) {
        Object stored = headerField(browser, fill, "score");
        return stored == null ? null : new BigDecimal(String.valueOf(stored));
    }

    private static long notices(Plants plants, String kind) {
        return scalar(plants.world().setting().organization(),
                "select count(*) from notification where kind = ?", kind);
    }

    @Test
    void aScoreAtTheLimitPassesAndTheReviewersAndTopManagementApproveWithoutAnActionPlan() {
        Scored scored = scored();
        Plants plants = scored.plants();
        var approver = plants.world().approver().browser();
        String[] month = month(plants, plants.head1(), "PLANT1", "2026-01", "85", "100");

        assertThat(score(approver, month[0])).as("not calculated before a reviewer decides").isNull();
        assertThat(approve(plants.reviewerA().browser(), month[1]).status()).isEqualTo(200);
        assertThat(score(approver, month[0])).isEqualByComparingTo("85.00");
        assertThat(headerField(approver, month[0], "result")).isEqualTo("PASS");
        assertThat(notices(plants, "ACTION_PLAN_NEEDED")).isZero();
        assertThat(approve(approver, month[1]).status()).isEqualTo(200);
        assertThat(fillStatus(approver, month[0])).isEqualTo("APPROVED");
    }

    @Test
    void aScoreBelowTheLimitNeedsAnActionPlanThatTheLastStepWaitsForAndTheReviewersOfThePlantReview() {
        Scored scored = scored();
        Plants plants = scored.plants();
        var approver = plants.world().approver().browser();
        String[] month = month(plants, plants.head1(), "PLANT1", "2026-02", "80", "100");

        assertThat(approve(plants.reviewerA().browser(), month[1]).status()).isEqualTo(200);
        assertThat(score(approver, month[0])).isEqualByComparingTo("80.00");
        assertThat(headerField(approver, month[0], "result")).isEqualTo("ACTION_PLAN");
        assertThat(notices(plants, "ACTION_PLAN_NEEDED")).as("the filler is told once").isEqualTo(1);

        // The last step is refused, in the engine's own conflict and the hook's words, until the Action plan is sent.
        Response early = approve(approver, month[1]);
        assertThat(early.status()).as(early.body()).isEqualTo(409);
        assertThat(message(early)).contains("Action plan");
        assertThat(fillStatus(approver, month[0])).isEqualTo("SUBMITTED");

        // Only a plant head of the plant may start it: not a plant head of another plant, not a reviewer.
        String start = FILL_INS + "/" + month[0] + "/action-plan";
        assertThat(plants.head2().browser().postJson(start, "{}").status()).as("a plant head of PLANT2").isEqualTo(404);
        assertThat(plants.reviewerA().browser().postJson(start, "{}").status()).as("a reviewer").isIn(403, 404, 409);
        Response state = plants.head1b().browser().get(start);
        assertThat(JsonPath.<Boolean>read(state.body(), "$.data.canStart")).isTrue();

        // A colleague of the same plant starts it; a second try is refused; the link is unique.
        Response made = plants.head1b().browser().postJson(start, "{}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        String plan = JsonPath.read(made.body(), "$.data.fillId");
        assertThat(plants.head1().browser().postJson(start, "{}").status()).isEqualTo(409);
        assertThat(headerField(approver, plan, "parentFill")).isEqualTo(month[0]);
        assertThat(headerField(approver, plan, "plant")).isEqualTo("PLANT1");
        assertThat(headerField(approver, plan, "period")).isEqualTo("2026-02");
        assertThat(headerStatus(approver, plan)).isEqualTo("DRAFT");

        // The Action plan is not sent yet: the last step of the fill-in it answers still waits.
        assertThat(approve(approver, month[1]).status()).isEqualTo(409);
        saved(plants.head1b().browser(), plan, three(row("r1", "\"t\":\"Fix the door\"")));
        String planRequest = sent(plants.head1b().browser(), plan, plants.process());

        // Its reviewers are those of PLANT1, through the link: not those of PLANT2.
        assertThat(tasks(plants.reviewerB().browser())).contains(planRequest);
        assertThat(tasks(plants.reviewerC().browser())).as("a reviewer of PLANT2").doesNotContain(planRequest);
        assertThat(fillView(plants.reviewerC().browser(), plan).status()).isEqualTo(404);

        // Now the first fill-in can end, and so can the Action plan.
        assertThat(approve(approver, month[1]).status()).isEqualTo(200);
        assertThat(fillStatus(approver, month[0])).isEqualTo("APPROVED");
        assertThat(approve(plants.reviewerA().browser(), planRequest).status()).isEqualTo(200);
        assertThat(approve(approver, planRequest).status()).isEqualTo(200);
        assertThat(fillStatus(approver, plan)).isEqualTo("APPROVED");
    }

    @Test
    void aScoreThatCannotBeCalculatedKeepsTheReviewOpenAndWritesNothing() {
        Scored scored = scored();
        Plants plants = scored.plants();
        var approver = plants.world().approver().browser();
        String[] month = month(plants, plants.head1(), "PLANT1", "2026-03", "5", "0");

        Response refused = approve(plants.reviewerA().browser(), month[1]);
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(message(refused)).contains("score");
        assertThat(score(approver, month[0])).isNull();
        assertThat(headerField(approver, month[0], "result")).isNull();
        assertThat(tasks(plants.reviewerA().browser())).as("the task is still open").contains(month[1]);
    }

    @Test
    void aLimitChosenInSetupIsUsedByTheNextApprovalAndTakenBackOnReset() {
        Scored scored = scored();
        Plants plants = scored.plants();
        var admin = plants.world().setting().admin();
        var approver = plants.world().approver().browser();

        Response listed = admin.get("/api/v1/application-settings");
        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(listed.body(),
                "$.data.applications[?(@.application=='management-core')].settings[0].value[0]")).isEqualTo(85);
        Response chosen = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"scoreLimit\",\"value\":90}");
        assertThat(chosen.status()).as(chosen.body()).isEqualTo(200);

        String[] month = month(plants, plants.head1(), "PLANT1", "2026-04", "88", "100");
        assertThat(approve(plants.reviewerA().browser(), month[1]).status()).isEqualTo(200);
        assertThat(headerField(approver, month[0], "result")).as("88 is below 90").isEqualTo("ACTION_PLAN");

        Response refused = admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"scoreLimit\",\"value\":101,\"version\":0}");
        assertThat(refused.status()).as("above the range").isEqualTo(400);
        Response reset = admin.request("DELETE", "/api/v1/application-settings?application=management-core"
                + "&key=scoreLimit&version=0", null);
        assertThat(reset.status()).as(reset.body()).isEqualTo(200);
    }

    @Test
    void applicationSettingsAreForThoseWhoManageThemAndOnlyForTheirOrganization() {
        Scored scored = scored();
        Plants plants = scored.plants();
        assertThat(plants.head1().browser().get("/api/v1/application-settings").status()).isEqualTo(403);
        assertThat(plants.head1().browser().request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"scoreLimit\",\"value\":50}").status())
                .isEqualTo(403);
        Response unknown = plants.world().setting().admin().request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"noSuchSetting\",\"value\":50}");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(scalar(plants.world().setting().organization(),
                "select count(*) from application_setting where deleted_at is null")).isZero();
    }

    @Test
    void aCorrectionAfterApprovalBringsTheScoreUpToDateInTheSameTransaction() {
        Scored scored = scored();
        Plants plants = scored.plants();
        var approver = plants.world().approver().browser();
        String[] month = month(plants, plants.head1(), "PLANT1", "2026-05", "90", "100");
        assertThat(approve(plants.reviewerA().browser(), month[1]).status()).isEqualTo(200);
        assertThat(approve(approver, month[1]).status()).isEqualTo(200);
        assertThat(score(approver, month[0])).isEqualByComparingTo("90.00");

        long version = fillVersion(fillView(approver, month[0]));
        Response corrected = saveCells(approver, month[0], version, values("60", "100"));
        assertThat(corrected.status()).as(corrected.body()).isEqualTo(200);
        assertThat(score(approver, month[0])).isEqualByComparingTo("60.00");
        assertThat(headerField(approver, month[0], "result")).isEqualTo("ACTION_PLAN");
    }

    @Test
    void theNumbersADashboardNeedsAreReadWithTheRecordStoresOwnList() {
        Scored scored = scored();
        Plants plants = scored.plants();
        var approver = plants.world().approver().browser();
        String[] one = month(plants, plants.head1(), "PLANT1", "2026-06", "70", "100");
        String[] two = month(plants, plants.head2(), "PLANT2", "2026-06", "95", "100");
        assertThat(approve(plants.reviewerA().browser(), one[1]).status()).isEqualTo(200);
        assertThat(approve(plants.reviewerC().browser(), two[1]).status()).isEqualTo(200);

        Response page = approver.get(FILL_INS + "?status=SUBMITTED&size=50");
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        List<String> results = JsonPath.read(page.body(), "$.data.rows[*].result");
        List<String> plantsListed = JsonPath.read(page.body(), "$.data.rows[*].plant");
        assertThat(results).containsExactlyInAnyOrder("ACTION_PLAN", "PASS");
        assertThat(plantsListed).containsExactlyInAnyOrder("PLANT1", "PLANT2");
        assertThat(JsonPath.<List<String>>read(page.body(), "$.data.rows[*].period")).containsOnly("2026-06");
        // A person who may read the fill-ins of only their own plant sees that plant's numbers and no other.
        Response own = plants.head2().browser().get(FILL_INS + "?size=50");
        assertThat(JsonPath.<List<String>>read(own.body(), "$.data.rows[*].plant")).containsExactly("PLANT2");
    }
}
