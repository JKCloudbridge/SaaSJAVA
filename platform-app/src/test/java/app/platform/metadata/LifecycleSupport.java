package app.platform.metadata;

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
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What the Sprint 11 integration tests share (relationships, record types, change sets, releases): an organization
 * with its administrator, and short ways to make objects, fields, record types and change sets through the real API.
 */
abstract class LifecycleSupport {

    protected static final String OBJECTS = "/api/v1/metadata/objects";
    protected static final String SETS = "/api/v1/metadata/change-sets";
    protected static final String RELEASES = "/api/v1/metadata/releases";
    private static final String PROFILES = "/api/v1/profiles";
    private static final String MEMBERS = "/api/v1/members";

    @LocalServerPort
    protected int port;

    @Autowired
    protected Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    protected Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        return organization;
    }

    protected TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    protected TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    /** A member who holds the abilities through a profile of their own. */
    protected Member memberWith(Organization organization, TestBrowser admin, String... abilities) {
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        List<String> quoted = Arrays.stream(abilities).map(ability -> "\"" + ability + "\"").toList();
        Response profile = admin.postJson(PROFILES, "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[" + String.join(",", quoted) + "]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        Response set = admin.request("PUT", MEMBERS + "/" + person.membership() + "/profile",
                "{\"profileId\":\"" + JsonPath.<String>read(profile.body(), "$.data.id") + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        return person;
    }

    // ---- objects, fields, record types ----

    protected static Response createObject(TestBrowser admin, String name) {
        Response created = admin.postJson(OBJECTS, "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"pluralLabel\":\"" + name + "s\",\"description\":\"\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created;
    }

    protected static Response createField(TestBrowser admin, String object, String fieldJson) {
        return admin.postJson(OBJECTS + "/" + object + "/fields", fieldJson);
    }

    protected static String text(String name) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"type\":\"TEXT\"}";
    }

    protected static String requiredText(String name) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"type\":\"TEXT\",\"required\":true}";
    }

    protected static String picklist(String name, String... values) {
        String options = Arrays.stream(values)
                .map(value -> "{\"value\":\"" + value + "\",\"label\":\"" + value + "\",\"active\":true}")
                .reduce((a, b) -> a + "," + b).orElse("");
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"type\":\"PICKLIST\","
                + "\"settings\":{\"values\":[" + options + "]}}";
    }

    protected static String reference(String name, String type, String target, String extraSettings) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"type\":\"" + type + "\","
                + "\"settings\":{\"targetObject\":\"" + target + "\"" + extraSettings + "}}";
    }

    protected static Response createRecordType(TestBrowser admin, String object, String recordTypeJson) {
        return admin.postJson(OBJECTS + "/" + object + "/record-types", recordTypeJson);
    }

    protected static String recordType(String name, String availableFieldsJson, String subsetsJson) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\","
                + "\"availableFields\":" + availableFieldsJson + ",\"picklistSubsets\":" + subsetsJson + "}";
    }

    protected static List<String> objectNames(TestBrowser browser) {
        Response list = browser.get(OBJECTS);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[*].apiName");
    }

    protected static List<String> fieldNames(TestBrowser browser, String object) {
        Response one = browser.get(OBJECTS + "/" + object);
        assertThat(one.status()).as(one.body()).isEqualTo(200);
        return JsonPath.read(one.body(), "$.data.fields[*].apiName");
    }

    protected static List<String> recordTypeNames(TestBrowser browser, String object) {
        Response list = browser.get(OBJECTS + "/" + object + "/record-types");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[*].apiName");
    }

    // ---- change sets ----

    protected static String createSet(TestBrowser admin, String name) {
        Response created = admin.postJson(SETS, "{\"name\":\"" + name + "\",\"description\":\"\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    protected static Response addChange(TestBrowser admin, String set, String changeJson) {
        return admin.postJson(SETS + "/" + set + "/changes", changeJson);
    }

    /** A change of the given kind; {@code requestName} is the member of the change that carries the request. */
    protected static String change(String kind, String object, String item, String requestName, String request) {
        return "{\"kind\":\"" + kind + "\",\"objectApiName\":\"" + object + "\""
                + (item == null ? "" : ",\"itemApiName\":\"" + item + "\"")
                + (requestName == null ? "" : ",\"" + requestName + "\":" + request) + "}";
    }

    protected static String createObjectRequest(String name) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"pluralLabel\":\"" + name + "s\"}";
    }

    protected static Response validate(TestBrowser admin, String set) {
        return admin.postJson(SETS + "/" + set + "/validate", "{}");
    }

    protected static Response publish(TestBrowser admin, String set) {
        return admin.postJson(SETS + "/" + set + "/publish", "{}");
    }

    protected static List<String> problems(Response report) {
        return JsonPath.read(report.body(), "$.data.problems[*].message");
    }
}
