package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.solution.SolutionSupport;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMembers;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every application of the kind must do, written once (FA13B, ADR-0226). A concrete test names the
 * application, its plan feature, its template object and the names of its two access policies; the battery then proves,
 * on a real PostgreSQL and the real application, that the application's templates are listed, numbered, approved and
 * counted for itself, and never mixed with the Quality Management System's. {@code EnvironmentManagementIT} runs it for
 * shipped second application; {@code ThirdApplicationIT} runs it for an application made only of data files.
 *
 * <p>The Quality Management System is the other application throughout: the organizations under test have its feature
 * too, so a leak in either direction would show.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
public abstract class ApplicationOfTheKindTests extends SolutionSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OTHER = "inspection";
    private static final String OTHER_TEMPLATE = "InspectionTemplate";

    /** The API name of the application under test. */
    protected abstract String application();

    /** Its plan feature. */
    protected abstract String applicationFeature();

    /** The object that plays the template part. */
    protected abstract String templateObject();

    /** The name of its policy that writes templates. */
    protected abstract String authorPolicy();

    /** The name of its policy that approves them. */
    protected abstract String approverPolicy();

    // ---- the people ----

    private record World(Organization organization, TestBrowser admin) {
    }

    private World world(String... features) {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 30, 3, features);
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        return new World(organization, admin);
    }

    private World both() {
        return world(applicationFeature(), FEATURE, "approvals");
    }

    private record Person(Member member, TestBrowser browser) {

        String userId() {
            return member.person().user().id().toString();
        }
    }

    private Person holding(World world, String policyName) {
        Member member = memberHolding(world.organization(), world.admin(), policyName);
        return new Person(member, as(world.organization(), member));
    }

    /** An author who also sets the identification (the test policy gives the fields the Approver normally has). */
    private Person author(World world, String policyName, String templateObject) {
        Person person = holding(world, policyName);
        String policy = JsonPath.read(world.admin().postJson(POLICIES, "{\"name\":\"identity-" + newName()
                + "\",\"description\":\"\",\"abilities\":[]}").body(), "$.data.id");
        Response access = world.admin().request("PUT", POLICIES + "/" + policy + "/data-access",
                "{\"objects\":[{\"key\":\"" + templateObject + "\",\"actions\":[\"read\",\"update\"]}],\"fields\":["
                + field(templateObject, "category") + "," + field(templateObject, "partA") + "]}");
        assertThat(access.status()).as(access.body()).isEqualTo(200);
        give(world.admin(), person.member(), policy);
        return person;
    }

    private static String field(String object, String field) {
        return "{\"key\":\"" + object + "." + field + "\",\"actions\":[\"edit\"]}";
    }

    private Person approver(World world, String policyName) {
        Person person = holding(world, policyName);
        TestMembers.giveLicence(world.organization().id(), person.member().membership(), "user");
        return person;
    }

    // ---- the calls ----

    private static String list(String application) {
        return "/api/v1/templates?size=200&application=" + application;
    }

    private static List<String> idsIn(TestBrowser browser, String application) {
        Response page = browser.get(list(application));
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data.rows[*].id");
    }

    private static String newTemplate(TestBrowser browser, String object, String category) {
        Response created = browser.postJson("/api/v1/objects/" + object + "/records",
                "{\"fields\":{\"name\":\"" + newName() + "\""
                + (category == null ? "" : ",\"category\":\"" + category + "\"") + "}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    /** Gives the template a saved, non-empty first version of its sheet. */
    private static void withSheet(TestBrowser browser, String object, String template) {
        String versions = "/api/v1/sheets/" + object + "/" + template + "/versions";
        Response started = browser.postJson(versions, "{\"dimension\":2}");
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        int number = JsonPath.<Integer>read(started.body(), "$.data.versionNo");
        Response read = browser.get(versions + "/" + number);
        long rowVersion = JsonPath.<Number>read(read.body(), "$.data.version.version").longValue();
        Response saved = browser.request("PUT", versions + "/" + number + "/grid?version=" + rowVersion,
                withContent(read.body()));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
    }

    /** The grid of the version that was read, with a header band and one named row: more than a blank start. */
    @SuppressWarnings("unchecked") // the grid is read back from our own answer
    private static String withContent(String versionBody) {
        Map<String, Object> document = JSON.readValue(JSON.writeValueAsString(JsonPath.read(versionBody,
                "$.data.grid")), new TypeReference<Map<String, Object>>() {
        });
        document.put("headerRows", new ArrayList<>(List.of(Map.of("id", "h1"))));
        document.put("rows", new ArrayList<>(List.of(Map.of("id", "r1", "label", "First"))));
        return JSON.writeValueAsString(document);
    }

    private static String approvalProcess(TestBrowser admin, String object, String approverUserId) {
        Response created = admin.postJson("/api/v1/metadata/approvals/" + object, "{\"name\":\"OneStep\","
                + "\"label\":\"OneStep\",\"description\":\"\",\"active\":true,\"entryCriteria\":null,"
                + "\"allowSelfApproval\":false,\"steps\":[{\"name\":\"Only\",\"label\":\"Only\","
                + "\"completion\":\"ANY\",\"approvers\":[{\"kind\":\"USER\",\"value\":\"" + approverUserId + "\"}]}],"
                + "\"outcomes\":null,\"fallback\":null}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.apiName");
    }

    /** Sends the template for approval, approves it, and returns the number it got. */
    private static String numbered(Person author, Person approver, TestBrowser admin, String object, String template,
            String process) {
        Response sent = author.browser().postJson("/api/v1/objects/" + object + "/records/" + template + "/approval",
                "{\"process\":\"" + process + "\",\"comment\":null}");
        assertThat(sent.status()).as(sent.body()).isEqualTo(201);
        String request = JsonPath.read(sent.body(), "$.data.summary.id");
        long version = JsonPath.<Number>read(approver.browser().get("/api/v1/approvals/instances/" + request).body(),
                "$.data.summary.version").longValue();
        Response approved = approver.browser().postJson("/api/v1/approvals/instances/" + request + "/approve",
                "{\"version\":" + version + ",\"comment\":null}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        Response record = admin.get("/api/v1/objects/" + object + "/records/" + template);
        return JsonPath.read(record.body(), "$.data.fields.number");
    }

    // ---- the gate ----

    @Test
    void anApplicationIsAnsweredOnlyWhileTheOrganizationHasItsFeatureAndAnyOtherCaseIsNotFoundInOneWords() {
        World onlyThis = world(applicationFeature(), "approvals");
        World onlyOther = world(FEATURE, "approvals");
        World neither = world("approvals");

        assertThat(onlyThis.admin().get(list(application())).status()).isEqualTo(200);
        assertThat(onlyOther.admin().get(list(application())).status()).isEqualTo(404);
        assertThat(neither.admin().get(list(application())).status()).isEqualTo(404);
        assertThat(onlyThis.admin().get(list(OTHER)).status()).as("the other application, feature missing")
                .isEqualTo(404);
        Response unknown = onlyThis.admin().get(list("no-such-application"));
        Response lacking = onlyOther.admin().get(list(application()));
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(unknown.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(lacking.body(), "$.error.message"));
    }

    @Test
    void everyEndpointOfTheKindAnswersNotFoundForAnApplicationTheOrganizationLacks() {
        World onlyOther = world(FEATURE, "approvals");
        TestBrowser admin = onlyOther.admin();

        assertThat(admin.postJson("/api/v1/templates/query?application=" + application(), "{}").status())
                .isEqualTo(404);
        assertThat(admin.get("/api/v1/fill-ins?application=" + application()).status()).isEqualTo(404);
        assertThat(admin.postJson("/api/v1/fill-ins/query?application=" + application(), "{}").status())
                .isEqualTo(404);
        assertThat(admin.postJson("/api/v1/change-requests/query?application=" + application(), "{}").status())
                .isEqualTo(404);
        assertThat(admin.postJson("/api/v1/quality-home/summary?application=" + application(), "{}").status())
                .isEqualTo(404);
    }

    @Test
    void anApplicationCanBeSwitchedOffAndThenItsTemplatesAreGoneFromViewButKept() {
        World world = both();
        Person author = holding(world, authorPolicy());
        String template = newTemplate(author.browser(), templateObject(), null);
        assertThat(idsIn(author.browser(), application())).contains(template);

        entitlements.override(world.organization().id(), applicationFeature(), false,
                app.platform.sharedkernel.ActorId.SYSTEM);

        assertThat(author.browser().get(list(application())).status()).isEqualTo(404);
        entitlements.override(world.organization().id(), applicationFeature(), true,
                app.platform.sharedkernel.ActorId.SYSTEM);
        assertThat(idsIn(author.browser(), application())).contains(template);
    }

    // ---- the separation ----

    @Test
    void whatIsMadeInOneApplicationIsNeverInTheOtherInListsLifecyclesCountsOrDownloads() {
        World world = both();
        Person hereAuthor = holding(world, authorPolicy());
        Person thereAuthor = holding(world, "Author");
        // One person who reads both lists, to see that nothing crosses even for somebody who may open both.
        Person both = holding(world, approverPolicy());
        give(world.admin(), both.member(), policyId(world.admin(), "Approver"));

        String here = newTemplate(hereAuthor.browser(), templateObject(), null);
        String there = newTemplate(thereAuthor.browser(), OTHER_TEMPLATE, null);

        assertThat(idsIn(hereAuthor.browser(), application())).containsExactly(here);
        assertThat(idsIn(thereAuthor.browser(), OTHER)).containsExactly(there);
        assertThat(idsIn(both.browser(), application())).containsExactly(here);
        assertThat(idsIn(both.browser(), OTHER)).containsExactly(there);
        // An identifier of the other set is an unknown one, in every endpoint that takes a template.
        assertThat(both.browser().get("/api/v1/templates/" + there + "/lifecycle?application=" + application())
                .status()).isEqualTo(404);
        assertThat(both.browser().get("/api/v1/templates/" + here + "/lifecycle?application=" + OTHER).status())
                .isEqualTo(404);
        assertThat(both.browser().get("/api/v1/templates/" + there + "/download?application=" + application())
                .status()).isEqualTo(404);
        assertThat(both.browser().get("/api/v1/templates/" + there + "/fill-in-options?application="
                + application()).status()).isEqualTo(404);
        assertThat(both.browser().get("/api/v1/templates/" + there + "/change-requests?application="
                + application()).status()).isEqualTo(404);
        // The home page of each counts its own records only (a new template has no sheet yet).
        assertThat(statusCount(hereAuthor.browser(), application(), "NO_SHEET")).isEqualTo(1);
        assertThat(statusCount(thereAuthor.browser(), OTHER, "NO_SHEET")).isEqualTo(1);
        newTemplate(hereAuthor.browser(), templateObject(), null);
        assertThat(statusCount(hereAuthor.browser(), application(), "NO_SHEET")).isEqualTo(2);
        assertThat(statusCount(thereAuthor.browser(), OTHER, "NO_SHEET")).isEqualTo(1);
    }

    private static int statusCount(TestBrowser browser, String application, String status) {
        Response home = browser.postJson("/api/v1/quality-home/summary?application=" + application, "{}");
        assertThat(home.status()).as(home.body()).isEqualTo(200);
        List<Integer> counts = JsonPath.read(home.body(), "$.data.charts.templatesByStatus[?(@.key=='" + status
                + "')].count");
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    @Test
    void anotherOrganizationSeesNothingOfThisOne() {
        World mine = both();
        World theirs = both();
        Person author = holding(mine, authorPolicy());
        newTemplate(author.browser(), templateObject(), null);

        assertThat(idsIn(theirs.admin(), application())).isEmpty();
    }

    @Test
    void theAccessPoliciesOfOneApplicationGiveNothingInTheOther() {
        World world = both();
        Person quality = holding(world, "Author");

        assertThat(quality.browser().get(list(application())).status()).isEqualTo(404);
        Response created = quality.browser().postJson("/api/v1/objects/" + templateObject() + "/records",
                "{\"fields\":{\"name\":\"" + newName() + "\"}}");
        assertThat(created.status()).as("no right on the objects of the other application").isIn(403, 404);
    }

    // ---- numbering and approval for the application's own records ----

    @Test
    void approvalAndNumberingWorkOnTheApplicationsOwnObjectsAndTheCountersAreSeparate() {
        World world = both();
        Person here = author(world, authorPolicy(), templateObject());
        Person there = author(world, "Author", OTHER_TEMPLATE);
        Person approverHere = approver(world, approverPolicy());
        Person approverThere = approver(world, "Approver");
        String processHere = approvalProcess(world.admin(), templateObject(), approverHere.userId());
        String processThere = approvalProcess(world.admin(), OTHER_TEMPLATE, approverThere.userId());

        String first = newTemplate(here.browser(), templateObject(), "IMS");
        withSheet(here.browser(), templateObject(), first);
        String second = newTemplate(there.browser(), OTHER_TEMPLATE, "IMS");
        withSheet(there.browser(), OTHER_TEMPLATE, second);

        String numberHere = numbered(here, approverHere, world.admin(), templateObject(), first, processHere);
        String numberThere = numbered(there, approverThere, world.admin(), OTHER_TEMPLATE, second, processThere);

        // The same identification in both applications starts at the same number: the counters are not shared, and the
        // unique index of the number (one per object) never sees a clash.
        assertThat(numberHere).isEqualTo("IMS/01");
        assertThat(numberThere).isEqualTo("IMS/01");
        // Approvers of one application cannot decide the other's request.
        Response sentThere = there.browser().postJson("/api/v1/objects/" + OTHER_TEMPLATE + "/records/" + second
                + "/approval", "{\"process\":\"" + processHere + "\",\"comment\":null}");
        assertThat(sentThere.status()).as("a process of the other application's object").isIn(404, 409);
    }
}
