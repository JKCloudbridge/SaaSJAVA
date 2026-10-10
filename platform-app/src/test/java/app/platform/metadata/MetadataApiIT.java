package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The object and field definitions (Sprint 10, ADR-0058 to ADR-0062) through the real application and a real
 * PostgreSQL: the exit criterion (two organizations define their own objects with no deployment and see only their
 * own), the protection of what the platform defines, every field type, the rules and the words of every refusal,
 * concurrency, the end of the permissions of what is removed, and the usual authorization tests (allowed, denied,
 * cross-tenant, forged tenant header, platform administrator refused).
 */
@PlatformIntegrationTest
class MetadataApiIT {

    private static final String OBJECTS = "/api/v1/metadata/objects";
    private static final String TYPES = "/api/v1/metadata/field-types";
    private static final String CATALOGUE = "/api/v1/data-catalogue";
    private static final String PROFILES = "/api/v1/profiles";
    private static final String POLICIES = "/api/v1/access-policies";
    private static final String MEMBERS = "/api/v1/members";
    private static final String EVENTS = "/api/v1/audit-events";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private Decisions decisions;

    @Autowired
    private TenantContexts contexts;

    private Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        return organization;
    }

    private TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    // ---- helpers ----

    private static String objectBody(String name, String label) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + label + "\",\"pluralLabel\":\"" + label + "s\","
                + "\"description\":\"\"}";
    }

    private static Response createObject(TestBrowser admin, String name) {
        return admin.postJson(OBJECTS, objectBody(name, name));
    }

    private static Response createField(TestBrowser admin, String object, String fieldJson) {
        return admin.postJson(OBJECTS + "/" + object + "/fields", fieldJson);
    }

    private static String textField(String name, String label) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + label + "\",\"type\":\"TEXT\"}";
    }

    private static List<String> objectNames(TestBrowser browser) {
        Response list = browser.get(OBJECTS);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[*].apiName");
    }

    private static List<String> fieldNames(TestBrowser browser, String object) {
        Response one = browser.get(OBJECTS + "/" + object);
        assertThat(one.status()).as(one.body()).isEqualTo(200);
        return JsonPath.read(one.body(), "$.data.fields[*].apiName");
    }

    private static UUID idOf(Response created) {
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    /** A member who holds the abilities through a profile of their own, so no other test setup is needed. */
    private Member memberWith(Organization organization, TestBrowser admin, String... abilities) {
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        List<String> quoted = java.util.Arrays.stream(abilities).map(ability -> "\"" + ability + "\"").toList();
        UUID profile = idOf(admin.postJson(PROFILES, "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[" + String.join(",", quoted) + "]}"));
        Response set = admin.request("PUT", MEMBERS + "/" + person.membership() + "/profile",
                "{\"profileId\":\"" + profile + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        return person;
    }

    private TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    private boolean can(Organization organization, UUID membership, String object, ObjectAction action) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> decisions.can(membership, object, action).allowed());
    }

    // ---- the exit criterion ----

    @Test
    void twoOrganizationsDefineTheirOwnObjectsWithNoDeploymentAndEachSeesOnlyItsOwn() {
        Organization first = organization();
        Organization second = organization();
        TestBrowser firstAdmin = adminOf(first);
        TestBrowser secondAdmin = adminOf(second);

        Response employee = createObject(firstAdmin, "Employee");
        Response vehicle = createObject(secondAdmin, "Vehicle");

        assertThat(employee.status()).as(employee.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(employee.body(), "$.data.apiName")).isEqualTo("Employee__c");
        assertThat(JsonPath.<String>read(employee.body(), "$.data.kind")).isEqualTo("CUSTOM");
        assertThat(vehicle.status()).as(vehicle.body()).isEqualTo(201);
        assertThat(objectNames(firstAdmin)).contains("Employee__c").doesNotContain("Vehicle__c");
        assertThat(objectNames(secondAdmin)).contains("Vehicle__c").doesNotContain("Employee__c");
        assertThat(firstAdmin.get(OBJECTS + "/Vehicle__c").status()).as("another organization's object")
                .isEqualTo(404);
        assertThat(secondAdmin.get(OBJECTS + "/Employee__c").status()).isEqualTo(404);
        assertThat(firstAdmin.get(OBJECTS + "/Vehicle__c").body()).doesNotContain(second.id().value().toString());
    }

    @Test
    void theSameNameInTwoOrganizationsIsTwoSeparateObjects() {
        Organization first = organization();
        Organization second = organization();
        TestBrowser firstAdmin = adminOf(first);
        TestBrowser secondAdmin = adminOf(second);

        assertThat(createObject(firstAdmin, "Project").status()).isEqualTo(201);
        assertThat(createObject(secondAdmin, "Project").status()).isEqualTo(201);
        assertThat(createField(firstAdmin, "Project__c", textField("budget", "Budget")).status()).isEqualTo(201);

        assertThat(fieldNames(firstAdmin, "Project__c")).contains("budget__c");
        assertThat(fieldNames(secondAdmin, "Project__c")).doesNotContain("budget__c");
    }

    // ---- what the platform defines ----

    @Test
    void theStandardObjectsAreThereForEveryOrganizationAndTheirFieldsFollowTheSystemFields() {
        TestBrowser admin = adminOf(organization());

        Response list = admin.get(OBJECTS);

        assertThat(list.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[?(@.kind=='STANDARD')].apiName"))
                .contains("Account", "Contact", "Opportunity", "Case", "User", "AccessPolicy",
                        "AccessPolicyAssignment", "Profile", "Role", "LicenceType");
        assertThat(fieldNames(admin, "Account").subList(0, 8)).containsExactly("id", "sequence", "description",
                "ownerId", "createdAt", "createdById", "updatedAt", "updatedById");
        assertThat(fieldNames(admin, "Account")).contains("name", "parentAccountId");
        Response account = admin.get(OBJECTS + "/Account");
        assertThat(JsonPath.<Boolean>read(account.body(), "$.data.editable")).isFalse();
        assertThat(JsonPath.<Boolean>read(account.body(), "$.data.extensible")).isTrue();
        assertThat(JsonPath.<String>read(admin.get(OBJECTS + "/User").body(), "$.data.managedBy"))
                .isEqualTo("identity");
    }

    @Test
    void aProtectedStandardDefinitionCannotBeChangedByAnOrganizationAndTheRefusalIsRecorded() {
        TestBrowser admin = adminOf(organization());
        String before = admin.get(OBJECTS + "/Account").body();
        String update = "{\"label\":\"Hacked\",\"pluralLabel\":\"Hacked\",\"description\":\"\",\"version\":0}";
        String field = "{\"label\":\"Hacked\",\"description\":\"\",\"required\":false,\"unique\":false,"
                + "\"settings\":{},\"version\":0}";

        Response changeObject = admin.request("PUT", OBJECTS + "/Account", update);
        Response removeObject = admin.request("DELETE", OBJECTS + "/Account", null);
        Response changeField = admin.request("PUT", OBJECTS + "/Account/fields/name", field);
        Response removeField = admin.request("DELETE", OBJECTS + "/Account/fields/name", null);
        Response changeSystemField = admin.request("PUT", OBJECTS + "/Account/fields/id", field);
        Response removeSystemField = admin.request("DELETE", OBJECTS + "/Contact/fields/ownerId", null);
        Response addToClosedObject = createField(admin, "LicenceType", textField("nickname", "Nickname"));

        for (Response refused : List.of(changeObject, removeObject, changeField, removeField, changeSystemField,
                removeSystemField, addToClosedObject)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(403);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("FORBIDDEN");
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("defined by the platform");
        }
        assertThat(admin.get(OBJECTS + "/Account").body()).as("nothing changed").isEqualTo(before);
        Response events = admin.get(EVENTS + "?kind=metadata.change.refused");
        assertThat(JsonPath.<List<String>>read(events.body(), "$.data[*].type")).hasSizeGreaterThanOrEqualTo(7);
        assertThat(events.body()).contains("protected_definition");
    }

    @Test
    void anOrganizationCanAddItsOwnFieldToAStandardObjectButNeverChangeTheStandardOnes() {
        TestBrowser admin = adminOf(organization());

        Response added = createField(admin, "Account", textField("region", "Region"));

        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(added.body(), "$.data.apiName")).isEqualTo("region__c");
        assertThat(JsonPath.<String>read(added.body(), "$.data.kind")).isEqualTo("CUSTOM");
        List<String> fields = fieldNames(admin, "Account");
        assertThat(fields.indexOf("region__c")).as("custom fields come last").isEqualTo(fields.size() - 1);
        assertThat(admin.request("DELETE", OBJECTS + "/Account/fields/region__c", null).status()).isEqualTo(204);
        assertThat(fieldNames(admin, "Account")).doesNotContain("region__c");
    }

    @Test
    void aMasterDetailCannotBeAddedToAStandardObject() {
        TestBrowser admin = adminOf(organization());

        Response refused = createField(admin, "Account", "{\"name\":\"owner\",\"label\":\"Owner\","
                + "\"type\":\"MASTER_DETAIL\",\"settings\":{\"targetObject\":\"Contact\"}}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.type"))
                .anyMatch(text -> text.contains("custom object"));
    }

    // ---- creating objects ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("badObjects")
    void aBadObjectIsRefusedInWordsThatNameTheProblem(String name, String body, String field) {
        TestBrowser admin = adminOf(organization());

        Response refused = admin.postJson(OBJECTS, body);

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields." + field + "[*]")).isNotEmpty();
    }

    static Stream<Arguments> badObjects() {
        return Stream.of(
                Arguments.of("a name that starts in lower case", objectBody("employee", "Employee"), "name"),
                Arguments.of("a name with a space", objectBody("Fleet Vehicle", "Vehicle"), "name"),
                Arguments.of("a name with the ending typed", objectBody("Employee__c", "Employee"), "name"),
                Arguments.of("a name with two underscores", objectBody("Fleet__Vehicle", "Vehicle"), "name"),
                Arguments.of("a blank label", objectBody("Thing", " "), "label"),
                Arguments.of("a label that is too long", objectBody("Thing", "x".repeat(81)), "label"),
                Arguments.of("a control character in a label", objectBody("Thing", "bad\\ntext"), "label"));
    }

    @Test
    void aNameInUseIsRefusedWhateverTheCaseAndAfterRemovalItIsFreeAgain() {
        TestBrowser admin = adminOf(organization());
        assertThat(createObject(admin, "Asset").status()).isEqualTo(201);

        Response same = createObject(admin, "Asset");
        Response otherCase = admin.postJson(OBJECTS, objectBody("ASSET", "Asset"));

        assertThat(same.status()).isEqualTo(400);
        assertThat(otherCase.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(same.body(), "$.error.fields.name[0]")).contains("exists already");
        assertThat(admin.request("DELETE", OBJECTS + "/Asset__c", null).status()).isEqualTo(204);
        assertThat(createObject(admin, "Asset").status()).as("free again").isEqualTo(201);
    }

    @Test
    void aCustomObjectComesWithTheSystemFieldsAndItsLabelsCanChange() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Gadget");

        assertThat(fieldNames(admin, "Gadget__c")).containsExactly("id", "sequence", "description", "ownerId",
                "createdAt", "createdById", "updatedAt", "updatedById");
        Response changed = admin.request("PUT", OBJECTS + "/Gadget__c",
                "{\"label\":\"Device\",\"pluralLabel\":\"Devices\",\"description\":\"Things\",\"version\":0}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(changed.body(), "$.data.label")).isEqualTo("Device");
        assertThat(JsonPath.<String>read(changed.body(), "$.data.apiName")).as("never changes")
                .isEqualTo("Gadget__c");
        assertThat(JsonPath.<Integer>read(changed.body(), "$.data.version")).isEqualTo(1);
    }

    @Test
    void aChangeOnTopOfANewerVersionIsRefused() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Gadget");
        String body = "{\"label\":\"A\",\"pluralLabel\":\"As\",\"description\":\"\",\"version\":0}";
        assertThat(admin.request("PUT", OBJECTS + "/Gadget__c", body).status()).isEqualTo(200);

        Response stale = admin.request("PUT", OBJECTS + "/Gadget__c",
                body.replace("\"A\"", "\"B\"").replace("\"As\"", "\"Bs\""));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(stale.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(JsonPath.<String>read(admin.get(OBJECTS + "/Gadget__c").body(), "$.data.label"))
                .isEqualTo("A");
    }

    // ---- fields and their types ----

    @Test
    void theFieldTypesEndpointDescribesAllNineteenTypes() {
        TestBrowser admin = adminOf(organization());

        Response types = admin.get(TYPES);

        assertThat(types.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(types.body(), "$.data[*].type")).containsExactly("TEXT", "LONG_TEXT",
                "NUMBER", "DECIMAL", "CURRENCY", "PERCENT", "BOOLEAN", "DATE", "DATETIME", "TIME", "EMAIL", "PHONE",
                "URL", "PICKLIST", "MULTI_PICKLIST", "LOOKUP", "MASTER_DETAIL", "FORMULA", "AUTO_NUMBER");
        assertThat(JsonPath.<List<String>>read(types.body(), "$.data[?(@.type=='PICKLIST')].settings[*]"))
                .containsExactly("values");
        assertThat(JsonPath.<List<Boolean>>read(types.body(), "$.data[?(@.type=='FORMULA')].calculated"))
                .containsExactly(true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyType")
    void everyFieldTypeCanBeAddedAndComesBackAsItWasConfigured(String type, String extra, String path,
            String expected) {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Holder");
        createObject(admin, "Target");

        Response created = createField(admin, "Holder__c", "{\"name\":\"value\",\"label\":\"Value\",\"type\":\""
                + type + "\"" + extra + "}");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.data.type")).isEqualTo(type);
        assertThat(String.valueOf(JsonPath.<Object>read(created.body(), path))).isEqualTo(expected);
        Response again = admin.get(OBJECTS + "/Holder__c");
        assertThat(JsonPath.<List<String>>read(again.body(), "$.data.fields[?(@.apiName=='value__c')].type"))
                .containsExactly(type);
    }

    static Stream<Arguments> everyType() {
        return Stream.of(
                Arguments.of("TEXT", ",\"settings\":{\"maxLength\":40}", "$.data.settings.maxLength", "40"),
                Arguments.of("LONG_TEXT", "", "$.data.settings.maxLength", "32000"),
                Arguments.of("NUMBER", ",\"settings\":{\"digits\":6},\"defaultValue\":\"42\"",
                        "$.data.defaultValue", "42"),
                Arguments.of("DECIMAL", ",\"settings\":{\"precision\":10,\"scale\":3}", "$.data.settings.scale", "3"),
                Arguments.of("CURRENCY", "", "$.data.settings.scale", "2"),
                Arguments.of("PERCENT", ",\"required\":true", "$.data.required", "true"),
                Arguments.of("BOOLEAN", ",\"defaultValue\":\"true\"", "$.data.defaultValue", "true"),
                Arguments.of("DATE", ",\"defaultValue\":\"2030-01-31\"", "$.data.defaultValue", "2030-01-31"),
                Arguments.of("DATETIME", "", "$.data.type", "DATETIME"),
                Arguments.of("TIME", ",\"defaultValue\":\"09:30\"", "$.data.defaultValue", "09:30"),
                Arguments.of("EMAIL", ",\"unique\":true", "$.data.unique", "true"),
                Arguments.of("PHONE", "", "$.data.settings.maxLength", "40"),
                Arguments.of("URL", "", "$.data.settings.maxLength", "2048"),
                Arguments.of("PICKLIST", ",\"defaultValue\":\"Red\",\"settings\":{\"values\":[{\"value\":\"Red\","
                        + "\"label\":\"Red\",\"active\":true},{\"value\":\"Blue\",\"label\":\"Blue\","
                        + "\"active\":false}]}", "$.data.settings.values[1].value", "Blue"),
                Arguments.of("MULTI_PICKLIST", ",\"settings\":{\"values\":[{\"value\":\"A\",\"label\":\"A\","
                        + "\"active\":true}]}", "$.data.settings.values[0].label", "A"),
                Arguments.of("LOOKUP", ",\"settings\":{\"targetObject\":\"Target__c\"}",
                        "$.data.settings.targetObject", "Target__c"),
                Arguments.of("MASTER_DETAIL", ",\"settings\":{\"targetObject\":\"Target__c\"}",
                        "$.data.required", "true"),
                Arguments.of("FORMULA", ",\"settings\":{\"expression\":\"a * 2\",\"resultType\":\"NUMBER\"}",
                        "$.data.settings.resultType", "NUMBER"),
                Arguments.of("AUTO_NUMBER", ",\"settings\":{\"prefix\":\"INV-\",\"startAt\":100,\"width\":5}",
                        "$.data.settings.startAt", "100"));
    }

    @Test
    void aFieldWithSeveralProblemsListsThemAllUnderTheNamesOfTheirSettings() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Holder");

        Response refused = createField(admin, "Holder__c", "{\"name\":\"Bad Name\",\"label\":\"x\","
                + "\"type\":\"BOOLEAN\",\"required\":true,\"defaultValue\":\"maybe\","
                + "\"settings\":{\"maxLength\":5}}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<java.util.Map<String, Object>>read(refused.body(), "$.error.fields").keySet())
                .contains("name", "required", "defaultValue", "settings.maxLength");
    }

    @Test
    void anUnknownTypeIsRefusedAndTheTypeOfAFieldNeverChanges() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Holder");

        Response unknown = createField(admin, "Holder__c", "{\"name\":\"x\",\"label\":\"X\",\"type\":\"WIZARD\"}");
        createField(admin, "Holder__c", textField("code", "Code"));
        Response changed = admin.request("PUT", OBJECTS + "/Holder__c/fields/code__c",
                "{\"label\":\"Code\",\"description\":\"\",\"required\":false,\"unique\":false,"
                        + "\"settings\":{\"digits\":5},\"version\":0}");

        assertThat(unknown.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(unknown.body(), "$.error.fields.type[0]")).contains("field types");
        assertThat(changed.status()).as("a setting of another type").isEqualTo(400);
        assertThat(fieldNames(admin, "Holder__c")).contains("code__c");
    }

    @Test
    void aFieldNameInUseOnTheObjectIsRefusedWhateverTheCase() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Holder");
        assertThat(createField(admin, "Holder__c", textField("code", "Code")).status()).isEqualTo(201);

        Response same = createField(admin, "Holder__c", textField("code", "Other"));
        Response lookalike = createField(admin, "Holder__c", textField("cODE", "Other"));

        assertThat(same.status()).isEqualTo(400);
        assertThat(lookalike.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(same.body(), "$.error.fields.name[0]")).contains("exists already");
    }

    @Test
    void aFieldCanBeChangedWithItsVersionAndKeepsItsTypeAndName() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Holder");
        createField(admin, "Holder__c", textField("code", "Code"));
        String body = "{\"label\":\"Product code\",\"description\":\"The code\",\"required\":true,\"unique\":true,"
                + "\"settings\":{\"maxLength\":20},\"version\":0}";

        Response changed = admin.request("PUT", OBJECTS + "/Holder__c/fields/code__c", body);
        Response stale = admin.request("PUT", OBJECTS + "/Holder__c/fields/code__c", body);

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(changed.body(), "$.data.label")).isEqualTo("Product code");
        assertThat(JsonPath.<Boolean>read(changed.body(), "$.data.unique")).isTrue();
        assertThat(JsonPath.<Integer>read(changed.body(), "$.data.settings.maxLength")).isEqualTo(20);
        assertThat(JsonPath.<String>read(changed.body(), "$.data.type")).isEqualTo("TEXT");
        assertThat(JsonPath.<Integer>read(changed.body(), "$.data.version")).isEqualTo(1);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(stale.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void aPicklistValueCanBeSwitchedOffAndAddedButNotRemoved() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Holder");
        createField(admin, "Holder__c", "{\"name\":\"colour\",\"label\":\"Colour\",\"type\":\"PICKLIST\","
                + "\"settings\":{\"values\":[{\"value\":\"Red\",\"label\":\"Red\",\"active\":true},"
                + "{\"value\":\"Blue\",\"label\":\"Blue\",\"active\":true}]}}");
        String head = "{\"label\":\"Colour\",\"description\":\"\",\"required\":false,\"unique\":false,\"settings\":"
                + "{\"values\":[";
        String tail = "]},\"version\":";

        Response removed = admin.request("PUT", OBJECTS + "/Holder__c/fields/colour__c", head
                + "{\"value\":\"Red\",\"label\":\"Red\",\"active\":true}" + tail + "0}");
        Response switchedOff = admin.request("PUT", OBJECTS + "/Holder__c/fields/colour__c", head
                + "{\"value\":\"Blue\",\"label\":\"Blue\",\"active\":true},{\"value\":\"Red\",\"label\":\"Red\","
                + "\"active\":false},{\"value\":\"Green\",\"label\":\"Green\",\"active\":true}" + tail + "0}");

        assertThat(removed.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(removed.body(), "$.error.fields['settings.values'][0]"))
                .contains("switch it off");
        assertThat(switchedOff.status()).as(switchedOff.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(switchedOff.body(), "$.data.settings.values[*].value"))
                .containsExactly("Blue", "Red", "Green");
        assertThat(JsonPath.<List<Boolean>>read(switchedOff.body(), "$.data.settings.values[*].active"))
                .containsExactly(true, false, true);
    }

    // ---- removing ----

    @Test
    void removingAFieldEndsThePermissionsOnItAndNamesStayCleanWhenReused() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        createObject(admin, "Holder");
        createField(admin, "Holder__c", textField("code", "Code"));
        UUID profile = idOf(admin.postJson(PROFILES, "{\"name\":\"p1\",\"description\":\"\",\"licenceType\":\"user\","
                + "\"abilities\":[]}"));
        Response saved = admin.request("PUT", PROFILES + "/" + profile + "/data-access",
                "{\"objects\":[{\"key\":\"Holder__c\",\"actions\":[\"read\"]}],\"fields\":"
                        + "[{\"key\":\"Holder__c.code__c\",\"actions\":[\"edit\"]}]}");
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);

        assertThat(admin.request("DELETE", OBJECTS + "/Holder__c/fields/code__c", null).status()).isEqualTo(204);

        Response matrix = admin.get(PROFILES + "/" + profile + "/data-access");
        assertThat(JsonPath.<List<String>>read(matrix.body(), "$.data.fields[*].key")).isEmpty();
        assertThat(JsonPath.<List<String>>read(matrix.body(), "$.data.objects[*].key")).containsExactly("Holder__c");
        createField(admin, "Holder__c", textField("code", "Code again"));
        Response again = admin.get(PROFILES + "/" + profile + "/data-access");
        assertThat(JsonPath.<List<String>>read(again.body(), "$.data.fields[*].key")).as("a reused name starts clean")
                .isEmpty();
    }

    @Test
    void removingAnObjectRemovesItsFieldsAndEndsEveryPermissionAndAReusedNameStartsClean() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        createObject(admin, "Holder");
        createField(admin, "Holder__c", textField("code", "Code"));
        UUID profile = idOf(admin.postJson(PROFILES, "{\"name\":\"p1\",\"description\":\"\",\"licenceType\":\"user\","
                + "\"abilities\":[]}"));
        admin.request("PUT", PROFILES + "/" + profile + "/data-access",
                "{\"objects\":[{\"key\":\"Holder__c\",\"actions\":[\"read\"]}],\"fields\":"
                        + "[{\"key\":\"Holder__c.code__c\",\"actions\":[\"read\"]}]}");
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        admin.request("PUT", MEMBERS + "/" + person.membership() + "/profile", "{\"profileId\":\"" + profile + "\"}");
        assertThat(can(organization, person.membership(), "Holder__c", ObjectAction.READ)).isTrue();

        Response removed = admin.request("DELETE", OBJECTS + "/Holder__c", null);

        assertThat(removed.status()).isEqualTo(204);
        assertThat(admin.get(OBJECTS + "/Holder__c").status()).isEqualTo(404);
        assertThat(objectNames(admin)).doesNotContain("Holder__c");
        assertThat(can(organization, person.membership(), "Holder__c", ObjectAction.READ)).as("gone at once")
                .isFalse();
        createObject(admin, "Holder");
        assertThat(can(organization, person.membership(), "Holder__c", ObjectAction.READ))
                .as("an object made again under the same name does not wake the old permissions").isFalse();
        Response matrix = admin.get(PROFILES + "/" + profile + "/data-access");
        assertThat(JsonPath.<List<String>>read(matrix.body(), "$.data.objects[*].key")).isEmpty();
    }

    @Test
    void anObjectThatAnotherFieldPointsToCannotBeRemovedUntilThatFieldIsGone() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Department");
        createObject(admin, "Employee");
        createField(admin, "Employee__c", "{\"name\":\"department\",\"label\":\"Department\",\"type\":\"LOOKUP\","
                + "\"settings\":{\"targetObject\":\"Department__c\"}}");
        createField(admin, "Account", "{\"name\":\"dept\",\"label\":\"Dept\",\"type\":\"LOOKUP\","
                + "\"settings\":{\"targetObject\":\"Department__c\"}}");

        Response refused = admin.request("DELETE", OBJECTS + "/Department__c", null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("Employee__c.department__c")
                .contains("Account.dept__c");
        admin.request("DELETE", OBJECTS + "/Employee__c/fields/department__c", null);
        admin.request("DELETE", OBJECTS + "/Account/fields/dept__c", null);
        assertThat(admin.request("DELETE", OBJECTS + "/Department__c", null).status()).isEqualTo(204);
    }

    @Test
    void anObjectThatPointsToItselfCanBeRemoved() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Person");
        createField(admin, "Person__c", "{\"name\":\"manager\",\"label\":\"Manager\",\"type\":\"LOOKUP\","
                + "\"settings\":{\"targetObject\":\"Person__c\"}}");

        assertThat(admin.request("DELETE", OBJECTS + "/Person__c", null).status()).isEqualTo(204);
    }

    // ---- the permissions catalogue follows ----

    @Test
    void aNewObjectCanBeNamedInAPermissionMatrixAtOnceAndAnUnknownOneCannot() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        UUID profile = idOf(admin.postJson(PROFILES, "{\"name\":\"p1\",\"description\":\"\",\"licenceType\":\"user\","
                + "\"abilities\":[]}"));
        String matrix = "{\"objects\":[{\"key\":\"Employee__c\",\"actions\":[\"read\"]}],\"fields\":[]}";

        Response before = admin.request("PUT", PROFILES + "/" + profile + "/data-access", matrix);
        createObject(admin, "Employee");
        Response after = admin.request("PUT", PROFILES + "/" + profile + "/data-access", matrix);
        Response catalogue = admin.get(CATALOGUE);

        assertThat(before.status()).as("the object does not exist yet").isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.objects[*].key")).contains("Employee__c");
        assertThat(after.status()).as(after.body()).isEqualTo(200);
    }

    // ---- authorization ----

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void aMemberWithoutTheAbilityIsRefusedOnEveryEndpoint(String method, String path, String body) {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        createObject(admin, "Gadget");
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = as(organization, person);

        Response refused = asPerson.request(method, path, body);

        assertThat(refused.status()).as(refused.body()).isEqualTo(403);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("FORBIDDEN");
    }

    static Stream<Arguments> everyEndpoint() {
        String object = "{\"name\":\"Thing\",\"label\":\"T\",\"pluralLabel\":\"Ts\"}";
        String updateObject = "{\"label\":\"T\",\"pluralLabel\":\"Ts\",\"description\":\"\",\"version\":0}";
        String field = "{\"name\":\"code\",\"label\":\"Code\",\"type\":\"TEXT\"}";
        String updateField = "{\"label\":\"C\",\"description\":\"\",\"required\":false,\"unique\":false,"
                + "\"settings\":{},\"version\":0}";
        return Stream.of(
                Arguments.of("GET", TYPES, null),
                Arguments.of("GET", OBJECTS, null),
                Arguments.of("GET", OBJECTS + "/Gadget__c", null),
                Arguments.of("POST", OBJECTS, object),
                Arguments.of("PUT", OBJECTS + "/Gadget__c", updateObject),
                Arguments.of("DELETE", OBJECTS + "/Gadget__c", null),
                Arguments.of("POST", OBJECTS + "/Gadget__c/fields", field),
                Arguments.of("PUT", OBJECTS + "/Gadget__c/fields/code__c", updateField),
                Arguments.of("DELETE", OBJECTS + "/Gadget__c/fields/code__c", null));
    }

    @Test
    void viewingAndManagingAreSeparateAbilities() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        createObject(admin, "Gadget");
        Member viewer = memberWith(organization, admin, "metadata.view");
        Member drafter = memberWith(organization, admin, "metadata.view", "metadata.manage");
        Member manager = memberWith(organization, admin, "metadata.view", "metadata.manage", "metadata.publish");
        TestBrowser asViewer = as(organization, viewer);
        TestBrowser asDrafter = as(organization, drafter);
        TestBrowser asManager = as(organization, manager);

        assertThat(asViewer.get(OBJECTS).status()).as("a viewer reads").isEqualTo(200);
        assertThat(asViewer.get(OBJECTS + "/Gadget__c").status()).isEqualTo(200);
        assertThat(asViewer.get(TYPES).status()).isEqualTo(200);
        Response refused = createObject(asViewer, "Thing");
        assertThat(refused.status()).as("a viewer does not change").isEqualTo(403);
        assertThat(asViewer.request("DELETE", OBJECTS + "/Gadget__c", null).status()).isEqualTo(403);
        assertThat(createObject(asDrafter, "Thing").status())
                .as("a change made at once is a publication: managing alone is not enough (ADR-0068)")
                .isEqualTo(403);
        assertThat(createObject(asManager, "Thing").status()).as("a manager who may publish changes").isEqualTo(201);
        assertThat(asManager.request("DELETE", OBJECTS + "/Thing__c", null).status()).isEqualTo(204);
    }

    @Test
    void aRefusalForMissingTheAbilityIsRecorded() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        TestBrowser asPerson = as(organization, TestOrganizations.join(users, organization.tenant(), false));

        assertThat(createObject(asPerson, "Thing").status()).isEqualTo(403);

        Response events = admin.get(EVENTS + "?kind=membership.action.refused");
        assertThat(events.body()).contains("metadata.object.create");
    }

    @Test
    void aPlatformAdministratorIsRefusedOnEveryOrganizationEndpoint() {
        organization();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (String path : List.of(TYPES, OBJECTS, OBJECTS + "/Account")) {
            assertThat(platformAdmin.get(path).status()).as(path).isEqualTo(404);
        }
        assertThat(platformAdmin.postJson(OBJECTS, objectBody("Thing", "Thing")).status()).isEqualTo(404);
        assertThat(platformAdmin.request("DELETE", OBJECTS + "/Account", null).status()).isEqualTo(404);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        createObject(adminOf(theirs), "Secret");
        createObject(admin, "Mine");
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response plain = admin.get(OBJECTS);
        Response withHeaders = admin.get(OBJECTS, forged);
        Response create = admin.request("POST", OBJECTS, objectBody("Other", "Other"), forged);

        assertThat(withHeaders.status()).isEqualTo(plain.status());
        assertThat(withHeaders.body()).isEqualTo(plain.body()).doesNotContain("Secret__c");
        assertThat(create.status()).as(create.body()).isEqualTo(201);
        assertThat(objectNames(adminOf(theirs))).doesNotContain("Other__c");
        assertThat(objectNames(admin)).contains("Other__c");
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        Organization organization = organization();

        Response refused = new TestBrowser(port, organization.host()).get(OBJECTS);

        assertThat(refused.status()).isEqualTo(401);
    }

    // ---- audit ----

    @Test
    void everyChangeLeavesAnEventTheViewerShowsWithNoTypedText() {
        TestBrowser admin = adminOf(organization());
        String typed = "Secret quarterly figures";
        admin.postJson(OBJECTS, "{\"name\":\"Report\",\"label\":\"" + typed + "\",\"pluralLabel\":\"" + typed
                + "s\",\"description\":\"" + typed + "\"}");
        admin.request("PUT", OBJECTS + "/Report__c", "{\"label\":\"Changed " + typed + "\",\"pluralLabel\":\"Rs\","
                + "\"description\":\"\",\"version\":0}");
        createField(admin, "Report__c", "{\"name\":\"total\",\"label\":\"" + typed + "\",\"type\":\"CURRENCY\"}");
        admin.request("PUT", OBJECTS + "/Report__c/fields/total__c", "{\"label\":\"Total\",\"description\":\"" + typed
                + "\",\"required\":true,\"unique\":false,\"settings\":{},\"version\":0}");
        admin.request("DELETE", OBJECTS + "/Report__c/fields/total__c", null);
        admin.request("DELETE", OBJECTS + "/Report__c", null);

        Response events = admin.get(EVENTS + "?limit=100");

        assertThat(events.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(events.body(), "$.data[*].type")).contains("metadata.object.created",
                "metadata.object.updated", "metadata.field.created", "metadata.field.updated",
                "metadata.field.deleted", "metadata.object.deleted");
        assertThat(JsonPath.<List<String>>read(events.body(),
                "$.data[?(@.type=='metadata.field.created')].objectKey")).containsExactly("Report__c.total__c");
        assertThat(JsonPath.<List<String>>read(events.body(),
                "$.data[?(@.type=='metadata.object.updated')].attributes.changed"))
                .containsExactly("label,pluralLabel,description");
        assertThat(events.body()).as("what a person typed is never in the trail").doesNotContain("Secret quarterly");
    }
}
