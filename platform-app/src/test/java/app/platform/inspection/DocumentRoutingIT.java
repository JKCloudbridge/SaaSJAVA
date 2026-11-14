package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A document's number decides which application it also belongs to (MC1.1, ADR-0259), on a real PostgreSQL and the
 * real application: the application is written when the number is made, an application narrowed to its own documents
 * lists, counts and opens only those, the footer day follows the document, a fill-in copies the application and an
 * approval process is chosen by it, the creator is told when the document is published, and a rule that changes
 * afterwards moves nothing once the first approval has fixed the number.
 *
 * <p>The organizations have the Quality Management System, whose application shows every document, and Management Core,
 * whose application is the documents routed to it.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class DocumentRoutingIT extends FillSupport {

    private static final String ROUTING = "/api/v1/document-routing";
    private static final String CORE = "management-core";
    private static final String CORE_LIST = TEMPLATES + "?size=200&application=" + CORE;
    private static final String ALL_LIST = TEMPLATES + "?size=200&application=inspection";

    /** Every world of this test has Management Core and the rule "WI with Part A PRD belongs to it". */
    @Override
    protected void beforeTheTemplate(Setting setting) {
        routeToCore(setting, "WI", "PRD");
    }

    private void routeToCore(Setting setting, String identification, String partA) {
        entitlements.override(setting.organization().id(), CORE, true, ActorId.SYSTEM);
        Response made = setting.admin().request("PUT", ROUTING, "{\"identification\":\"" + identification
                + "\",\"partA\":\"" + partA + "\",\"application\":\"" + CORE + "\",\"version\":null}");
        assertThat(made.status()).as(made.body()).isEqualTo(200);
    }

    private static String application(TestBrowser browser, String template) {
        Object found = field(browser, template, "application");
        return found == null || found.toString().isEmpty() ? null : found.toString();
    }

    private static List<String> idsOf(TestBrowser browser, String path) {
        Response page = browser.get(path);
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data.rows[*].id");
    }

    private static Response patch(TestBrowser browser, String id, String fields) {
        long version = JsonPath.<Number>read(browser.get(RECORDS + "/" + id).body(), "$.data.version").longValue();
        return browser.request("PATCH", RECORDS + "/" + id, "{\"version\":" + version + ",\"fields\":" + fields + "}");
    }

    /** A document its author made with a name only, then given its identification by Plant QMS (so it is numbered). */
    private static String numbered(Person author, Person plant, String identification, String partA) {
        String id = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
        Response saved = patch(plant.browser(), id, "{\"category\":\"" + identification + "\",\"partA\":\"" + partA
                + "\"}");
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        return id;
    }

    private static void sendAndApprove(Person author, Person approver, String process, String id) {
        fill(author.browser(), id, firstVersion(author.browser(), id));
        assertThat(approve(approver.browser(), submitted(author.browser(), id, process)).status()).isEqualTo(200);
    }

    // ---- the number decides ----

    @Test
    void theNumberMadeAtSaveDecidesTheApplicationAndTheNarrowedApplicationShowsOnlyItsOwnDocuments() {
        Setting setting = setting();
        routeToCore(setting, "WI", "PRD");
        Person author = plainAuthor(setting);
        Person plant = approver(setting);

        String routed = numbered(author, plant, "WI", "PRD");
        String other = numbered(author, plant, "WI", "OTHER");

        assertThat(number(plant.browser(), routed)).isEqualTo("WI/PRD/01");
        assertThat(application(plant.browser(), routed)).isEqualTo(CORE);
        assertThat(number(plant.browser(), other)).isEqualTo("WI/OTHER/01");
        assertThat(application(plant.browser(), other)).as("no rule fits: Document Management alone").isNull();
        assertThat(idsOf(plant.browser(), CORE_LIST)).containsExactly(routed);
        assertThat(idsOf(plant.browser(), ALL_LIST)).containsExactlyInAnyOrder(routed, other);
        assertThat(idsOf(author.browser(), CORE_LIST)).as("the author's own, narrowed").containsExactly(routed);
    }

    @Test
    void aDocumentOfAnotherApplicationIsNotFoundWhenOpenedThroughANarrowedOneInTheSameWordsAsAnUnknownOne() {
        Setting setting = setting();
        routeToCore(setting, "WI", "PRD");
        Person author = plainAuthor(setting);
        Person plant = approver(setting);
        String routed = numbered(author, plant, "WI", "PRD");
        String other = numbered(author, plant, "WI", "OTHER");

        assertThat(plant.browser().get(TEMPLATES + "/" + routed + "/lifecycle?application=" + CORE).status())
                .isEqualTo(200);
        Response notThere = plant.browser().get(TEMPLATES + "/" + other + "/lifecycle?application=" + CORE);
        Response unknown = plant.browser().get(TEMPLATES + "/" + UUID.randomUUID() + "/lifecycle?application=" + CORE);
        assertThat(notThere.status()).as(notThere.body()).isEqualTo(404);
        assertThat(message(notThere)).isEqualTo(message(unknown));
        assertThat(plant.browser().get(TEMPLATES + "/" + other + "/lifecycle?application=inspection").status())
                .isEqualTo(200);
    }

    @Test
    void aNarrowedApplicationOffersNoNewDocumentAndTheFooterDayFollowsTheDocumentWhereverItIsShown() {
        Setting setting = setting();
        routeToCore(setting, "WI", "PRD");
        Person author = plainAuthor(setting);
        Person plant = approver(setting);
        String routed = numbered(author, plant, "WI", "PRD");
        String other = numbered(author, plant, "WI", "OTHER");

        assertThat(JsonPath.<Boolean>read(author.browser().get(CORE_LIST).body(), "$.data.canCreate")).isFalse();
        assertThat(JsonPath.<Boolean>read(author.browser().get(ALL_LIST).body(), "$.data.canCreate")).isTrue();
        // Management Core asks for the footer day; it is on the document in both places, and on no other document.
        for (String application : List.of("inspection", CORE)) {
            assertThat(JsonPath.<Boolean>read(plant.browser().get(TEMPLATES + "/" + routed + "/lifecycle?application="
                    + application).body(), "$.data.footerDate")).as(application).isTrue();
        }
        assertThat(JsonPath.<Boolean>read(plant.browser().get(TEMPLATES + "/" + other
                + "/lifecycle?application=inspection").body(), "$.data.footerDate")).isFalse();
    }

    @Test
    void aRuleThatIsChangedAfterTheNumberWasMadeMovesNothingButTheFirstApprovalLooksOnceMore() {
        Setting setting = setting();
        routeToCore(setting, "WI", "PRD");
        Person author = plainAuthor(setting);
        Person plant = approver(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(plant.userId())));
        String fixed = numbered(author, plant, "WI", "PRD");
        String late = numbered(author, plant, "WI", "LATE");
        assertThat(application(plant.browser(), late)).isNull();

        // A rule made after the number was made, before the first approval: Plant QMS saves again and it follows.
        routeToCore(setting, "WI", "LATE");
        assertThat(patch(plant.browser(), late, "{\"name\":\"" + newName() + "\"}").status()).isEqualTo(200);
        assertThat(application(plant.browser(), late)).isEqualTo(CORE);
        // The first approval fixes the number and the application: a rule that no longer fits changes nothing, so both
        // keep what their last Save gave.
        removeEveryRule(setting.admin());
        sendAndApprove(author, plant, process, fixed);
        sendAndApprove(author, plant, process, late);

        assertThat(application(plant.browser(), fixed)).isEqualTo(CORE);
        assertThat(application(plant.browser(), late)).isEqualTo(CORE);
    }

    @Test
    void aDocumentAlreadyFixedKeepsItsApplicationWhenARuleIsRemovedOrChangedLater() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        TestBrowser approver = world.approver().browser();
        assertThat(application(approver, world.template())).isEqualTo(CORE);

        removeEveryRule(world.setting().admin());
        Person another = author(world.setting());
        String after = template(another.browser(), "WI", "PRD");

        assertThat(application(approver, world.template())).as("fixed at its first approval").isEqualTo(CORE);
        assertThat(application(approver, after)).as("numbered after the rule was removed").isNull();
        assertThat(idsOf(approver, CORE_LIST)).containsExactly(world.template());
    }

    private static void removeEveryRule(TestBrowser admin) {
        Response view = admin.get(ROUTING);
        List<String> ids = JsonPath.read(view.body(), "$.data.rules[*].id");
        List<Integer> versions = JsonPath.read(view.body(), "$.data.rules[*].version");
        for (int index = 0; index < ids.size(); index++) {
            assertThat(admin.request("DELETE", ROUTING + "/" + ids.get(index) + "?version=" + versions.get(index),
                    null).status()).isEqualTo(200);
        }
    }

    // ---- the creator is told ----

    @Test
    void thePublishTellsTheCreatorByANoticeThatLeadsToTheApplicationTheDocumentWasRoutedToAndQueuesTheMail() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);

        Response notices = world.author().browser().get("/api/v1/notifications");

        List<String> found = JsonPath.read(notices.body(), "$.data[?(@.kind == 'DOCUMENT_PUBLISHED')].id");
        assertThat(found).hasSize(1);
        assertThat(JsonPath.<List<String>>read(notices.body(), "$.data[?(@.kind == 'DOCUMENT_PUBLISHED')].category"))
                .containsExactly("DOCUMENTS");
        assertThat(JsonPath.<List<String>>read(notices.body(), "$.data[?(@.kind == 'DOCUMENT_PUBLISHED')].href"))
                .containsExactly("/apps/" + CORE + "/s-templates?open=" + world.template());
        Response opened = world.author().browser().postJson("/api/v1/notifications/" + found.get(0) + "/open", "{}");
        assertThat(JsonPath.<String>read(opened.body(), "$.data.href"))
                .isEqualTo("/apps/" + CORE + "/s-templates?open=" + world.template());
        // Nothing of the document is in the notice: no title and no number.
        assertThat(notices.body()).doesNotContain("WI/PRD/01");
        assertThat(owner("select count(*) from mail_queue where template = 'DOCUMENT_PUBLISHED' and user_id = ?",
                UUID.fromString(world.author().userId()))).containsExactly("1");
        // The person who published it is told nothing of their own act, and nobody else is told.
        assertThat(world.approver().browser().get("/api/v1/notifications").body()).doesNotContain("DOCUMENT_PUBLISHED");
        assertThat(world.filler().browser().get("/api/v1/notifications").body()).doesNotContain("DOCUMENT_PUBLISHED");
    }

    @Test
    void theNoticeOfADocumentThatBelongsToNoOtherApplicationLeadsToDocumentManagementsPage() {
        Setting setting = setting();
        Person author = plainAuthor(setting);
        Person plant = approver(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(plant.userId())));
        department(setting.admin(), "PLANT1", "PRD", group(setting.admin(), "dept-" + System.nanoTime(),
                holding(setting, "Filler")));
        String plain = numbered(author, plant, "WI", "PLAIN");
        sendAndApprove(author, plant, process, plain);
        assertThat(publish(plant.browser(), plain, null, "PLANT1/PRD").status()).isEqualTo(200);

        String href = JsonPath.<List<String>>read(author.browser().get("/api/v1/notifications").body(),
                "$.data[?(@.kind == 'DOCUMENT_PUBLISHED')].href").get(0);

        assertThat(href).isEqualTo("/apps/inspection/s-templates?open=" + plain);
    }

    @Test
    void theNotificationPreferencesOfferTheDocumentsCategory() {
        Setting setting = setting();
        Person author = plainAuthor(setting);

        Response preferences = author.browser().get("/api/v1/notification-preferences");

        assertThat(preferences.status()).as(preferences.body()).isEqualTo(200);
        assertThat(preferences.body()).contains("DOCUMENTS");
    }

    // ---- fill-ins copy the application and an approval process is chosen by it ----

    @Test
    void aFillInCopiesTheApplicationOfItsDocumentAndTheNarrowedListsShowOnlyTheirOwn() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String other = publishedOther(world);
        String routedFill = started(world.filler().browser(), world.template());
        String otherFill = started(world.filler().browser(), other);

        assertThat(JsonPath.<String>read(world.approver().browser().get(FILL_RECORDS + "/" + routedFill).body(),
                "$.data.fields.application")).isEqualTo(CORE);
        assertThat(world.approver().browser().get(FILL_RECORDS + "/" + otherFill).body())
                .doesNotContain("\"application\":\"");
        assertThat(listedFills(world.approver().browser(), "application=" + CORE)).containsExactly(routedFill);
        assertThat(listedFills(world.approver().browser(), "application=inspection"))
                .containsExactlyInAnyOrder(routedFill, otherFill);
        // The fill-ins of a document belonging to another application are neither listed nor counted in the report.
        assertThat(JsonPath.<List<String>>read(world.approver().browser().get(FILL_INS + "?application=" + CORE
                + "&templateId=" + other).body(), "$.data.rows[*].fillId")).isEmpty();
    }

    @Test
    void anApprovalProcessIsChosenByTheApplicationOfTheFillInThroughItsEntryCriteria() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String other = publishedOther(world);
        String user = user(world.approver().userId());
        criteriaProcess(world.setting().admin(), "OnlyDm", "ISBLANK(application)", step("DmStep", user),
                step("DmStepTwo", user));
        criteriaProcess(world.setting().admin(), "OnlyCore", "application = '" + CORE + "'", step("CoreStep", user));
        String routedFill = started(world.filler().browser(), world.template());
        String otherFill = started(world.filler().browser(), other);
        saved(world.filler().browser(), routedFill, complete());
        saved(world.filler().browser(), otherFill, complete());

        Response routedSent = world.filler().browser().postJson(FILL_RECORDS + "/" + routedFill + "/approval",
                "{\"process\":null,\"comment\":null}");
        Response otherSent = world.filler().browser().postJson(FILL_RECORDS + "/" + otherFill + "/approval",
                "{\"process\":null,\"comment\":null}");

        assertThat(routedSent.status()).as(routedSent.body()).isEqualTo(201);
        assertThat(statusOf(world.approver().browser(), JsonPath.<String>read(routedSent.body(), "$.data.summary.id")))
                .isEqualTo("PENDING");
        assertThat(JsonPath.<Integer>read(routedSent.body(), "$.data.summary.stepCount")).isEqualTo(1);
        assertThat(otherSent.status()).as(otherSent.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(otherSent.body(), "$.data.summary.stepCount")).isEqualTo(2);
    }

    private static String criteriaProcess(TestBrowser admin, String name, String criteria, String... steps) {
        Response created = admin.postJson(DEFINITIONS + "/" + FILL, "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"description\":\"\",\"active\":true,\"entryCriteria\":\"" + criteria
                + "\",\"allowSelfApproval\":false,\"steps\":[" + String.join(",", steps)
                + "],\"outcomes\":null,\"fallback\":null}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.apiName");
    }

    /** A second published document of the same sheet that no rule routes anywhere. */
    private String publishedOther(World world) {
        String other = template(world.author().browser(), "WI", "OTHER");
        int number = firstVersion(world.author().browser(), other);
        saveSheet(world.author().browser(), other, number, sheet(2, "FIXED_AND_REPEATABLE"));
        assertThat(approve(world.approver().browser(), submitted(world.author().browser(), other,
                world.templateProcess())).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), other, null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(application(world.approver().browser(), other)).isNull();
        return other;
    }
}
