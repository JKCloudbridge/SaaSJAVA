package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;

/**
 * What the integration tests of publishing a document to its plants share (MC2A, ADR-0260): an organization that has
 * Management Core and the rule "WI with Part A PRD belongs to it", a document made by an author that went through its
 * approval and is published in Document Management, the plant heads of two plants (each plant has its own group of
 * plant heads, the department {@code PLANTHEAD}), the people a creator may choose as reviewers, and an approval process
 * for the fill-ins of the application whose first step is asked of the reviewers chosen for the plant. The words of the
 * solution are in these tests because they test the solution that ships.
 */
public abstract class PlantsSupport extends FillSupport {

    protected static final String CORE = "management-core";
    protected static final String PLANT_HEADS = "PLANTHEAD";
    protected static final String ROUTING = "/api/v1/document-routing";
    protected static final String INBOX = "/api/v1/approvals/inbox?tab=TO_APPROVE";

    /** Every world of these tests has Management Core and the rule that routes the world's document to it. */
    @Override
    protected void beforeTheTemplate(Setting setting) {
        entitlements.override(setting.organization().id(), CORE, true, ActorId.SYSTEM);
        Response made = setting.admin().request("PUT", ROUTING, "{\"identification\":\"WI\",\"partA\":\"PRD\","
                + "\"application\":\"" + CORE + "\",\"version\":null}");
        assertThat(made.status()).as(made.body()).isEqualTo(200);
    }

    /**
     * The world and the people around its document.
     *
     * @param world the document (owned by {@code world.author()}, in use, published to a department of fillers)
     * @param head1 plant head of PLANT1
     * @param head1b a second plant head of PLANT1
     * @param head2 plant head of PLANT2
     * @param group1 the group of plant heads of PLANT1
     * @param group2 the group of plant heads of PLANT2
     * @param reviewerA a licensed person with no policy
     * @param reviewerB another
     * @param reviewerC a third, the only member of {@code reviewerGroup}
     * @param reviewerGroup a group of reviewers
     * @param process the approval process of the fill-ins: the chosen reviewers, then the approver
     */
    protected record Plants(World world, Person head1, Person head1b, Person head2, String group1, String group2,
            Person reviewerA, Person reviewerB, Person reviewerC, String reviewerGroup, String process) {

        TestBrowser creator() {
            return world.author().browser();
        }

        String template() {
            return world.template();
        }
    }

    protected Plants plants() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        Setting setting = world.setting();
        TestBrowser admin = setting.admin();
        Person head1 = holding(setting, "Filler");
        Person head1b = holding(setting, "Filler");
        Person head2 = holding(setting, "Filler");
        String group1 = group(admin, "heads-1-" + System.nanoTime(), head1, head1b);
        String group2 = group(admin, "heads-2-" + System.nanoTime(), head2);
        department(admin, "PLANT1", PLANT_HEADS, group1);
        department(admin, "PLANT2", PLANT_HEADS, group2);
        Person reviewerA = licensed(setting);
        Person reviewerB = licensed(setting);
        Person reviewerC = licensed(setting);
        String reviewerGroup = group(admin, "reviewers-" + System.nanoTime(), reviewerC);
        String process = fillProcess(admin, "McReview",
                step("Reviewers", "{\"kind\":\"PUBLICATION_REVIEWERS\",\"value\":null}"),
                step("Top", user(world.approver().userId())));
        return new Plants(world, head1, head1b, head2, group1, group2, reviewerA, reviewerB, reviewerC, reviewerGroup,
                process);
    }

    /** A member who holds no policy of the solution but holds a licence, so they can be asked to decide. */
    protected Person licensed(Setting setting) {
        Person person = outsider(setting);
        app.platform.testsupport.TestMembers.giveLicence(setting.organization().id(), person.member().membership(),
                "user");
        return person;
    }

    // ---- the requests ----

    protected static String reviewerUser(Person person) {
        return "{\"kind\":\"USER\",\"id\":\"" + person.userId() + "\"}";
    }

    protected static String reviewerGroup(String groupId) {
        return "{\"kind\":\"GROUP\",\"id\":\"" + groupId + "\"}";
    }

    /** One plant of a request with its reviewers (each as written by {@link #reviewerUser} or the group one). */
    protected static String plant(String code, String... reviewers) {
        return "{\"plant\":\"" + code + "\",\"reviewers\":[" + String.join(",", reviewers) + "]}";
    }

    protected static Response publishToPlants(TestBrowser browser, String template, String... plants) {
        return browser.request("PUT", TEMPLATES + "/" + template + "/plants", "{\"plants\":[" + String.join(",", plants)
                + "]}");
    }

    protected static Response plantState(TestBrowser browser, String template) {
        return browser.get(TEMPLATES + "/" + template + "/plants");
    }

    protected static List<String> plantCodes(Response state) {
        return JsonPath.read(state.body(), "$.data.plants[*].plant");
    }

    /** Whether the person can read the document's record (the share to their group is what lets them). */
    protected static boolean reads(TestBrowser browser, String template) {
        return browser.get(RECORDS + "/" + template).status() == 200;
    }

    protected static Response startMonth(TestBrowser browser, String template, String body) {
        return browser.postJson(TEMPLATES + "/" + template + "/fill-ins", body);
    }

    protected static String startedMonth(TestBrowser browser, String template, String plant, String period) {
        Response response = startMonth(browser, template, "{\"plant\":\"" + plant + "\",\"period\":\"" + period
                + "\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.data.fillId");
    }

    /** The identifiers of the requests the person has a task in. */
    protected static List<String> tasks(TestBrowser browser) {
        Response inbox = browser.get(INBOX);
        assertThat(inbox.status()).as(inbox.body()).isEqualTo(200);
        return JsonPath.read(inbox.body(), "$.data[*].instance.id");
    }

    // ---- the rows behind ----

    protected static long livePlants(Plants plants) {
        return scalar(plants.world().setting().organization(), "select count(*) from inspection_plant_publication "
                + "where template_id = ? and revoked_at is null", java.util.UUID.fromString(plants.template()));
    }

    protected static long liveReviewers(Plants plants) {
        return scalar(plants.world().setting().organization(), "select count(*) from inspection_plant_reviewer "
                + "where template_id = ? and revoked_at is null", java.util.UUID.fromString(plants.template()));
    }
}
