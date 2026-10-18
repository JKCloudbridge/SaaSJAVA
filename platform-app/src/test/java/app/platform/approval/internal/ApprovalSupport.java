package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What the Sprint 24 integration tests of approvals share (ADR-0145 to ADR-0153): an organization whose plan has the
 * features {@code approvals} and {@code workflows}, an administrator (who holds every ability, so also the override of
 * a lock), people who may change records of the sample object {@code ObjectA__c} but hold no approval ability (the
 * submitters and the approvers: an approver acts because of the task they hold), short ways to build a process and to
 * submit, decide and read through the real API, and ways to look at the rows of an organization. Generic data only.
 *
 * <p>The tests that use it switch the outbox relay on (a decision announces its end through it) and the dispatcher of
 * workflows off, so what a workflow does can be driven by hand where a test needs it.
 */
abstract class ApprovalSupport {

    protected static final String METADATA_OBJECTS = "/api/v1/metadata/objects";
    protected static final String DEFINITIONS = "/api/v1/metadata/approvals";
    protected static final String RUNTIME = "/api/v1/approvals";
    protected static final String INSTANCES = RUNTIME + "/instances";
    protected static final String DELEGATIONS = RUNTIME + "/delegations";
    protected static final String PROFILES = "/api/v1/profiles";
    protected static final String MEMBERS = "/api/v1/members";
    protected static final String OBJECT = "ObjectA__c";
    protected static final String RECORDS = "/api/v1/objects/" + OBJECT + "/records";

    /** What an approver and a submitter may do with the sample object: everything, on every field. */
    private static String dataAccess(boolean withQuantity, String extraObjects, String extraFields) {
        return "{\"objects\":[{\"key\":\"ObjectA__c\",\"actions\":[\"read\",\"create\",\"update\",\"delete\"]}"
                + (extraObjects == null ? "" : "," + extraObjects) + "],"
                + "\"fields\":[{\"key\":\"ObjectA__c.fieldA__c\",\"actions\":[\"edit\"]},"
                + "{\"key\":\"ObjectA__c.fieldB__c\",\"actions\":[\"edit\"]},"
                + "{\"key\":\"ObjectA__c.fieldC__c\",\"actions\":[\"edit\"]}"
                + (withQuantity ? ",{\"key\":\"ObjectA__c.quantity__c\",\"actions\":[\"edit\"]}" : "")
                + (extraFields == null ? "" : "," + extraFields) + "]}";
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected Users users;

    @Autowired
    protected Plans plans;

    @Autowired
    protected Subscriptions subscriptions;

    /** A person of an organization: who they are and their session. */
    protected record Person(Member member, TestBrowser browser) {

        String userId() {
            return member.person().user().id().toString();
        }
    }

    // ---- organizations and people ----

    /** An organization whose plan has approvals and workflows, with enough licences for a few approvers. */
    protected Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 30, 3, "approvals", "workflows");
        return organization;
    }

    /** An organization whose plan has nothing but the licences. */
    protected Organization organizationWithoutTheFeature() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 30, 3);
        return organization;
    }

    /** Moves the organization to a plan that has no approvals, as a lapsed subscription would. */
    protected void loseTheFeature(Organization organization) {
        String key = TestPlatform.plan(plans, 30, Math.max(3, TestPlatform.adminLicencesInUse(organization.id())));
        subscriptions.change(organization.id(), new app.platform.licensing.SubscriptionChange(key, null, null, null),
                app.platform.sharedkernel.ActorId.SYSTEM);
    }

    protected TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    protected Person adminPerson(Organization organization) {
        return new Person(organization.admin(), adminOf(organization));
    }

    /** The administrator, with a number field added to the sample object (for entry criteria and outcome tests). */
    protected TestBrowser adminWithData(Organization organization) {
        TestBrowser admin = adminOf(organization);
        Response made = admin.postJson(METADATA_OBJECTS + "/" + OBJECT + "/fields",
                "{\"name\":\"quantity\",\"label\":\"quantity\",\"type\":\"NUMBER\"}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return admin;
    }

    /**
     * A licensed member who may read and change records of the sample object and holds exactly these abilities (none
     * of the approval abilities unless a test names them).
     */
    protected Person person(Organization organization, TestBrowser admin, String... abilities) {
        return personAlso(organization, admin, null, null, abilities);
    }

    /** The same, with more objects and fields in the profile's data access (JSON array items, without brackets). */
    protected Person personAlso(Organization organization, TestBrowser admin, String extraObjects, String extraFields,
            String... abilities) {
        Member joined = TestOrganizations.join(users, organization.tenant(), false);
        List<String> quoted = Arrays.stream(abilities).map(ability -> "\"" + ability + "\"").toList();
        Response profile = admin.postJson(PROFILES, "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[" + String.join(",", quoted) + "]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        String profileId = JsonPath.read(profile.body(), "$.data.id");
        Response set = admin.request("PUT", MEMBERS + "/" + joined.membership() + "/profile",
                "{\"profileId\":\"" + profileId + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        boolean withQuantity = JsonPath.<List<String>>read(admin.get(METADATA_OBJECTS + "/" + OBJECT).body(),
                "$.data.fields[*].apiName").contains("quantity__c");
        Response matrix = admin.request("PUT", PROFILES + "/" + profileId + "/data-access",
                dataAccess(withQuantity, extraObjects, extraFields));
        assertThat(matrix.status()).as(matrix.body()).isEqualTo(200);
        return new Person(joined, TestOrganizations.signedIn(port, organization.host(), joined.person()));
    }

    // ---- definitions ----

    protected static String user(String userId) {
        return "{\"kind\":\"USER\",\"value\":\"" + userId + "\"}";
    }

    protected static String group(String groupId) {
        return "{\"kind\":\"GROUP\",\"value\":\"" + groupId + "\"}";
    }

    protected static String profile(String profileId) {
        return "{\"kind\":\"PROFILE\",\"value\":\"" + profileId + "\"}";
    }

    /** An approver rule that takes no value: OWNER or OWNER_ROLE_MANAGER. */
    protected static String bare(String kind) {
        return "{\"kind\":\"" + kind + "\"}";
    }

    protected static String recordUser(String field) {
        return "{\"kind\":\"RECORD_USER\",\"value\":\"" + field + "\"}";
    }

    protected static String step(String name, String completion, String... approvers) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"completion\":\"" + completion
                + "\",\"approvers\":[" + String.join(",", approvers) + "]}";
    }

    protected static String outcomes(String approved, String rejected, String recalled) {
        return "{\"approved\":[" + approved + "],\"rejected\":[" + rejected + "],\"recalled\":[" + recalled + "]}";
    }

    protected static String assignment(String field, String value) {
        return "{\"field\":\"" + field + "\",\"value\":\"" + value + "\"}";
    }

    protected static String createRequest(String name, String criteria, boolean allowSelf, String fallback,
            String outcomes, String... steps) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\",\"active\":true,"
                + "\"entryCriteria\":" + (criteria == null ? "null" : "\"" + criteria + "\"")
                + ",\"allowSelfApproval\":" + allowSelf + ",\"steps\":[" + String.join(",", steps) + "],"
                + "\"outcomes\":" + (outcomes == null ? "null" : outcomes) + ",\"fallback\":"
                + (fallback == null ? "null" : fallback) + "}";
    }

    protected static String updateRequest(String label, boolean active, String criteria, boolean allowSelf,
            String fallback, String outcomes, long version, String... steps) {
        return "{\"label\":\"" + label + "\",\"description\":\"\",\"active\":" + active + ",\"entryCriteria\":"
                + (criteria == null ? "null" : "\"" + criteria + "\"") + ",\"allowSelfApproval\":" + allowSelf
                + ",\"steps\":[" + String.join(",", steps) + "],\"outcomes\":" + (outcomes == null ? "null" : outcomes)
                + ",\"fallback\":" + (fallback == null ? "null" : fallback) + ",\"version\":" + version + "}";
    }

    protected static Response createProcess(TestBrowser admin, String name, String criteria, String... steps) {
        return admin.postJson(DEFINITIONS + "/" + OBJECT, createRequest(name, criteria, false, null, null, steps));
    }

    /** Makes a process that must succeed and returns its API name. */
    protected static String processExists(TestBrowser admin, String name, String criteria, String... steps) {
        return processExists(admin, createRequest(name, criteria, false, null, null, steps));
    }

    protected static String processExists(TestBrowser admin, String request) {
        Response created = admin.postJson(DEFINITIONS + "/" + OBJECT, request);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.apiName");
    }

    protected static long processVersion(TestBrowser admin, String process) {
        return JsonPath.<Number>read(admin.get(DEFINITIONS + "/" + OBJECT + "/" + process).body(), "$.data.version")
                .longValue();
    }

    // ---- records ----

    /** Makes a record as this person and returns its identifier. */
    protected static String record(TestBrowser browser, String fieldsJson) {
        Response created = browser.postJson(RECORDS, "{\"fields\":" + fieldsJson + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    protected static long recordVersion(TestBrowser browser, String id) {
        return JsonPath.<Number>read(browser.get(RECORDS + "/" + id).body(), "$.data.version").longValue();
    }

    protected static Response change(TestBrowser browser, String id, String fieldsJson) {
        return browser.request("PATCH", RECORDS + "/" + id,
                "{\"fields\":" + fieldsJson + ",\"version\":" + recordVersion(browser, id) + "}");
    }

    protected static Object fieldOf(TestBrowser browser, String id, String field) {
        return JsonPath.read(browser.get(RECORDS + "/" + id).body(), "$.data.fields." + field);
    }

    // ---- requests ----

    protected static Response submit(TestBrowser browser, String record, String process, String comment) {
        return browser.postJson(RECORDS + "/" + record + "/approval", "{\"process\":"
                + (process == null ? "null" : "\"" + process + "\"") + ",\"comment\":"
                + (comment == null ? "null" : "\"" + comment + "\"") + "}");
    }

    /** Submits a record that must be accepted and returns the identifier of the request. */
    protected static String submitted(TestBrowser browser, String record, String process) {
        Response response = submit(browser, record, process, null);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.data.summary.id");
    }

    protected static Response instance(TestBrowser browser, String request) {
        return browser.get(INSTANCES + "/" + request);
    }

    protected static long instanceVersion(TestBrowser browser, String request) {
        Response response = instance(browser, request);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.<Number>read(response.body(), "$.data.summary.version").longValue();
    }

    protected static String statusOf(TestBrowser browser, String request) {
        return JsonPath.read(instance(browser, request).body(), "$.data.summary.status");
    }

    protected static Response decide(TestBrowser browser, String request, String action, String comment) {
        return decide(browser, request, action, comment, instanceVersion(browser, request));
    }

    protected static Response decide(TestBrowser browser, String request, String action, String comment,
            long version) {
        return browser.postJson(INSTANCES + "/" + request + "/" + action, "{\"version\":" + version + ",\"comment\":"
                + (comment == null ? "null" : "\"" + comment + "\"") + "}");
    }

    protected static Response approve(TestBrowser browser, String request) {
        return decide(browser, request, "approve", null);
    }

    protected static Response reject(TestBrowser browser, String request, String comment) {
        return decide(browser, request, "reject", comment);
    }

    protected static Response reassign(TestBrowser browser, String request, String toUserId) {
        return browser.postJson(INSTANCES + "/" + request + "/reassign", "{\"toUserId\":\"" + toUserId
                + "\",\"version\":" + instanceVersion(browser, request) + ",\"comment\":null}");
    }

    protected static Response assign(TestBrowser browser, String request, String toUserId) {
        return browser.postJson(INSTANCES + "/" + request + "/assign", "{\"toUserId\":\"" + toUserId
                + "\",\"version\":" + instanceVersion(browser, request) + ",\"comment\":null}");
    }

    /** The history of a request, as the kinds in order. */
    protected static List<String> historyOf(TestBrowser browser, String request) {
        return JsonPath.read(instance(browser, request).body(), "$.data.history[*].kind");
    }

    /** The open tasks of the person. */
    protected static List<String> openRequests(TestBrowser browser) {
        Response inbox = browser.get(RUNTIME + "/inbox?tab=TO_APPROVE");
        assertThat(inbox.status()).as(inbox.body()).isEqualTo(200);
        return JsonPath.read(inbox.body(), "$.data[*].instance.id");
    }

    /** The open tasks the person holds because somebody delegated to them. */
    protected static List<String> delegatedRequests(TestBrowser browser) {
        Response inbox = browser.get(RUNTIME + "/inbox?tab=DELEGATED");
        assertThat(inbox.status()).as(inbox.body()).isEqualTo(200);
        return JsonPath.read(inbox.body(), "$.data[*].instance.id");
    }

    /** What a path holds, or null when the answer leaves it out (an empty value is not written). */
    protected static String optional(String body, String path) {
        try {
            return JsonPath.read(body, path);
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }

    protected static String code(Response response) {
        return JsonPath.read(response.body(), "$.error.code");
    }

    protected static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    protected static String inDays(int days) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(days).toString();
    }

    // ---- driving the runtime ----

    /** Waits until the outbox has handed every event of the organization to its handlers. */
    protected static void settle(Organization organization) {
        Awaitility.await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofMillis(50)).until(
                () -> scalar(organization, "select count(*) from outbox_event where status = 'PENDING'") == 0);
    }

    // ---- looking at an organization's rows ----

    protected static long scalar(Organization organization, String sql, Object... parameters) {
        try {
            return TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.count(connection, sql,
                    parameters));
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    protected static void run(Organization organization, String sql, Object... parameters) {
        try {
            TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection, sql,
                    parameters));
        } catch (SQLException e) {
            throw new IllegalStateException("The statement failed: " + e.getSQLState(), e);
        }
    }

    /** The rows of a query of the organization, each as column label to text. */
    protected static List<Map<String, String>> rows(Organization organization, String sql, Object... parameters) {
        try {
            return TenantFixtures.asTenant(organization.id(), connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < parameters.length; i++) {
                        statement.setObject(i + 1, parameters[i]);
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        ResultSetMetaData columns = result.getMetaData();
                        List<Map<String, String>> found = new ArrayList<>();
                        while (result.next()) {
                            Map<String, String> row = new LinkedHashMap<>();
                            for (int c = 1; c <= columns.getColumnCount(); c++) {
                                row.put(columns.getColumnLabel(c), result.getString(c));
                            }
                            found.add(row);
                        }
                        return found;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    /** How many events of this exact type the organization's outbox holds. */
    protected static long signals(Organization organization, String type) {
        return scalar(organization, "select count(*) from outbox_event where event_type = ?", type);
    }

    /** The audit records of a type about an organization (the audit table is platform-level). */
    protected static long audited(Organization organization, String type) {
        return Long.parseLong(owner("select count(*) from audit_record where event_type = ? and context_tenant_id = ?",
                type, organization.id().value()).get(0));
    }

    /** The first column of every row of a query run as the owner of the database (audit and mail tables). */
    protected static List<String> owner(String sql, Object... parameters) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                List<String> found = new ArrayList<>();
                while (result.next()) {
                    found.add(result.getString(1));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    /** The variables of every queued approval mail to the address, each as the text of its JSON. */
    protected static List<String> queuedMails(String address) {
        return owner("select variables::text from mail_queue where email = ? and template = 'APPROVAL_REQUEST' "
                + "order by created_at", address);
    }
}
