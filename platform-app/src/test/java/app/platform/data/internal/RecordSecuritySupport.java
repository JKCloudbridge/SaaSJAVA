package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the tests of record-level security, files, bulk, import and search share (Sprint 17): people of an organization
 * with the permissions on {@code Item__c} they need and a place in the role tree, short ways to set how the object is
 * shared and to read what a person reaches, all through the real API.
 */
abstract class RecordSecuritySupport extends QuerySupport {

    protected static final String SHARING = "/api/v1/sharing";
    protected static final String ROLES = "/api/v1/roles";
    protected static final String GROUPS = "/api/v1/groups";
    protected static final String MEMBERS = "/api/v1/members";

    /** The permission on each field of {@code Item__c}: edit all but the formula, which is read. */
    private static final String ITEM_FIELD_PERMISSIONS = "[" + String.join(",", ITEM_FIELDS.keySet().stream()
            .map(name -> "{\"key\":\"Item__c." + name + "__c\",\"actions\":[\""
                    + ("calc".equals(name) ? "read" : "edit") + "\"]}").toList()) + "]";

    /** A person of the organization: who they are, their session and their user identifier. */
    protected record Person(Member member, TestBrowser browser, String profileId) {

        /** A person whose profile does not matter (the administrator). */
        Person(Member member, TestBrowser browser) {
            this(member, browser, null);
        }

        String userId() {
            return member.person().user().id().toString();
        }
    }

    /** A member with exactly these actions on {@code Item__c} and every field permission, signed in. */
    protected Person person(Organization organization, TestBrowser admin, String... actions) {
        Holder holder = holderWith(organization, admin, "Item__c", ITEM_FIELD_PERMISSIONS, actions);
        return new Person(holder.member(), as(organization, holder.member()), holder.profileId());
    }

    /** A member who may read, create, change and delete records of {@code Item__c}. */
    protected Person worker(Organization organization, TestBrowser admin) {
        return person(organization, admin, "read", "create", "update", "delete");
    }

    // ---- how the object is shared ----

    /** Sets the organization-wide default and the hierarchy switch of {@code Item__c}. */
    protected static void share(TestBrowser admin, String defaultAccess, boolean hierarchy) {
        Response current = admin.get(SHARING + "/objects/Item__c");
        assertThat(current.status()).as(current.body()).isEqualTo(200);
        boolean configured = JsonPath.read(current.body(), "$.data.configured");
        String version = configured ? ",\"version\":" + JsonPath.<Number>read(current.body(), "$.data.version") : "";
        Response set = admin.request("PUT", SHARING + "/objects/Item__c", "{\"defaultAccess\":\"" + defaultAccess
                + "\",\"grantViaHierarchy\":" + hierarchy + version + "}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
    }

    protected static String role(TestBrowser admin, String name, String parent) {
        Response created = admin.postJson(ROLES, "{\"name\":\"" + name + "\",\"description\":\"\""
                + (parent == null ? "" : ",\"parentId\":\"" + parent + "\"") + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    protected static void putInRole(TestBrowser admin, Person person, String role) {
        Response set = admin.request("PUT", MEMBERS + "/" + person.member().membership() + "/role",
                "{\"roleId\":" + (role == null ? "null" : "\"" + role + "\"") + "}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
    }

    protected static String group(TestBrowser admin, String name, Person... people) {
        Response created = admin.postJson(GROUPS, "{\"name\":\"" + name + "\",\"description\":\"\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = JsonPath.read(created.body(), "$.data.id");
        for (Person person : people) {
            Response added = admin.postJson(GROUPS + "/" + id + "/members",
                    "{\"membershipId\":\"" + person.member().membership() + "\"}");
            assertThat(added.status()).as(added.body()).isEqualTo(200);
        }
        return id;
    }

    /** Creates a sharing rule and returns its identifier. */
    protected static String rule(TestBrowser admin, String json) {
        Response created = admin.postJson(SHARING + "/rules", json);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    // ---- records ----

    /** Creates a record of {@code Item__c} named so, as the person; returns its identifier. */
    protected static String newItem(TestBrowser browser, String name) {
        Response created = browser.postJson(ITEM_RECORDS, "{\"fields\":{\"name__c\":\"" + name + "\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    /** The identifiers a list returns, following the cursor to the end. */
    protected static List<String> listed(TestBrowser browser) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            Response page = browser.get(ITEM_RECORDS + "?limit=100" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            ids.addAll(JsonPath.<List<String>>read(page.body(), "$.data[*].id"));
            cursor = JsonPath.<Boolean>read(page.body(), "$.pagination.hasMore")
                    ? JsonPath.<String>read(page.body(), "$.pagination.nextCursor") : null;
        } while (cursor != null);
        return ids;
    }

    /** The identifiers a query with no filter returns, following the cursor to the end. */
    protected static List<String> queried(TestBrowser browser) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            Response page = browser.postJson(ITEM_QUERY, "{\"limit\":100" + (cursor == null ? ""
                    : ",\"cursor\":\"" + cursor + "\"") + "}");
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            ids.addAll(JsonPath.<List<String>>read(page.body(), "$.data[*].record.id"));
            cursor = JsonPath.<Boolean>read(page.body(), "$.pagination.hasMore")
                    ? JsonPath.<String>read(page.body(), "$.pagination.nextCursor") : null;
        } while (cursor != null);
        return ids;
    }

    /** Whether a single read of the record answers 200 (otherwise 404). */
    protected static boolean reads(TestBrowser browser, String id) {
        int status = browser.get(ITEM_RECORDS + "/" + id).status();
        assertThat(status).isIn(200, 404);
        return status == 200;
    }

    /** A fresh identifier that no record has. */
    protected static String missing() {
        return UUID.randomUUID().toString();
    }
}
