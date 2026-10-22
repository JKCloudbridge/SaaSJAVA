package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Who may do what with the endpoints of the template lifecycle (FA4, ADR-0188): every endpoint as the author, an
 * approver, a reader the template is published to, a member who has no policy of the solution, the administrator, a
 * person of another organization, a platform administrator, someone who is not signed in, and someone who forges the
 * organization. A template has no permission of its own: it follows the record, so what one person sees another does
 *not. /
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateAccessIT extends TemplateSupport {

    private record World(Setting setting, Person author, Person approver, Person reader, Person outsider,
            String template, String process) {
    }

    /** A template that is approved (so it can be published), its author, its approver and a reader of a department. */
    private World world() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        Person reader = reader(setting);
        Person outsider = outsider(setting);
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        department(setting.admin(), "PLANT1", "PRD", group(setting.admin(), "dept-" + System.nanoTime(), reader));
        String template = templateWithSheet(author.browser(), "WI", "PRD");
        assertThat(approve(approver.browser(), submitted(author.browser(), template, process)).status()).isEqualTo(200);
        return new World(setting, author, approver, reader, outsider, template, process);
    }

    private Map<String, Response> everything(TestBrowser browser, String template) {
        Map<String, Response> answers = new LinkedHashMap<>();
        answers.put("list", browser.get(TEMPLATES));
        answers.put("options", browser.get(TEMPLATES + "/audience-options"));
        answers.put("lifecycle", lifecycle(browser, template));
        answers.put("publish", publish(browser, template, null, "PLANT1/PRD"));
        answers.put("unpublish", unpublish(browser, template));
        answers.put("retire", retire(browser, template));
        return answers;
    }

    // ---- people of the organization ----

    @Test
    void theApproverMayDoEverythingTheLifecycleOffersAndTheAuthorMayReadItsOwnAndNothingElse() {
        World world = world();

        assertThat(world.approver().browser().get(TEMPLATES).status()).isEqualTo(200);
        assertThat(world.approver().browser().get(TEMPLATES + "/audience-options").status()).isEqualTo(200);
        assertThat(lifecycle(world.approver().browser(), world.template()).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(unpublish(world.approver().browser(), world.template()).status()).isEqualTo(200);
        assertThat(retire(world.approver().browser(), world.template()).status()).isEqualTo(200);

        Map<String, Response> author = everything(world.author().browser(), world.template());
        assertThat(author.get("list").status()).isEqualTo(200);
        assertThat(author.get("lifecycle").status()).as("the author sees their own template").isEqualTo(200);
        for (String act : List.of("options", "publish", "unpublish", "retire")) {
            assertThat(author.get(act).status()).as(act + " needs an ability the author does not hold").isEqualTo(403);
        }
    }

    @Test
    void aReaderMayReadWhatIsPublishedToThemAndDoNothingElse() {
        World world = world();
        assertThat(publish(world.approver().browser(), world.template(), null, "PLANT1/PRD").status()).isEqualTo(200);

        Map<String, Response> reader = everything(world.reader().browser(), world.template());

        assertThat(reader.get("list").status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(reader.get("list").body(), "$.data.rows[*].id"))
                .containsExactly(world.template());
        assertThat(JsonPath.<Boolean>read(reader.get("list").body(), "$.data.canCreate")).isFalse();
        assertThat(reader.get("lifecycle").status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(reader.get("lifecycle").body(), "$.data.canPublish")).isFalse();
        for (String act : List.of("options", "publish", "unpublish", "retire")) {
            assertThat(reader.get(act).status()).as(act).isEqualTo(403);
        }
    }

    @Test
    void aReaderTheTemplateIsNotPublishedToSeesNothingOfItAndIsToldNoMoreThanForATemplateThatDoesNotExist() {
        World world = world();
        Person elsewhere = reader(world.setting());

        Response real = lifecycle(elsewhere.browser(), world.template());
        Response invented = lifecycle(elsewhere.browser(), "0198f5c0-0000-7000-8000-0000000000aa");

        assertThat(real.status()).isEqualTo(404);
        assertThat(invented.status()).isEqualTo(404);
        assertThat(message(real)).isEqualTo(message(invented));
        assertThat(elsewhere.browser().get(TEMPLATES).status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(elsewhere.browser().get(TEMPLATES).body(), "$.data.rows[*].id"))
                .isEmpty();
        assertThat(publish(elsewhere.browser(), world.template(), null, "PLANT1/PRD").status())
                .as("not even to learn that it exists").isEqualTo(404);
    }

    @Test
    void aMemberWithNoPolicyOfTheSolutionGetsNotFoundEverywhere() {
        World world = world();

        Map<String, Response> outsider = everything(world.outsider().browser(), world.template());

        outsider.forEach((name, answer) -> assertThat(answer.status()).as(name + ": " + answer.body())
                .isIn(403, 404));
        assertThat(outsider.get("list").status()).isEqualTo(404);
        assertThat(outsider.get("lifecycle").status()).isEqualTo(404);
    }

    // ---- other organizations, the platform, nobody, a forged host ----

    @Test
    void aPersonOfAnotherOrganizationReachesNothingEvenWithTheSameIdentifiers() {
        World mine = world();
        World theirs = world();

        Map<String, Response> answers = everything(theirs.approver().browser(), mine.template());

        for (String act : List.of("lifecycle", "publish", "unpublish", "retire")) {
            assertThat(answers.get(act).status()).as(act).isEqualTo(404);
        }
        assertThat(JsonPath.<List<String>>read(answers.get("list").body(), "$.data.rows[*].id"))
                .containsExactly(theirs.template());
        assertThat(statuses(mine.author().browser(), mine.template())).containsExactly("APPROVED");
    }

    @Test
    void anOrganizationWithoutTheFeatureHasNoTemplatesAndTheyComeBackWithIt() {
        World world = world();
        switchOff(world.setting().organization());

        Map<String, Response> off = everything(world.setting().admin(), world.template());
        off.forEach((name, answer) -> assertThat(answer.status()).as(name).isEqualTo(404));

        switchOn(world.setting().organization());
        assertThat(lifecycle(world.setting().admin(), world.template()).status()).as("it all comes back")
                .isEqualTo(200);
    }

    @Test
    void aForgedOrganizationInTheHeadersChangesNothing() {
        World mine = world();
        World theirs = world();
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        Response theirTemplate = mine.approver().browser().get(TEMPLATES + "/" + theirs.template() + "/lifecycle",
                forged);
        Response own = mine.approver().browser().get(TEMPLATES + "/" + mine.template() + "/lifecycle", forged);

        assertThat(theirTemplate.status()).isEqualTo(404);
        assertThat(own.status()).isEqualTo(200);
    }

    @Test
    void aPlatformAdministratorOnThePlatformAddressReachesNoTemplate() {
        World world = world();
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));

        Map<String, Response> answers = everything(platform, world.template());

        answers.forEach((name, answer) -> assertThat(answer.status()).as(name).isEqualTo(404));
    }

    @Test
    void aPersonWhoIsNotSignedInGetsUnauthenticatedEverywhere() {
        World world = world();
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());

        Map<String, Response> answers = everything(nobody, world.template());

        answers.forEach((name, answer) -> {
            assertThat(answer.status()).as(name + ": " + answer.body()).isEqualTo(401);
            assertThat(JsonPath.<String>read(answer.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void aStateChangingRequestWithoutTheForgeryProtectionHeaderIsRefusedAndChangesNothing() {
        World world = world();

        Response refused = world.approver().browser().postJsonWithoutCsrfHeader(TEMPLATES + "/" + world.template()
                + "/publish", "{\"audiences\":" + audiences("PLANT1/PRD") + "}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("APPROVED");
        assertThat(shares(world.setting().organization(), world.template())).isZero();
    }

    @Test
    void aBadIdentifierOrAMalformedBodyIsRefusedBeforeAnythingHappens() {
        World world = world();

        Response notAnId = world.approver().browser().get(TEMPLATES + "/not-an-id/lifecycle");
        Response noBody = world.approver().browser().postJson(TEMPLATES + "/" + world.template() + "/publish", "{}");
        Response badCode = world.approver().browser().postJson(TEMPLATES + "/" + world.template() + "/publish",
                "{\"audiences\":[{\"location\":\"x y\",\"department\":\"PRD\"}]}");
        Response badState = world.approver().browser().get(TEMPLATES + "?state=SOMETHING");
        Response badSort = world.approver().browser().get(TEMPLATES + "?sort=colour");

        assertThat(notAnId.status()).isEqualTo(400);
        assertThat(noBody.status()).isEqualTo(400);
        assertThat(badCode.status()).isEqualTo(400);
        assertThat(badState.status()).isEqualTo(400);
        assertThat(badSort.status()).isEqualTo(400);
        assertThat(shares(world.setting().organization(), world.template())).isZero();
    }
}
