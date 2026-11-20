package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Who may publish where (FA15, ADR-0265). Corporate QMS chooses the locations at its step; the people of Plant QMS (the
 * Publisher policy) publish only to those locations, the people who hold Publisher anywhere publish to any, and a
 * template whose approval chose nothing is not limited. A limited publish never changes the rows of other locations.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class PublishLocationLimitIT extends ChangeRequestReviewSupport {

    private static final String TWO_PLANTS = "[{\"key\":\"location\",\"value\":\"PLANT1\"},"
            + "{\"key\":\"location\",\"value\":\"PLANT2\"}]";

    /** The people and the approved template of one test. */
    private record World(Setting setting, Person plant, Person corporate, Person anywhere, Person reader,
            String template) {
    }

    private static String flaggedStep(String name, boolean skippable, String... approvers) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"completion\":\"ANY\",\"approvers\":["
                + String.join(",", approvers) + "],\"skipWhenNobody\":" + skippable + ",\"asksForChoice\":true}";
    }

    private static Response approveChoosing(TestBrowser browser, String request, String choices) {
        return browser.postJson(INSTANCES + "/" + request + "/approve", "{\"version\":"
                + instanceVersion(browser, request) + ",\"comment\":null"
                + (choices == null ? "" : ",\"choices\":" + choices) + "}");
    }

    /** A person who holds Publisher anywhere (and a licence, as a person who acts must have). */
    private Person anywhere(Setting setting) {
        Person person = holding(setting, "Publisher anywhere");
        app.platform.testsupport.TestMembers.giveLicence(setting.organization().id(), person.member().membership(),
                "user");
        return person;
    }

    /**
     * Plant QMS approves, Corporate QMS approves and chooses (or has nobody and is skipped), Plant QMS approves last.
     * Plant QMS holds the plain Publisher policy: it is the limited one. Four departments are set up: two at PLANT1,
     * one at PLANT2 and one at the head office, which Corporate never chooses.
     */
    private World world(String choices) {
        Setting setting = setting();
        Person author = author(setting);
        Person plant = approver(setting);
        Person corporate = plainApprover(setting);
        Person reader = reader(setting);
        String plantGroup = group(setting.admin(), "plant-" + System.nanoTime(), plant);
        String corporateGroup = choices != null
                ? group(setting.admin(), "corporate-" + System.nanoTime(), corporate)
                : group(setting.admin(), "corporate-" + System.nanoTime());
        for (String pair : List.of("PLANT1/PRD", "PLANT1/QLT", "PLANT2/PRD", "HEAD/HR")) {
            department(setting.admin(), pair.split("/")[0], pair.split("/")[1],
                    group(setting.admin(), "dept-" + System.nanoTime(), reader));
        }
        String process = process(setting.admin(), "Flow" + System.nanoTime(), step("Plant", groupApprover(plantGroup)),
                flaggedStep("Corporate", true, groupApprover(corporateGroup)),
                step("Publish", groupApprover(plantGroup)));
        String template = templateWithSheet(author.browser(), "WI", "PRD");
        String request = submitted(author.browser(), template, process);
        assertThat(approve(plant.browser(), request).status()).isEqualTo(200);
        if (choices != null) {
            assertThat(approveChoosing(corporate.browser(), request, choices).status()).isEqualTo(200);
        }
        assertThat(approve(plant.browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(plant.browser(), request)).isEqualTo("APPROVED");
        return new World(setting, plant, corporate, anywhere(setting), reader, template);
    }

    private static List<String> livePairs(World world) {
        return owner("select location || '/' || department from inspection_publication where template_id = ? "
                + "and revoked_at is null order by location, department", UUID.fromString(world.template()));
    }

    private static String lastPublishAudit(World world) {
        List<String> trail = owner("select attributes::text from audit_record where event_type = "
                + "'inspection.template.published' and context_tenant_id = ? order by occurred_at desc",
                world.setting().organization().id().value());
        return trail.get(0);
    }

    // ---- the limit ----

    @Test
    void aLimitedPublisherPublishesToTheLocationsTheApprovalChose() {
        World world = world(TWO_PLANTS);

        Response published = publish(world.plant().browser(), world.template(), null, "PLANT1/PRD", "PLANT2/PRD");

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(livePairs(world)).containsExactly("PLANT1/PRD", "PLANT2/PRD");
        assertThat(state(world.plant().browser(), world.template())).isEqualTo("ACTIVE");
    }

    @Test
    void aDepartmentOfAChosenLocationIsAHintAndAnyDepartmentOfItMayBePublished() {
        World world = world("[{\"key\":\"location\",\"value\":\"PLANT1\"},"
                + "{\"key\":\"department\",\"value\":\"PLANT1/PRD\"}]");

        Response published = publish(world.plant().browser(), world.template(), null, "PLANT1/QLT");

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(livePairs(world)).containsExactly("PLANT1/QLT");
    }

    @Test
    void aPickOutsideTheChosenLocationsIsRefusedAtItsPlaceAndNothingChanges() {
        World world = world(TWO_PLANTS);

        Response refused = publish(world.plant().browser(), world.template(), null, "PLANT1/PRD", "HEAD/HR");

        assertThat(refused.status()).as(refused.body()).isBetween(400, 499);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<String>read(refused.body(), "$.error.fields['audiences[1]'][0]"))
                .isEqualTo("This location was not chosen for this document.");
        assertThat(refused.body()).as("the words repeat no code").doesNotContain("HEAD").doesNotContain("HR\"");
        assertThat(state(world.plant().browser(), world.template())).isEqualTo("APPROVED");
        assertThat(publications(world.setting().organization(), world.template(), false)).isZero();
        assertThat(shares(world.setting().organization(), world.template())).isZero();
    }

    @Test
    void aTemplateWhoseApprovalChoseNothingIsNotLimited() {
        World world = world(null);

        Response published = publish(world.plant().browser(), world.template(), null, "HEAD/HR");

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(livePairs(world)).containsExactly("HEAD/HR");
        assertThat(JsonPath.<Boolean>read(lifecycle(world.plant().browser(), world.template()).body(),
                "$.data.limitedToSuggested")).isFalse();
    }

    // ---- the ability ----

    @Test
    void aPersonWhoMayPublishAnywhereReplacesTheWholeAudienceAsBefore() {
        World world = world(TWO_PLANTS);
        assertThat(publish(world.plant().browser(), world.template(), null, "PLANT1/PRD", "PLANT2/PRD").status())
                .isEqualTo(200);

        Response published = publish(world.anywhere().browser(), world.template(), null, "HEAD/HR");

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(livePairs(world)).as("what was not asked for is taken back").containsExactly("HEAD/HR");
        assertThat(lastPublishAudit(world)).contains("\"limited\": \"false\"").contains("\"kept\": \"0\"");
    }

    // ---- a limited publish changes only its own locations ----

    @Test
    void aLimitedPublishLeavesTheRowsOfOtherLocationsAloneAndSaysHowManyItKept() {
        World world = world(TWO_PLANTS);
        assertThat(publish(world.plant().browser(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(publish(world.anywhere().browser(), world.template(), null, "PLANT1/PRD", "HEAD/HR").status())
                .isEqualTo(200);

        Response published = publish(world.plant().browser(), world.template(), null, "PLANT1/PRD", "PLANT2/PRD");

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(livePairs(world)).containsExactly("HEAD/HR", "PLANT1/PRD", "PLANT2/PRD");
        assertThat(lastPublishAudit(world)).contains("\"limited\": \"true\"").contains("\"kept\": \"1\"");
    }

    @Test
    void aGroupLeftOutOfALimitedPublishIsTakenBackInsideTheScopeOnly() {
        World world = world(TWO_PLANTS);
        assertThat(publish(world.plant().browser(), world.template(), null, "PLANT1/PRD", "PLANT2/PRD").status())
                .isEqualTo(200);
        assertThat(publish(world.anywhere().browser(), world.template(), null, "PLANT1/PRD", "PLANT2/PRD",
                "HEAD/HR").status()).isEqualTo(200);

        Response published = publish(world.plant().browser(), world.template(), null, "PLANT2/PRD");

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(livePairs(world)).containsExactly("HEAD/HR", "PLANT2/PRD");
    }

    @Test
    void twoPublishesByTheSameGroupKeepTheFirstWhenTheSecondListsIt() {
        World world = world(TWO_PLANTS);
        assertThat(publish(world.plant().browser(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(200);

        Response second = publish(world.plant().browser(), world.template(), null, "PLANT1/PRD", "PLANT2/PRD");

        assertThat(second.status()).as(second.body()).isEqualTo(200);
        assertThat(livePairs(world)).containsExactly("PLANT1/PRD", "PLANT2/PRD");
        assertThat(shares(world.setting().organization(), world.template())).isEqualTo(2);
    }

    // ---- the view ----

    @Test
    void theViewNamesTheLocationsNotPublishedToYetAndSaysWhoIsLimited() {
        World world = world(TWO_PLANTS);
        assertThat(publish(world.plant().browser(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(200);

        Response limited = lifecycle(world.plant().browser(), world.template());
        Response anywhere = lifecycle(world.anywhere().browser(), world.template());
        Response reader = lifecycle(world.reader().browser(), world.template());

        assertThat(JsonPath.<Boolean>read(limited.body(), "$.data.limitedToSuggested")).isTrue();
        assertThat(JsonPath.<Boolean>read(limited.body(), "$.data.noPlaceLeft")).isFalse();
        assertThat(JsonPath.<List<String>>read(limited.body(), "$.data.unpublishedPlaces[*].location"))
                .containsExactly("PLANT2");
        assertThat(JsonPath.<List<String>>read(limited.body(), "$.data.unpublishedPlaces[*].locationLabel"))
                .containsExactly("PLANT2 - Plant 2");
        assertThat(JsonPath.<Boolean>read(anywhere.body(), "$.data.limitedToSuggested")).isFalse();
        assertThat(JsonPath.<List<String>>read(anywhere.body(), "$.data.unpublishedPlaces[*].location"))
                .containsExactly("PLANT2");
        assertThat(reader.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(reader.body(), "$.data.limitedToSuggested")).isFalse();
        assertThat(JsonPath.<List<Object>>read(reader.body(), "$.data.unpublishedPlaces")).isEmpty();
    }

    // ---- who may, other organizations, a forged host, the logs ----

    @Test
    void aPersonWithoutThePublishAbilityIsRefusedAndAnotherOrganizationSeesNothing() {
        World world = world(TWO_PLANTS);
        Setting other = setting();

        assertThat(publish(world.corporate().browser(), world.template(), null, "PLANT1/PRD").status())
                .as("sees the template, lacks the ability").isEqualTo(403);
        assertThat(publish(world.reader().browser(), world.template(), null, "PLANT1/PRD").status())
                .as("cannot see an unpublished template at all").isEqualTo(404);
        assertThat(publish(other.admin(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(404);
        assertThat(lifecycle(other.admin(), world.template()).status()).isEqualTo(404);
        assertThat(state(world.plant().browser(), world.template())).isEqualTo("APPROVED");
    }

    @Test
    void aForgedOrganizationInTheHeadersChangesNothingForALimitedPublisher() {
        World mine = world(TWO_PLANTS);
        World theirs = world(TWO_PLANTS);
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        Response theirs404 = mine.plant().browser().postJson(TEMPLATES + "/" + theirs.template() + "/publish",
                "{\"audiences\":" + audiences("PLANT1/PRD") + "}", forged);
        Response outside = mine.plant().browser().postJson(TEMPLATES + "/" + mine.template() + "/publish",
                "{\"audiences\":" + audiences("HEAD/HR") + "}", forged);

        assertThat(theirs404.status()).isEqualTo(404);
        assertThat(outside.status()).as("the limit is the caller's own, not the forged one's").isBetween(400, 499);
        assertThat(publications(theirs.setting().organization(), theirs.template(), false)).isZero();
    }

    @Test
    void noCodeOfAPlaceReachesTheAuditTrailAnEventOrANotice() {
        World world = world(TWO_PLANTS);
        assertThat(publish(world.plant().browser(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(publish(world.plant().browser(), world.template(), null, "HEAD/HR").status()).isBetween(400, 499);
        UUID tenant = world.setting().organization().id().value();

        String trail = String.join(" ", owner("select attributes::text from audit_record where context_tenant_id = ?"
                + " and event_type like 'inspection.template.%'", tenant));
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?", tenant));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?", tenant));

        assertThat(trail).contains("limited").contains("kept").doesNotContain("PLANT1").doesNotContain("PLANT2")
                .doesNotContain("HEAD").doesNotContain("PRD").doesNotContain("HR\"");
        assertThat(events).doesNotContain("PLANT1/PRD").doesNotContain("HEAD/HR");
        assertThat(notices).doesNotContain("PLANT1/PRD").doesNotContain("HEAD/HR");
    }
}
