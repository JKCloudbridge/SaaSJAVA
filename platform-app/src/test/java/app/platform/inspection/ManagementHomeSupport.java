package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * What the tests of the Home page that compares the plants build on (MC3, ADR-0262): a Management Core organization
 * with a document whose sheet marks a score, an Action plan form named for it, two plants with their plant heads and
 * reviewers, and a few fill-ins of this month and last month in the states the dashboard tells apart.
 */
abstract class ManagementHomeSupport extends PlantsSupport {

    protected static final String HOME = "/api/v1/management-home/summary?application=" + CORE;
    protected static final String CURRENT = YearMonth.now(ZoneOffset.UTC).toString();
    protected static final String PREVIOUS = YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString();

    /** Two rows of numbers and a third whose cell is the score: achieved over possible, in percent points. */
    protected static final String SCORED = "{\"formatVersion\":2,\"sheets\":[{\"key\":\"main\",\"score\":\"r3:n\","
            + "\"dimension\":2,\"rowsMode\":\"FIXED_AND_REPEATABLE\","
            + "\"columns\":[{\"id\":\"t\",\"label\":\"Item\",\"type\":\"text\"},"
            + "{\"id\":\"n\",\"label\":\"Value\",\"type\":\"number\"}],\"headerRows\":[{\"id\":\"h1\"}],"
            + "\"rows\":[{\"id\":\"r1\",\"label\":\"Achieved\"},{\"id\":\"r2\",\"label\":\"Possible\"},"
            + "{\"id\":\"r3\",\"label\":\"Score\"}],\"cells\":{\"h1:t\":{\"value\":\"Plant activity\"},"
            + "\"r3:n\":{\"formula\":\"@{n,r1}/@{n,r2}*100\"}},\"merges\":[]}]}";

    /** The form of the Action plan: a sheet with one text row to fill. */
    protected static final String PLAN = "{\"formatVersion\":2,\"sheets\":[{\"key\":\"main\",\"dimension\":2,"
            + "\"rowsMode\":\"FIXED_AND_REPEATABLE\",\"columns\":[{\"id\":\"t\",\"label\":\"Action\","
            + "\"type\":\"text\"}],"
            + "\"headerRows\":[{\"id\":\"h1\"}],\"rows\":[{\"id\":\"r1\",\"label\":\"First\"}],"
            + "\"cells\":{\"h1:t\":{\"value\":\"Action plan\"}},\"merges\":[]}]}";

    protected static String three(String... rows) {
        return "{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[" + String.join(",", rows) + "]}]}";
    }

    protected static String values(String achieved, String possible) {
        return three(row("r1", "\"n\":\"" + achieved + "\""), row("r2", "\"n\":\"" + possible + "\""));
    }

    /** A published document with a score, an Action plan form named for it, two plants and their reviewers. */
    protected Plants scored() {
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
        return plants;
    }

    /** A month filled with these numbers and sent; returns the fill-in and its request. */
    protected String[] month(Plants plants, Person head, String plant, String period, String achieved,
            String possible) {
        String fill = startedMonth(head.browser(), plants.template(), plant, period);
        saved(head.browser(), fill, values(achieved, possible));
        return new String[] {fill, sent(head.browser(), fill, plants.process())};
    }

    /**
     * The world of the first tests: this month, PLANT1 approved at 90 and PLANT2 waiting at the last step at 70 with no
     * Action plan; last month, PLANT2 waiting for the first review at 95 and PLANT1 at the last step at 60 with an
     * Action plan being written.
     */
    protected record Filled(Plants plants, String approved, String top, String review, String low, String plan) {
    }

    protected Filled filled() {
        Plants plants = scored();
        var approver = plants.world().approver().browser();
        String[] a = month(plants, plants.head1(), "PLANT1", CURRENT, "90", "100");
        assertThat(approve(plants.reviewerA().browser(), a[1]).status()).isEqualTo(200);
        assertThat(approve(approver, a[1]).status()).isEqualTo(200);
        String[] b = month(plants, plants.head2(), "PLANT2", CURRENT, "70", "100");
        assertThat(approve(plants.reviewerC().browser(), b[1]).status()).isEqualTo(200);
        String[] c = month(plants, plants.head2(), "PLANT2", PREVIOUS, "95", "100");
        String[] e = month(plants, plants.head1(), "PLANT1", PREVIOUS, "60", "100");
        assertThat(approve(plants.reviewerA().browser(), e[1]).status()).isEqualTo(200);
        Response made = plants.head1().browser().postJson(FILL_INS + "/" + e[0] + "/action-plan", "{}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return new Filled(plants, a[0], b[0], c[0], e[0], JsonPath.read(made.body(), "$.data.fillId"));
    }

}
