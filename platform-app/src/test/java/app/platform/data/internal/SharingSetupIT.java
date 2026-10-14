package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The setup of record-level security (ADR-0116): the default of an object and the sharing rules. Every endpoint is
 * tried as an allowed administrator, as a member without the ability, as another organization, with a forged tenant
 * header, as a platform administrator and without signing in; and the rules of the setup are held to what they say.
 */
@PlatformIntegrationTest
class SharingSetupIT extends RecordSecuritySupport {

    private static final String FAKE = "018f0000-0000-7000-8000-000000000000";

    private record Call(String method, String path, String body) {
    }

    private static final List<Call> EVERY_ENDPOINT = List.of(
            new Call("GET", SHARING + "/objects", null),
            new Call("GET", SHARING + "/objects/Item__c", null),
            new Call("PUT", SHARING + "/objects/Item__c", "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":true}"),
            new Call("GET", SHARING + "/rules", null),
            new Call("POST", SHARING + "/rules", "{\"object\":\"Item__c\",\"name\":\"x\",\"kind\":\"OWNER\","
                    + "\"sourceType\":\"ROLE\",\"sourceId\":\"" + FAKE + "\",\"targetType\":\"GROUP\",\"targetId\":\""
                    + FAKE + "\",\"accessLevel\":\"READ\"}"),
            new Call("GET", SHARING + "/rules/" + FAKE, null),
            new Call("PUT", SHARING + "/rules/" + FAKE, "{\"name\":\"x\",\"targetType\":\"ROLE\",\"targetId\":\""
                    + FAKE + "\",\"accessLevel\":\"READ\",\"version\":0}"),
            new Call("DELETE", SHARING + "/rules/" + FAKE, null));

    // ---- who may ----

    @Test
    void anAdministratorSeesEveryObjectWithItsDefaultAndSetsOne() {
        Items items = items();

        Response all = items.admin().get(SHARING + "/objects");
        Response set = items.admin().request("PUT", SHARING + "/objects/Item__c",
                "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":false}");

        assertThat(all.status()).as(all.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(all.body(), "$.data[*].object")).contains("Item__c", "Target__c");
        assertThat(JsonPath.<String>read(items.admin().get(SHARING + "/objects/Target__c").body(),
                "$.data.defaultAccess")).isEqualTo("PUBLIC_READ_WRITE");
        assertThat(JsonPath.<Boolean>read(items.admin().get(SHARING + "/objects/Target__c").body(),
                "$.data.configured")).isFalse();
        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(set.body(), "$.data.defaultAccess")).isEqualTo("PRIVATE");
        assertThat(JsonPath.<Boolean>read(set.body(), "$.data.grantViaHierarchy")).isFalse();
    }

    @Test
    void aMemberWithoutTheAbilityIsForbiddenEverything() {
        Items items = items();
        Person member = worker(items.organization(), items.admin());

        for (Call call : EVERY_ENDPOINT) {
            Response refused = member.browser().request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(403);
        }
        assertThat(JsonPath.<Boolean>read(items.admin().get(SHARING + "/objects/Item__c").body(),
                "$.data.configured")).as("and nothing was done").isFalse();
    }

    @Test
    void anotherOrganizationNeverSeesOrChangesMyRulesOrMyDefaults() {
        Items mine = items();
        Items theirs = items();
        String role = role(mine.admin(), "Boss", null);
        String group = group(mine.admin(), "readers");
        String rule = rule(mine.admin(), "{\"object\":\"Item__c\",\"name\":\"mine\",\"kind\":\"OWNER\","
                + "\"sourceType\":\"ROLE\",\"sourceId\":\"" + role + "\",\"targetType\":\"GROUP\",\"targetId\":\""
                + group + "\",\"accessLevel\":\"READ\"}");
        share(mine.admin(), "PRIVATE", true);

        assertThat(theirs.admin().get(SHARING + "/rules/" + rule).status()).isEqualTo(404);
        assertThat(theirs.admin().request("DELETE", SHARING + "/rules/" + rule, null).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(theirs.admin().get(SHARING + "/rules").body(), "$.data[*].id"))
                .isEmpty();
        assertThat(JsonPath.<String>read(theirs.admin().get(SHARING + "/objects/Item__c").body(),
                "$.data.defaultAccess")).isEqualTo("PUBLIC_READ_WRITE");
        assertThat(mine.admin().get(SHARING + "/rules/" + rule).status()).isEqualTo(200);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Items mine = items();
        Items theirs = items();
        share(theirs.admin(), "PRIVATE", true);
        String[] forged = {"X-Tenant-Id", theirs.organization().id().value().toString(), "X-Forwarded-Host",
            theirs.organization().host()};

        Response read = mine.admin().get(SHARING + "/objects/Item__c", forged);

        assertThat(JsonPath.<String>read(read.body(), "$.data.defaultAccess")).isEqualTo("PUBLIC_READ_WRITE");
    }

    @Test
    void aPlatformAdministratorIsRefusedEverywhere() {
        Items items = items();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : EVERY_ENDPOINT) {
            assertThat(platformAdmin.request(call.method(), call.path(), call.body()).status())
                    .as(call.toString()).isIn(403, 404);
        }
        assertThat(items.admin().get(SHARING + "/objects").status()).isEqualTo(200);
    }

    @Test
    void anUnauthenticatedCallerIsRefusedEverywhere() {
        Organization organization = items().organization();
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : EVERY_ENDPOINT) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
    }

    @Test
    void aWriteWithoutTheRequestForgeryHeaderIsRefused() {
        Items items = items();

        Response refused = items.admin().postJsonWithoutCsrfHeader(SHARING + "/rules", "{}");

        assertThat(refused.status()).isEqualTo(403);
    }

    // ---- the rules of the setup ----

    @Test
    void aDefaultThatIsNotOneOfTheThreeIsRefusedAndAnUnknownObjectDoesNotExist() {
        Items items = items();

        Response bad = items.admin().request("PUT", SHARING + "/objects/Item__c",
                "{\"defaultAccess\":\"EVERYONE\",\"grantViaHierarchy\":true}");
        Response unknown = items.admin().request("PUT", SHARING + "/objects/Nothing__c",
                "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":true}");

        assertThat(bad.status()).isEqualTo(400);
        assertThat(unknown.status()).isEqualTo(404);
    }

    @Test
    void changingAConfiguredDefaultNeedsTheVersionThatWasRead() {
        Items items = items();
        share(items.admin(), "PRIVATE", true);

        Response without = items.admin().request("PUT", SHARING + "/objects/Item__c",
                "{\"defaultAccess\":\"PUBLIC_READ\",\"grantViaHierarchy\":true}");
        Response stale = items.admin().request("PUT", SHARING + "/objects/Item__c",
                "{\"defaultAccess\":\"PUBLIC_READ\",\"grantViaHierarchy\":true,\"version\":99}");

        assertThat(without.status()).isEqualTo(400);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(items.admin().get(SHARING + "/objects/Item__c").body(),
                "$.data.defaultAccess")).isEqualTo("PRIVATE");
    }

    @Test
    void aRuleWithSeveralProblemsListsThemAllWithTheirPlaces() {
        Items items = items();

        Response refused = items.admin().postJson(SHARING + "/rules", "{\"object\":\"Item__c\",\"name\":\"x\","
                + "\"kind\":\"OWNER\",\"sourceType\":\"ROLE\",\"sourceId\":\"" + FAKE + "\",\"targetType\":\"GROUP\","
                + "\"targetId\":\"" + FAKE + "\",\"accessLevel\":\"DELETE\"}");

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.sourceId")).isNotEmpty();
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.targetId")).isNotEmpty();
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.accessLevel")).isNotEmpty();
    }

    @Test
    void aConditionOnAFieldThatIsNotIndexedIsRefusedWithTheFieldToFlag() {
        Items items = items();
        String group = group(items.admin(), "readers");

        Response refused = items.admin().postJson(SHARING + "/rules", "{\"object\":\"Item__c\",\"name\":\"by name\","
                + "\"kind\":\"CRITERIA\",\"criterion\":{\"op\":\"EQ\",\"field\":\"name__c\",\"values\":[\"x\"]},"
                + "\"targetType\":\"GROUP\",\"targetId\":\"" + group + "\",\"accessLevel\":\"READ\"}");

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("indexed").doesNotContain("\"x\"");
    }

    @Test
    void aRuleByConditionRefusesAnUnknownFieldAndATextOfAnotherKind() {
        Items items = items();
        String group = group(items.admin(), "readers");

        Response unknown = items.admin().postJson(SHARING + "/rules", "{\"object\":\"Item__c\",\"name\":\"ghost\","
                + "\"kind\":\"CRITERIA\",\"criterion\":{\"op\":\"EQ\",\"field\":\"ghost__c\",\"values\":[\"x\"]},"
                + "\"targetType\":\"GROUP\",\"targetId\":\"" + group + "\",\"accessLevel\":\"READ\"}");
        Response ownerWithCondition = items.admin().postJson(SHARING + "/rules", "{\"object\":\"Item__c\","
                + "\"name\":\"mixed\",\"kind\":\"OWNER\",\"sourceType\":\"ROLE\",\"sourceId\":\"" + FAKE
                + "\",\"criterion\":{\"op\":\"EQ\",\"field\":\"name__c\",\"values\":[\"x\"]},\"targetType\":\"GROUP\","
                + "\"targetId\":\"" + group + "\",\"accessLevel\":\"READ\"}");

        assertThat(unknown.status()).as(unknown.body()).isEqualTo(400);
        assertThat(ownerWithCondition.status()).as(ownerWithCondition.body()).isEqualTo(400);
    }

    @Test
    void aRoleOrAGroupThatARuleUsesCannotBeRemovedUntilTheRuleIsGone() {
        Items items = items();
        String role = role(items.admin(), "Boss", null);
        String group = group(items.admin(), "readers");
        String rule = rule(items.admin(), "{\"object\":\"Item__c\",\"name\":\"keeps them\",\"kind\":\"OWNER\","
                + "\"sourceType\":\"ROLE\",\"sourceId\":\"" + role + "\",\"targetType\":\"GROUP\",\"targetId\":\""
                + group + "\",\"accessLevel\":\"READ\"}");

        assertThat(items.admin().request("DELETE", ROLES + "/" + role, null).status()).isEqualTo(409);
        assertThat(items.admin().request("DELETE", GROUPS + "/" + group, null).status()).isEqualTo(409);
        assertThat(items.admin().request("DELETE", SHARING + "/rules/" + rule, null).status()).isEqualTo(204);
        assertThat(items.admin().request("DELETE", ROLES + "/" + role, null).status()).isEqualTo(204);
        assertThat(items.admin().request("DELETE", GROUPS + "/" + group, null).status()).isEqualTo(204);
    }

    @Test
    void aRuleNamesAreUniqueAmongTheRulesOfTheObject() {
        Items items = items();
        String role = role(items.admin(), "Boss", null);
        String group = group(items.admin(), "readers");
        String json = "{\"object\":\"Item__c\",\"name\":\"same\",\"kind\":\"OWNER\",\"sourceType\":\"ROLE\","
                + "\"sourceId\":\"" + role + "\",\"targetType\":\"GROUP\",\"targetId\":\"" + group
                + "\",\"accessLevel\":\"READ\"}";
        rule(items.admin(), json);

        Response again = items.admin().postJson(SHARING + "/rules", json);

        assertThat(again.status()).as(again.body()).isEqualTo(400);
    }

    @Test
    void aFieldThatARuleTestsCannotBeRemovedUntilTheRuleChanges() {
        Items items = items();
        assertThat(flag(items.admin(), "stage", true).status()).isEqualTo(200);
        settle(items.organization(), items.admin());
        String group = group(items.admin(), "readers");
        rule(items.admin(), "{\"object\":\"Item__c\",\"name\":\"stage a\",\"kind\":\"CRITERIA\",\"criterion\":"
                + "{\"op\":\"EQ\",\"field\":\"stage__c\",\"values\":[\"a\"]},\"targetType\":\"GROUP\",\"targetId\":\""
                + group + "\",\"accessLevel\":\"READ\"}");
        String set = createSet(items.admin(), "remove stage");
        assertThat(addRemoval(items.admin(), set, "Item__c", "stage__c").status()).isEqualTo(201);

        Response checked = validate(items.admin(), set);

        assertThat(checked.body()).as("the report names the rule").contains("sharing rule stage a");
        assertThat(publish(items.admin(), set).status()).isEqualTo(409);
        assertThat(fieldNames(items.admin(), "Item__c")).contains("stage__c");
    }

    @Test
    void removingAnObjectEndsItsDefaultAndItsRules() {
        Items items = items();
        Response made = items.admin().postJson(METADATA_OBJECTS, "{\"name\":\"Gadget\",\"label\":\"Gadget\","
                + "\"pluralLabel\":\"Gadgets\",\"description\":\"\"}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        Response set = items.admin().request("PUT", SHARING + "/objects/Gadget__c",
                "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":true}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);

        String changeSet = createSet(items.admin(), "remove gadget");
        assertThat(addRemoval(items.admin(), changeSet, "Gadget__c", null).status()).isEqualTo(201);
        assertThat(publish(items.admin(), changeSet).status()).isIn(200, 201);

        assertThat(items.admin().get(SHARING + "/objects/Gadget__c").status()).isEqualTo(404);
    }

    @Test
    void aRuleCannotBeChangedWithAStaleVersionAndItsObjectAndKindNeverChange() {
        Items items = items();
        String role = role(items.admin(), "Boss", null);
        String group = group(items.admin(), "readers");
        String rule = rule(items.admin(), "{\"object\":\"Item__c\",\"name\":\"r\",\"kind\":\"OWNER\","
                + "\"sourceType\":\"ROLE\",\"sourceId\":\"" + role + "\",\"targetType\":\"GROUP\",\"targetId\":\""
                + group + "\",\"accessLevel\":\"READ\"}");

        Response stale = items.admin().request("PUT", SHARING + "/rules/" + rule, "{\"name\":\"r\",\"sourceType\":"
                + "\"ROLE\",\"sourceId\":\"" + role + "\",\"targetType\":\"GROUP\",\"targetId\":\"" + group
                + "\",\"accessLevel\":\"READ_WRITE\",\"version\":5}");
        Response changed = items.admin().request("PUT", SHARING + "/rules/" + rule, "{\"object\":\"Target__c\","
                + "\"kind\":\"GROUP\",\"name\":\"r2\",\"sourceType\":\"ROLE\",\"sourceId\":\"" + role
                + "\",\"targetType\":\"GROUP\",\"targetId\":\"" + group
                + "\",\"accessLevel\":\"READ_WRITE\",\"version\":0}");

        assertThat(stale.status()).isEqualTo(409);
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(changed.body(), "$.data.object")).isEqualTo("Item__c");
        assertThat(JsonPath.<String>read(changed.body(), "$.data.kind")).isEqualTo("OWNER");
        assertThat(JsonPath.<String>read(changed.body(), "$.data.name")).isEqualTo("r2");
    }
}
