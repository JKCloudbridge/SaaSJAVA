package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the integration tests of the connector framework share (Sprint 26): organizations on a plan with and without the
 * feature, members who hold exactly the abilities a test names, and the short ways to make a definition and call the
 * endpoints. Addresses and names in these tests are generic.
 */
public abstract class IntegrationSupport {

    protected static final String ROOT = "/api/v1/integrations";
    protected static final String FEATURE = "integrations";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected Users users;

    @Autowired
    protected Plans plans;

    @Autowired
    protected Subscriptions subscriptions;

    /** An organization on a plan that has the feature. */
    protected Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 8, 3, FEATURE);
        return organization;
    }

    /** An organization on a plan that has no feature at all. */
    protected Organization organizationWithoutTheFeature() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 8, 3);
        return organization;
    }

    protected TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    protected TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    /** A member whose profile holds exactly these abilities and nothing else. */
    protected Member memberWithAbilities(Organization organization, TestBrowser admin, String... abilities) {
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        String listed = String.join(",", Arrays.stream(abilities).map(name -> "\"" + name + "\"").toList());
        Response profile = admin.postJson("/api/v1/profiles", "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[" + listed + "]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        Response set = admin.request("PUT", "/api/v1/members/" + person.membership() + "/profile",
                "{\"profileId\":\"" + JsonPath.<String>read(profile.body(), "$.data.id") + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        return person;
    }

    /** A definition of the sample connector, to be changed by the test before it is sent. */
    protected static Map<String, Object> definition(String name, String baseUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("label", "Link " + name);
        body.put("connectorType", "rest");
        body.put("baseUrl", baseUrl);
        body.put("authKind", "NONE");
        body.put("settings", new LinkedHashMap<String, String>());
        body.put("subscriptions", List.of());
        body.put("scope", List.of());
        body.put("version", 0);
        return body;
    }

    protected static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    protected static Response create(TestBrowser browser, Map<String, Object> body) {
        return browser.postJson(ROOT, json(body));
    }

    protected static Response change(TestBrowser browser, String name, Map<String, Object> body) {
        return browser.request("PUT", ROOT + "/" + name, json(body));
    }

    protected static Response test(TestBrowser browser, String name) {
        return browser.postJson(ROOT + "/" + name + "/test", "{}");
    }

    protected static Response enable(TestBrowser browser, String name, long version) {
        return browser.postJson(ROOT + "/" + name + "/enable", "{\"version\":" + version + "}");
    }

    protected static Response disable(TestBrowser browser, String name) {
        return browser.postJson(ROOT + "/" + name + "/disable", "{}");
    }

    protected static Response remove(TestBrowser browser, String name) {
        return browser.request("DELETE", ROOT + "/" + name, "");
    }

    protected static String state(TestBrowser browser, String name) {
        Response read = browser.get(ROOT + "/" + name);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return JsonPath.read(read.body(), "$.data.state");
    }

    protected static long versionOf(TestBrowser browser, String name) {
        Response read = browser.get(ROOT + "/" + name);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return ((Number) JsonPath.read(read.body(), "$.data.version")).longValue();
    }

    /** Creates the connection and returns the id from the answer. */
    protected static String made(TestBrowser browser, Map<String, Object> body) {
        Response created = create(browser, body);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }
}
