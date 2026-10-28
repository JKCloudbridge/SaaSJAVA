package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The numbering information the template form asks while a person types (FA11B, ADR-0206): which parts the chosen
 * identification asks for, whether the combination already has numbers, and who is told the next serial. Every
 * answer is advice and takes nothing.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateNumberingInfoIT extends TemplateSupport {

    private static final String INFO = TEMPLATES + "/numbering-info";

    private static Response info(TestBrowser browser, String category, String partA, String partB) {
        return browser.postJson(INFO, "{" + field("category", category) + "," + field("partA", partA) + ","
                + field("partB", partB) + "}");
    }

    private static String field(String name, String value) {
        return "\"" + name + "\":" + (value == null ? "null" : "\"" + value + "\"");
    }

    private <T> T read(Response answer, String path) {
        return JsonPath.read(answer.body(), "$.data." + path);
    }

    private Object optional(Response answer, String path) {
        try {
            return JsonPath.read(answer.body(), "$.data." + path);
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }

    @Test
    void aNewCombinationIsOfferedToTheSetterOfTheIdentityAndAStartedOneShowsItsNextSerial() {
        Setting setting = setting();
        Person plant = approver(setting);
        Person author = plainAuthor(setting);

        Response fresh = info(plant.browser(), "WI", "PRD", null);
        assertThat(fresh.status()).as(fresh.body()).isEqualTo(200);
        assertThat(this.<Boolean>read(fresh, "known")).isTrue();
        assertThat(this.<Boolean>read(fresh, "partA")).isTrue();
        assertThat(this.<Boolean>read(fresh, "partB")).isFalse();
        assertThat(this.<Boolean>read(fresh, "serial")).isTrue();
        assertThat(this.<Integer>read(fresh, "width")).isEqualTo(2);
        assertThat(this.<String>read(fresh, "prefix")).isEqualTo("WI/PRD/");
        assertThat(this.<Boolean>read(fresh, "complete")).isTrue();
        assertThat(this.<Boolean>read(fresh, "started")).isFalse();
        assertThat(this.<Boolean>read(fresh, "canStart")).isTrue();
        assertThat(optional(fresh, "nextSerial")).isNull();

        String id = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
        long version = JsonPath.<Number>read(plant.browser().get(RECORDS + "/" + id).body(), "$.data.version")
                .longValue();
        assertThat(plant.browser().request("PATCH", RECORDS + "/" + id, "{\"version\":" + version
                + ",\"fields\":{\"category\":\"WI\",\"partA\":\"PRD\",\"serial\":\"4\"}}").status()).isEqualTo(200);

        Response running = info(plant.browser(), "WI", "PRD", null);
        assertThat(this.<Boolean>read(running, "started")).isTrue();
        assertThat(this.<Integer>read(running, "nextSerial")).isEqualTo(5);
        assertThat(this.<String>read(running, "number")).isEqualTo("WI/PRD/05");
        assertThat(this.<Boolean>read(info(plant.browser(), "WI", "QLT", null), "started"))
                .as("another combination is not started").isFalse();

        Response asked = info(author.browser(), "WI", "PRD", null);
        assertThat(this.<Boolean>read(asked, "started")).as("the author sees that it has started").isTrue();
        assertThat(this.<Boolean>read(asked, "canStart")).isFalse();
        assertThat(optional(asked, "nextSerial")).as("the next serial is for those who set the identity").isNull();
        assertThat(optional(asked, "number")).isNull();
    }

    @Test
    void theAnswerFollowsWhatIsTypedAndNamesTheKindsWithoutASerial() {
        Setting setting = setting();
        Person plant = approver(setting);

        Response unfinished = info(plant.browser(), "WI", null, null);
        Response both = info(plant.browser(), "CP", "PRD", "AB-12");
        Response year = info(plant.browser(), "BP", null, "2026");
        Response onlyTheSerial = info(plant.browser(), "EHSEMP", null, null);
        Response none = info(plant.browser(), null, null, null);
        Response unknown = info(plant.browser(), "NOPE", null, null);
        Response notAPart = info(plant.browser(), "WI", "has space", null);

        assertThat(this.<Boolean>read(unfinished, "complete")).isFalse();
        assertThat(this.<String>read(unfinished, "prefix")).isEqualTo("WI/");
        assertThat(this.<Boolean>read(both, "partA")).isTrue();
        assertThat(this.<Boolean>read(both, "partB")).isTrue();
        assertThat(this.<Boolean>read(both, "serial")).isFalse();
        assertThat(this.<String>read(both, "number")).isEqualTo("CP/PRD/AB-12");
        assertThat(this.<Boolean>read(year, "partA")).isFalse();
        assertThat(this.<Boolean>read(year, "partB")).isTrue();
        assertThat(this.<String>read(year, "number")).isEqualTo("BP/2026");
        assertThat(this.<Boolean>read(onlyTheSerial, "partA")).isFalse();
        assertThat(this.<Boolean>read(onlyTheSerial, "serial")).isTrue();
        assertThat(this.<String>read(onlyTheSerial, "prefix")).isEqualTo("EHS/EMP/");
        assertThat(this.<Boolean>read(none, "known")).isFalse();
        assertThat(this.<Boolean>read(unknown, "known")).isFalse();
        assertThat(this.<Boolean>read(notAPart, "complete")).as("a value that cannot be a part counts as not typed")
                .isFalse();
        assertThat(notAPart.body()).doesNotContain("has space");
        assertThat(scalar(setting.organization(), "select count(*) from number_series_counter")).as("a read takes "
                + "nothing").isZero();
    }

    @Test
    void aMemberWhoCannotReadTemplatesAndEveryoneOutsideTheOrganizationGetNothing() {
        Setting setting = setting();
        Setting other = setting();
        Person outsider = outsider(setting);

        Response denied = info(outsider.browser(), "WI", "PRD", null);
        Response nobody = info(new TestBrowser(port, setting.organization().host()), "WI", "PRD", null);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        Response onPlatform = info(platform, "WI", "PRD", null);
        Response forged = outsider.browser().request("POST", INFO, "{\"category\":\"WI\"}", "X-Tenant-Id",
                other.organization().id().value().toString(), "X-Forwarded-Host", other.organization().host());

        assertThat(denied.status()).as(denied.body()).isEqualTo(404);
        assertThat(nobody.status()).isEqualTo(401);
        assertThat(onPlatform.status()).isEqualTo(404);
        assertThat(forged.status()).as("a header changes nothing").isEqualTo(404);
    }

    @Test
    void theAnswerIsPerOrganizationAndAnotherOrganizationsCounterIsNeverSeen() {
        Setting mine = setting();
        Setting theirs = setting();
        Person plant = approver(mine);
        Person theirPlant = approver(theirs);
        Person author = plainAuthor(mine);
        String id = template(author.browser(), "{\"name\":\"" + newName() + "\"}");
        long version = JsonPath.<Number>read(plant.browser().get(RECORDS + "/" + id).body(), "$.data.version")
                .longValue();
        assertThat(plant.browser().request("PATCH", RECORDS + "/" + id, "{\"version\":" + version
                + ",\"fields\":{\"category\":\"WI\",\"partA\":\"PRD\"}}").status()).isEqualTo(200);

        assertThat(this.<Boolean>read(info(plant.browser(), "WI", "PRD", null), "started")).isTrue();
        assertThat(this.<Boolean>read(info(theirPlant.browser(), "WI", "PRD", null), "started")).isFalse();
    }
}
