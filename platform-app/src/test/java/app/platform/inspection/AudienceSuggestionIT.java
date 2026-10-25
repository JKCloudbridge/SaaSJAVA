package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The audience a step of the template review suggests (FA6G, ADR-0200). Plant QMS checks and numbers, Corporate QMS
 * approves and must say where the template is meant to be published, then a last step is an ordinary approval. The
 * suggestion is kept and shown ready-filled to the person who publishes, and shares nothing: the department sees the
 * template only after the real Publish, which still needs a department and the Publisher policy.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class AudienceSuggestionIT extends ChangeRequestReviewSupport {

    private static final String LOCATION_ONLY = "[{\"key\":\"location\",\"value\":\"PLANT1\"}]";
    private static final String WITH_DEPARTMENT = "[{\"key\":\"location\",\"value\":\"PLANT1\"},"
            + "{\"key\":\"department\",\"value\":\"PLANT1/PRD\"}]";

    /** The people and the template of one review. */
    private record Flow(Setting setting, Person author, Person plant, Person corporate, Person reader,
            String template, String request) {
    }

    private static String flaggedStep(String name, boolean skippable, String... approvers) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"completion\":\"ANY\",\"approvers\":["
                + String.join(",", approvers) + "],\"skipWhenNobody\":" + skippable + ",\"asksForChoice\":true}";
    }

    /** Plant QMS, then Corporate QMS (asks for a choice), then a last step of Plant QMS, which also publishes. */
    private Flow flow(boolean corporateHasPeople) {
        Setting setting = setting();
        Person author = author(setting);
        Person plant = approver(setting);
        Person corporate = plainApprover(setting);
        Person reader = reader(setting);
        String plantGroup = group(setting.admin(), "plant-" + System.nanoTime(), plant);
        String corporateGroup = corporateHasPeople
                ? group(setting.admin(), "corporate-" + System.nanoTime(), corporate)
                : group(setting.admin(), "corporate-" + System.nanoTime());
        department(setting.admin(), "PLANT1", "PRD", group(setting.admin(), "dept-" + System.nanoTime(), reader));
        String process = process(setting.admin(), "Flow" + System.nanoTime(), step("Plant", groupApprover(plantGroup)),
                flaggedStep("Corporate", true, groupApprover(corporateGroup)),
                step("Publish", groupApprover(plantGroup)));
        String template = templateWithSheet(author.browser(), "WI", "PRD");
        String request = submitted(author.browser(), template, process);
        return new Flow(setting, author, plant, corporate, reader, template, request);
    }

    private static Response approveChoosing(TestBrowser browser, String request, String choices) {
        return browser.postJson(INSTANCES + "/" + request + "/approve", "{\"version\":"
                + instanceVersion(browser, request) + ",\"comment\":null"
                + (choices == null ? "" : ",\"choices\":" + choices) + "}");
    }

    private static void finish(Flow flow, String choices) {
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);
        if (choices != null) {
            assertThat(approveChoosing(flow.corporate().browser(), flow.request(), choices).status()).isEqualTo(200);
        }
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);
        assertThat(statusOf(flow.plant().browser(), flow.request())).isEqualTo("APPROVED");
    }

    private static long suggestionRows(Flow flow) {
        return scalar(flow.setting().organization(), "select count(*) from inspection_audience_suggestion "
                + "where template_id = ?", UUID.fromString(flow.template()));
    }

    // ---- the step that asks ----

    @Test
    void theStepIsMarkedInTheProcessAndTheRequestsSay() {
        Flow flow = flow(true);

        Response request = flow.plant().browser().get(INSTANCES + "/" + flow.request());
        assertThat(JsonPath.<List<Boolean>>read(request.body(), "$.data.steps[*].asksForChoice"))
                .containsExactly(false, true, false);
        assertThat(JsonPath.<Boolean>read(request.body(), "$.data.summary.currentStepAsksForChoice")).isFalse();
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);
        Response now = flow.corporate().browser().get(INSTANCES + "/" + flow.request());
        assertThat(JsonPath.<Boolean>read(now.body(), "$.data.summary.currentStepAsksForChoice")).isTrue();
    }

    @Test
    void corporateApprovesWithALocationOnlyAndTheSuggestionIsKeptWithNothingShared() {
        Flow flow = flow(true);
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);

        Response approved = approveChoosing(flow.corporate().browser(), flow.request(), LOCATION_ONLY);

        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(suggestionRows(flow)).isEqualTo(1);
        assertThat(shares(flow.setting().organization(), flow.template())).as("no share is made").isZero();
        assertThat(publications(flow.setting().organization(), flow.template(), false)).isZero();
        assertThat(audited(flow.setting().organization(), "inspection.audience.suggested")).isEqualTo(1);
    }

    @Test
    void approvingWithoutALocationIsRefusedAndNothingChanges() {
        Flow flow = flow(true);
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);

        Response plain = approveChoosing(flow.corporate().browser(), flow.request(), null);
        Response empty = approveChoosing(flow.corporate().browser(), flow.request(), "[]");
        Response departmentOnly = approveChoosing(flow.corporate().browser(), flow.request(),
                "[{\"key\":\"department\",\"value\":\"PLANT1/PRD\"}]");
        Response unknown = approveChoosing(flow.corporate().browser(), flow.request(),
                "[{\"key\":\"location\",\"value\":\"NOWHERE\"}]");

        for (Response refused : List.of(plain, empty, departmentOnly, unknown)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        }
        assertThat(message(plain)).isEqualTo(AUDIENCE_NEEDED);
        assertThat(message(unknown)).doesNotContain("NOWHERE");
        assertThat(statusOf(flow.plant().browser(), flow.request())).isEqualTo("PENDING");
        assertThat(suggestionRows(flow)).isZero();
        assertThat(approveChoosing(flow.corporate().browser(), flow.request(), LOCATION_ONLY).status())
                .as("the task is still open").isEqualTo(200);
    }

    private static final String AUDIENCE_NEEDED = "Choose the location this template is for, then approve.";

    @Test
    void aStepThatDoesNotAskIgnoresAChoiceSentByMistake() {
        Flow flow = flow(true);

        Response early = approveChoosing(flow.plant().browser(), flow.request(), LOCATION_ONLY);

        assertThat(early.status()).as(early.body()).isEqualTo(200);
        assertThat(suggestionRows(flow)).as("only a step that asks keeps a choice").isZero();
    }

    // ---- who sees the template ----

    @Test
    void aDepartmentPersonSeesNothingUntilTheRealPublish() {
        Flow flow = flow(true);
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);
        assertThat(approveChoosing(flow.corporate().browser(), flow.request(), WITH_DEPARTMENT).status())
                .isEqualTo(200);
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);

        assertThat(listed(flow.reader().browser())).as("approved and suggested, not published")
                .doesNotContain(flow.template());
        assertThat(publish(flow.plant().browser(), flow.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(listed(flow.reader().browser())).as("after the real publish").contains(flow.template());
    }

    // ---- Plant QMS publishes ----

    @Test
    void thePublisherIsShownTheSuggestionAndMayPublishItOrAChangedOne() {
        Flow flow = flow(true);
        finish(flow, WITH_DEPARTMENT);

        Response view = lifecycle(flow.plant().browser(), flow.template());
        assertThat(JsonPath.<List<String>>read(view.body(), "$.data.suggestedAudience[*].location"))
                .containsExactly("PLANT1");
        assertThat(JsonPath.<List<String>>read(view.body(), "$.data.suggestedAudience[*].department"))
                .containsExactly("PRD");
        assertThat(JsonPath.<Integer>read(view.body(), "$.data.suggestionDropped")).isZero();
        assertThat(JsonPath.<Boolean>read(view.body(), "$.data.canPublish")).isTrue();
        assertThat(publish(flow.plant().browser(), flow.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(state(flow.plant().browser(), flow.template())).isEqualTo("ACTIVE");
    }

    @Test
    void aLocationOnlySuggestionIsShownWithNoDepartmentAndPublishStillNeedsOne() {
        Flow flow = flow(true);
        finish(flow, LOCATION_ONLY);

        Response view = lifecycle(flow.plant().browser(), flow.template());
        assertThat(JsonPath.<List<String>>read(view.body(), "$.data.suggestedAudience[*].location"))
                .containsExactly("PLANT1");
        assertThat(JsonPath.<List<Object>>read(view.body(), "$.data.suggestedAudience[*].department")).hasSize(0);

        Response nothing = flow.plant().browser().postJson(TEMPLATES + "/" + flow.template() + "/publish",
                "{\"audiences\":[]}");
        assertThat(nothing.status()).as("a department is still required").isBetween(400, 499);
        assertThat(state(flow.plant().browser(), flow.template())).isEqualTo("APPROVED");
    }

    @Test
    void theSuggestionIsShownOnlyToThosePeopleWhoMayPublish() {
        Flow flow = flow(true);
        finish(flow, LOCATION_ONLY);

        Response corporate = lifecycle(flow.corporate().browser(), flow.template());
        assertThat(JsonPath.<List<Object>>read(corporate.body(), "$.data.suggestedAudience")).isEmpty();
        assertThat(publish(flow.corporate().browser(), flow.template(), null, "PLANT1/PRD").status())
                .as("a member without Publisher is refused").isEqualTo(403);
        assertThat(state(flow.plant().browser(), flow.template())).isEqualTo("APPROVED");
    }

    // ---- a skipped step, another organization, the database ----

    @Test
    void aSkippedCorporateStepLeavesNoSuggestion() {
        Flow flow = flow(false);

        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);
        assertThat(statusOf(flow.plant().browser(), flow.request())).isEqualTo("APPROVED");

        assertThat(suggestionRows(flow)).isZero();
        Response view = lifecycle(flow.plant().browser(), flow.template());
        assertThat(JsonPath.<List<Object>>read(view.body(), "$.data.suggestedAudience")).isEmpty();
        assertThat(JsonPath.<Integer>read(view.body(), "$.data.suggestionDropped")).isZero();
    }

    @Test
    void anotherOrganizationCannotReadTheSuggestionOrTheTemplateItBelongsTo() {
        Flow flow = flow(true);
        finish(flow, WITH_DEPARTMENT);
        Setting other = setting();

        assertThat(lifecycle(other.admin(), flow.template()).status()).isEqualTo(404);
        assertThat(other.admin().get(TEMPLATES + "/" + flow.template() + "/suggestion-options").status())
                .isEqualTo(404);
        assertThat(scalar(other.organization(), "select count(*) from inspection_audience_suggestion")).isZero();
    }

    @Test
    void theOptionsOfThePopupNeedOnlyThatTheTemplateCanBeSeen() {
        Flow flow = flow(true);
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);

        Response options = flow.corporate().browser().get(TEMPLATES + "/" + flow.template() + "/suggestion-options");

        assertThat(options.status()).as(options.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(options.body(), "$.data.locations[*].value")).contains("PLANT1");
        assertThat(flow.corporate().browser().get(TEMPLATES + "/audience-options").status())
                .as("the publish dialog's own lists stay behind the Publisher policy").isEqualTo(403);
    }

    @Test
    void theDatabaseWritesASuggestionOnlyForTheStepAndNeverChangesIt() throws SQLException {
        Flow flow = flow(true);
        assertThat(approve(flow.plant().browser(), flow.request()).status()).isEqualTo(200);
        assertThat(approveChoosing(flow.corporate().browser(), flow.request(), LOCATION_ONLY).status()).isEqualTo(200);
        UUID template = UUID.fromString(flow.template());

        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> TenantFixtures.asTenant(flow.setting().organization().id(), connection -> TenantFixtures.update(
                        connection, "insert into inspection_audience_suggestion (tenant_id, request_id, template_id, "
                                + "step_position, location, created_by, updated_by) "
                                + "values (?, ?, ?, 2, 'PLANT1', ?, ?)",
                        flow.setting().organization().id().value(), UUID.randomUUID(), template,
                        app.platform.sharedkernel.ActorId.SYSTEM.value(),
                        app.platform.sharedkernel.ActorId.SYSTEM.value())));
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> TenantFixtures.asTenant(flow.setting().organization().id(), connection -> TenantFixtures.update(
                        connection, "update inspection_audience_suggestion set location = 'HEAD' "
                                + "where template_id = ?", template)));
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> TenantFixtures.asTenant(flow.setting().organization().id(), connection -> TenantFixtures.update(
                        connection, "delete from inspection_audience_suggestion where template_id = ?", template)));
        assertThat(suggestionRows(flow)).isEqualTo(1);
    }

    @Test
    void noValueOfTheSuggestionReachesTheAuditTrailOrAnEvent() {
        Flow flow = flow(true);
        finish(flow, WITH_DEPARTMENT);

        String trail = String.join(" ", owner("select attributes::text from audit_record where event_type = "
                + "'inspection.audience.suggested' and context_tenant_id = ?",
                flow.setting().organization().id().value()));
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                flow.setting().organization().id().value()));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?",
                flow.setting().organization().id().value()));

        assertThat(trail).contains("places").doesNotContain("PLANT1").doesNotContain("PRD");
        assertThat(events).doesNotContain("PLANT1/PRD");
        assertThat(notices).doesNotContain("PLANT1/PRD");
    }
}
