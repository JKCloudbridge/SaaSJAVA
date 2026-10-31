package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The life of a connection and the checks of its definition through the real API and PostgreSQL (Sprint 26,
 * ADR-0208, ADR-0210): what a change does to a test, what is refused while a connection is on, a stale version, the
 * limit per organization, and every kind of wrong definition answered with fixed words at its place.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = "platform.integration.limits.max-connections=3")
class IntegrationLifecycleIT extends IntegrationSupport {

    private FakeService service;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private void tested(TestBrowser admin, String name) throws InterruptedException {
        Thread.sleep(1200);
        Response result = test(admin, name);
        assertThat(JsonPath.<String>read(result.body(), "$.data.result")).as(result.body()).isEqualTo("PASSED");
    }

    @Test
    void aChangeOfTheAddressVoidsTheTestButAChangeOfTheLabelDoesNot() throws Exception {
        TestBrowser admin = adminOf(organization());
        made(admin, definition("link-one", service.url()));
        tested(admin, "link-one");
        assertThat(state(admin, "link-one")).isEqualTo("TESTED");

        Map<String, Object> relabelled = definition("link-one", service.url());
        relabelled.put("label", "Another label");
        relabelled.put("version", versionOf(admin, "link-one"));
        assertThat(change(admin, "link-one", relabelled).status()).isEqualTo(200);
        assertThat(state(admin, "link-one")).as("a label is not part of what was tested").isEqualTo("TESTED");

        Map<String, Object> moved = definition("link-one", service.url() + "/v2");
        moved.put("version", versionOf(admin, "link-one"));
        Response changed = change(admin, "link-one", moved);
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(changed.body(), "$.data.state")).isEqualTo("UNTESTED");
        assertThat(JsonPath.<Boolean>read(changed.body(), "$.data.canEnable")).isFalse();
        assertThat(enable(admin, "link-one", versionOf(admin, "link-one")).status()).isEqualTo(409);
    }

    @Test
    void aSettingChangeAlsoVoidsTheTest() throws Exception {
        TestBrowser admin = adminOf(organization());
        made(admin, definition("link-one", service.url()));
        tested(admin, "link-one");

        Map<String, Object> other = definition("link-one", service.url());
        other.put("settings", Map.of("expect", "exact"));
        other.put("version", versionOf(admin, "link-one"));

        assertThat(change(admin, "link-one", other).status()).isEqualTo(200);
        assertThat(state(admin, "link-one")).isEqualTo("UNTESTED");
    }

    @Test
    void aConnectionThatIsOnCannotBeChangedOrRemovedUntilItIsSwitchedOff() throws Exception {
        TestBrowser admin = adminOf(organization());
        made(admin, definition("link-one", service.url()));
        tested(admin, "link-one");
        assertThat(enable(admin, "link-one", versionOf(admin, "link-one")).status()).isEqualTo(200);

        Map<String, Object> edit = definition("link-one", service.url() + "/v2");
        edit.put("version", versionOf(admin, "link-one"));
        Response refusedChange = change(admin, "link-one", edit);
        Response refusedRemoval = remove(admin, "link-one");

        assertThat(refusedChange.status()).as(refusedChange.body()).isEqualTo(409);
        assertThat(refusedRemoval.status()).as(refusedRemoval.body()).isEqualTo(409);
        assertThat(state(admin, "link-one")).isEqualTo("ENABLED");
        assertThat(JsonPath.<Boolean>read(admin.get(ROOT + "/link-one").body(), "$.data.canChange")).isFalse();

        assertThat(disable(admin, "link-one").status()).isEqualTo(200);
        edit.put("version", versionOf(admin, "link-one"));
        assertThat(change(admin, "link-one", edit).status()).isEqualTo(200);
        assertThat(state(admin, "link-one")).isEqualTo("UNTESTED");
    }

    @Test
    void aDisabledConnectionWhoseDefinitionDidNotChangeCanBeSwitchedOnAgainWithoutANewTest() throws Exception {
        TestBrowser admin = adminOf(organization());
        made(admin, definition("link-one", service.url()));
        tested(admin, "link-one");
        assertThat(enable(admin, "link-one", versionOf(admin, "link-one")).status()).isEqualTo(200);
        assertThat(disable(admin, "link-one").status()).isEqualTo(200);

        assertThat(JsonPath.<Boolean>read(admin.get(ROOT + "/link-one").body(), "$.data.canEnable")).isTrue();
        assertThat(enable(admin, "link-one", versionOf(admin, "link-one")).status()).isEqualTo(200);
        assertThat(disable(admin, "link-one").status()).isEqualTo(200);
        assertThat(disable(admin, "link-one").status()).as("it is not on").isEqualTo(409);
    }

    @Test
    void aStaleVersionIsRefusedAsAConcurrentModification() throws Exception {
        TestBrowser admin = adminOf(organization());
        made(admin, definition("link-one", service.url()));
        long read = versionOf(admin, "link-one");
        Map<String, Object> first = definition("link-one", service.url());
        first.put("label", "First writer");
        first.put("version", read);
        assertThat(change(admin, "link-one", first).status()).isEqualTo(200);

        Map<String, Object> second = definition("link-one", service.url());
        second.put("label", "Second writer");
        second.put("version", read);
        Response stale = change(admin, "link-one", second);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(stale.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void aNameIsUniqueInTheOrganizationButFreeInAnother() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        TestBrowser other = adminOf(theirs);
        made(admin, definition("shared-name", "https://api.example.com"));

        assertThat(create(admin, definition("shared-name", "https://api.example.com")).status()).isEqualTo(409);
        assertThat(create(other, definition("shared-name", "https://api.example.com")).status()).isEqualTo(201);
    }

    @Test
    void theNameAndTheKindOfAConnectionNeverChangeAndANameHasAShape() {
        TestBrowser admin = adminOf(organization());
        made(admin, definition("link-one", "https://api.example.com"));

        Map<String, Object> renamed = definition("link-two", "https://api.example.com");
        renamed.put("version", versionOf(admin, "link-one"));
        Map<String, Object> retyped = definition("link-one", "https://api.example.com");
        retyped.put("connectorType", "another");
        retyped.put("version", versionOf(admin, "link-one"));

        assertThat(change(admin, "link-one", renamed).status()).isEqualTo(400);
        assertThat(change(admin, "link-one", retyped).status()).isEqualTo(400);
        assertThat(create(admin, definition("Bad Name", "https://api.example.com")).status()).isEqualTo(400);
        assertThat(create(admin, definition("x", "https://api.example.com")).status()).isEqualTo(400);
        Map<String, Object> unknownType = definition("link-three", "https://api.example.com");
        unknownType.put("connectorType", "no-such-type");
        assertThat(create(admin, unknownType).status()).isEqualTo(400);
        Map<String, Object> started = definition("link-four", "https://api.example.com");
        started.put("version", 3);
        assertThat(create(admin, started).status()).isEqualTo(400);
    }

    @Test
    void anOrganizationCannotHaveMoreConnectionsThanItsLimit() {
        TestBrowser admin = adminOf(organization());
        for (String name : List.of("link-one", "link-two", "link-three")) {
            made(admin, definition(name, "https://api.example.com"));
        }

        Response fourth = create(admin, definition("link-four", "https://api.example.com"));

        assertThat(fourth.status()).as(fourth.body()).isEqualTo(409);
        assertThat(remove(admin, "link-one").status()).isEqualTo(200);
        assertThat(create(admin, definition("link-four", "https://api.example.com")).status()).isEqualTo(201);
    }

    @Test
    void everyWrongPartOfADefinitionIsAnsweredAtItsPlaceWithoutRepeatingWhatWasTyped() {
        TestBrowser admin = adminOf(organization());
        Map<String, Object> bad = definition("link-one", "http://10.20.30.40/private-path");
        bad.put("label", "<b>zzlabel</b>");
        bad.put("authKind", "BEARER_TOKEN");
        bad.put("credentialName", "Not A Valid Name");
        bad.put("settings", Map.of("healthPath", "/a/../b", "timeoutSeconds", "500", "script", "run()"));
        bad.put("subscriptions", List.of(Map.of("event", "record.created", "object", "Missing__c"),
                Map.of("event", "record.exploded", "object", "ObjectA__c")));
        bad.put("scope", List.of(Map.of("object", "ObjectA__c", "read", false, "create", false, "update", false)));

        Response refused = create(admin, bad);

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<Map<String, Object>>read(refused.body(), "$.error.fields")).containsKeys("label",
                "baseUrl", "credentialName", "settings.healthPath", "settings.timeoutSeconds", "settings",
                "subscriptions", "scope");
        assertThat(refused.body()).doesNotContain("10.20.30.40").doesNotContain("private-path")
                .doesNotContain("Not A Valid Name").doesNotContain("Missing__c").doesNotContain("record.exploded")
                .doesNotContain("run()").doesNotContain("zzlabel");
        assertThat(JsonPath.<List<String>>read(admin.get(ROOT).body(), "$.data")).as("nothing was stored").isEmpty();
    }

    @Test
    void theAnswerCarriesTheNameOfTheCredentialAndNothingInternal() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Map<String, Object> body = definition("link-one", "https://api.example.com");
        body.put("authKind", "BASIC");
        body.put("credentialName", "partner-login");

        Response created = create(admin, body);

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.data.credentialName")).isEqualTo("partner-login");
        assertThat(JsonPath.<String>read(created.body(), "$.data.authKind")).isEqualTo("BASIC");
        assertThat(created.body()).doesNotContain("fingerprint");
        assertThat(JsonPath.<String>read(created.body(), "$.data.inboundId")).matches("[0-9a-f]{32}");
    }
}
