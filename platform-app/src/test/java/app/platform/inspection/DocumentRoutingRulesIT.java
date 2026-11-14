package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The rules that route a document to an application by its number (MC1.1, ADR-0259): who may manage them, what a rule
 * may name, that one combination has one rule, that a removed or changed rule is the version read, and that nothing of
 * one organization is seen by another. The words are those of the solution that ships; the generic code has none.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class DocumentRoutingRulesIT extends TemplateSupport {

    private static final String ROUTING = "/api/v1/document-routing";
    private static final String APPLICATION = "management-core";

    private Setting routedSetting() {
        Setting setting = setting();
        entitlements.override(setting.organization().id(), APPLICATION, true, ActorId.SYSTEM);
        return setting;
    }

    private static String rule(String identification, String partA, String application, Long version) {
        return "{\"identification\":\"" + identification + "\",\"partA\":" + (partA == null ? "null"
                : "\"" + partA + "\"") + ",\"application\":\"" + application + "\",\"version\":" + version + "}";
    }

    private static Response save(TestBrowser browser, String identification, String partA, String application,
            Long version) {
        return browser.request("PUT", ROUTING, rule(identification, partA, application, version));
    }

    private static List<String> rules(TestBrowser browser) {
        Response list = browser.get(ROUTING);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data.rules[*].identification");
    }

    @Test
    void aManagerOfNumberingMakesChangesAndRemovesARuleAndTheViewNamesWhatARuleCanName() {
        Setting setting = routedSetting();
        TestBrowser admin = setting.admin();

        Response made = save(admin, "F", "MC", APPLICATION, null);
        assertThat(made.status()).as(made.body()).isEqualTo(200);

        assertThat(JsonPath.<List<String>>read(made.body(), "$.data.applications[*].application"))
                .containsExactly(APPLICATION);
        assertThat(JsonPath.<List<String>>read(made.body(), "$.data.applications[*].label"))
                .containsExactly("Management Core");
        assertThat(JsonPath.<List<String>>read(made.body(), "$.data.identifications[*].value")).contains("F", "WI");
        assertThat(JsonPath.<String>read(made.body(), "$.data.rules[0].partA")).isEqualTo("MC");
        assertThat(JsonPath.<Boolean>read(made.body(), "$.data.rules[0].available")).isTrue();
        assertThat(JsonPath.<String>read(made.body(), "$.data.rules[0].applicationLabel"))
                .isEqualTo("Management Core");
        long version = JsonPath.<Number>read(made.body(), "$.data.rules[0].version").longValue();
        String id = JsonPath.read(made.body(), "$.data.rules[0].id");

        Response changed = save(admin, "F", "MC", APPLICATION, version);
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        long next = JsonPath.<Number>read(changed.body(), "$.data.rules[0].version").longValue();
        assertThat(next).isEqualTo(version + 1);

        assertThat(admin.request("DELETE", ROUTING + "/" + id + "?version=" + version, null).status())
                .as("a version that was read before the change").isEqualTo(409);
        Response removed = admin.request("DELETE", ROUTING + "/" + id + "?version=" + next, null);
        assertThat(removed.status()).as(removed.body()).isEqualTo(200);
        assertThat(rules(admin)).isEmpty();
        assertThat(audited(setting.organization(), "inspection.routing.made")).isEqualTo(1);
        assertThat(audited(setting.organization(), "inspection.routing.changed")).isEqualTo(1);
        assertThat(audited(setting.organization(), "inspection.routing.removed")).isEqualTo(1);
    }

    @Test
    void aRuleForTheIdentificationAloneAndOneForAPairAreTwoRulesButOneCombinationHasOne() {
        Setting setting = routedSetting();
        TestBrowser admin = setting.admin();

        assertThat(save(admin, "F", null, APPLICATION, null).status()).isEqualTo(200);
        assertThat(save(admin, "F", "MC", APPLICATION, null).status()).isEqualTo(200);
        Response twice = save(admin, "F", "MC", APPLICATION, null);

        assertThat(twice.status()).as(twice.body()).isEqualTo(409);
        assertThat(rules(admin)).containsExactly("F", "F");
    }

    @Test
    void aRuleMayNameOnlyAnApplicationTheOrganizationHasWhoseDocumentsComeFromDocumentManagement() {
        Setting setting = routedSetting();
        TestBrowser admin = setting.admin();

        // The Quality Management System shows every document and never receives one by its number; an unknown name
        // and an application the organization lacks are refused in the same words.
        Response whole = save(admin, "F", "MC", "inspection", null);
        Response unknown = save(admin, "F", "MC", "no-such-application", null);
        assertThat(whole.status()).as(whole.body()).isEqualTo(400);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(whole.body(), "$.error.fields.application[0]"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.fields.application[0]"));

        entitlements.override(setting.organization().id(), APPLICATION, false, ActorId.SYSTEM);
        Response lost = save(admin, "F", "MC", APPLICATION, null);
        assertThat(lost.status()).as(lost.body()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(admin.get(ROUTING).body(), "$.data.applications[*].application"))
                .isEmpty();
    }

    @Test
    void anIdentificationThatIsNotInTheOrganizationsListAndAPartThatIsNotAPartAreRefusedWithTheirPlace() {
        Setting setting = routedSetting();
        TestBrowser admin = setting.admin();

        Response identification = save(admin, "NOPE", "MC", APPLICATION, null);
        Response lower = save(admin, "F", "mc", APPLICATION, null);
        Response long11 = save(admin, "F", "ABCDEFGHIJK", APPLICATION, null);
        Response blank = save(admin, "", "MC", APPLICATION, null);

        assertThat(identification.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(identification.body(), "$.error.fields.identification[0]"))
                .isEqualTo("Choose an identification from the list.");
        assertThat(lower.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(lower.body(), "$.error.fields.partA[0]"))
                .isEqualTo("Part A uses capital letters, digits or a hyphen, up to 10.");
        assertThat(long11.status()).isEqualTo(400);
        assertThat(blank.status()).isEqualTo(400);
        assertThat(rules(admin)).isEmpty();
    }

    @Test
    void aRuleThatNamesAnApplicationTheOrganizationLostIsShownAsUnavailableAndCanBeRemoved() {
        Setting setting = routedSetting();
        TestBrowser admin = setting.admin();
        assertThat(save(admin, "F", "MC", APPLICATION, null).status()).isEqualTo(200);

        entitlements.override(setting.organization().id(), APPLICATION, false, ActorId.SYSTEM);

        Response view = admin.get(ROUTING);
        assertThat(JsonPath.<Boolean>read(view.body(), "$.data.rules[0].available")).isFalse();
        assertThat(view.body()).doesNotContain("\"applicationLabel\":\"Management");
        String id = JsonPath.read(view.body(), "$.data.rules[0].id");
        long version = JsonPath.<Number>read(view.body(), "$.data.rules[0].version").longValue();
        assertThat(admin.request("DELETE", ROUTING + "/" + id + "?version=" + version, null).status()).isEqualTo(200);
    }

    // ---- who may ----

    @Test
    void aMemberWithoutTheAbilityIsRefusedOnEveryEndpointAndAnotherOrganizationSeesNothingOfTheRules() {
        Setting setting = routedSetting();
        assertThat(save(setting.admin(), "F", "MC", APPLICATION, null).status()).isEqualTo(200);
        Person nobody = outsider(setting);
        Person reader = reader(setting);
        Setting theirs = routedSetting();

        for (Person person : List.of(nobody, reader)) {
            assertThat(person.browser().get(ROUTING).status()).isEqualTo(403);
            assertThat(save(person.browser(), "F", "MC", APPLICATION, null).status()).isEqualTo(403);
            assertThat(person.browser().request("DELETE", ROUTING + "/" + java.util.UUID.randomUUID()
                    + "?version=0", null).status()).isEqualTo(403);
        }
        assertThat(rules(theirs.admin())).as("another organization").isEmpty();
        String id = JsonPath.read(setting.admin().get(ROUTING).body(), "$.data.rules[0].id");
        Response foreign = theirs.admin().request("DELETE", ROUTING + "/" + id + "?version=0", null);
        assertThat(foreign.status()).as("a rule of another organization is not theirs to remove").isEqualTo(409);
        assertThat(rules(setting.admin())).hasSize(1);
    }

    @Test
    void aForgedTenantHeaderOrNoSignInChangesNothing() {
        Setting mine = routedSetting();
        Setting theirs = routedSetting();
        assertThat(save(theirs.admin(), "F", "MC", APPLICATION, null).status()).isEqualTo(200);

        Response forged = mine.admin().get(ROUTING, "X-Tenant-Id", theirs.organization().id().value().toString(),
                "X-Forwarded-Host", theirs.organization().host());

        assertThat(forged.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(forged.body(), "$.data.rules[*].identification")).isEmpty();
        assertThat(new TestBrowser(port, mine.organization().host()).get(ROUTING).status()).isEqualTo(401);
        assertThat(new TestBrowser(port, mine.organization().host()).request("PUT", ROUTING,
                rule("F", "MC", APPLICATION, null)).status()).isEqualTo(401);
    }
}
