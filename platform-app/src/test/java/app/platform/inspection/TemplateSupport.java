package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.solution.SolutionSupport;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the integration tests of the template lifecycle share (Functional track FA4). An organization with the plan
 * features of the solution and of approvals, the shipped policies made, people who hold them, an approval process whose
 * steps name people, a template with a real sheet, and short ways to look at the rows behind. The words of the solution
 * are in these tests because they test the solution that ships; the generic code under test has none of them.
 */
public abstract class TemplateSupport extends SolutionSupport {

    protected static final String TEMPLATES = "/api/v1/templates";
    protected static final String SHEETS = "/api/v1/sheets/" + OBJECT + "/";
    protected static final String DEFINITIONS = "/api/v1/metadata/approvals";
    protected static final String INSTANCES = "/api/v1/approvals/instances";
    protected static final String GROUPS = "/api/v1/groups";
    protected static final String DEPARTMENT_OBJECT = "DepartmentGroup";
    protected static final String DEPARTMENTS = "/api/v1/objects/" + DEPARTMENT_OBJECT + "/records";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An organization with the feature of the solution and approvals, and the administrator signed in. */
    protected record Setting(Organization organization, TestBrowser admin) {
    }

    /** A person of the organization: who they are and their session. */
    protected record Person(Member member, TestBrowser browser) {

        public String userId() {
            return member.person().user().id().toString();
        }

        public String membership() {
            return member.membership().toString();
        }
    }

    protected Setting setting() {
        Organization organization = organization30();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        return new Setting(organization, admin);
    }

    private Organization organization30() {
        Organization organization = app.platform.testsupport.TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 30, 3, FEATURE, "approvals");
        return organization;
    }

    /** A member who holds the shipped policy of the name. */
    protected Person holding(Setting setting, String policyName) {
        Member member = memberHolding(setting.organization(), setting.admin(), policyName);
        return new Person(member, as(setting.organization(), member));
    }

    protected Person author(Setting setting) {
        return holding(setting, "Author");
    }

    /** An approver holds the Approver policy and a licence, because a person who decides must be licensed. */
    protected Person approver(Setting setting) {
        Person person = holding(setting, "Approver");
        app.platform.testsupport.TestMembers.giveLicence(setting.organization().id(), person.member().membership(),
                "user");
        return person;
    }

    protected Person reader(Setting setting) {
        return holding(setting, "Reader");
    }

    /** A member with no policy of the solution at all. */
    protected Person outsider(Setting setting) {
        Member member = plainMember(setting.organization());
        return new Person(member, as(setting.organization(), member));
    }

    // ---- templates and their sheets ----

    /** A template with the category and sub-category, made by the browser; returns its identifier. */
    protected static String template(TestBrowser browser, String category, String subCategory) {
        return template(browser, "{\"name\":\"" + newName() + "\",\"category\":\"" + category + "\""
                + (subCategory == null ? "" : ",\"subCategory\":\"" + subCategory + "\"") + "}");
    }

    protected static String template(TestBrowser browser, String fieldsJson) {
        Response created = browser.postJson(RECORDS, "{\"fields\":" + fieldsJson + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    protected static String versions(String template) {
        return SHEETS + template + "/versions";
    }

    /** Starts the first (blank) version of the sheet and returns its number. */
    protected static int firstVersion(TestBrowser browser, String template) {
        Response started = browser.postJson(versions(template), "{\"dimension\":2}");
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        return JsonPath.<Integer>read(started.body(), "$.data.versionNo");
    }

    /** Starts a new draft from the version in use (or the latest) and returns its number. */
    protected static int newVersion(TestBrowser browser, String template) {
        Response started = browser.postJson(versions(template), "{}");
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        return JsonPath.<Integer>read(started.body(), "$.data.versionNo");
    }

    protected static Response version(TestBrowser browser, String template, int number) {
        return browser.get(versions(template) + "/" + number);
    }

    protected static long rowVersion(Response summaryOrVersion) {
        String path = summaryOrVersion.body().contains("\"grid\"") ? "$.data.version.version" : "$.data.version";
        return JsonPath.<Number>read(summaryOrVersion.body(), path).longValue();
    }

    /** Fills a draft with a header row and a named row, so that it is not empty, and returns the saved summary. */
    protected static Response fill(TestBrowser browser, String template, int number) {
        Response read = version(browser, template, number);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return saveGrid(browser, template, number, rowVersion(read), withContent(gridOf(read), "First"));
    }

    protected static Response saveGrid(TestBrowser browser, String template, int number, long version, String grid) {
        return browser.request("PUT", versions(template) + "/" + number + "/grid?version=" + version, grid);
    }

    protected static String gridOf(Response version) {
        Object grid = JsonPath.read(version.body(), "$.data.grid");
        return JSON.writeValueAsString(grid);
    }

    /** The grid with a header band and one named row, which is what a sheet needs to be more than a blank start. */
    @SuppressWarnings("unchecked") // the grid is read back from our own answer
    protected static String withContent(String grid, String rowLabel) {
        Map<String, Object> document = JSON.readValue(grid, new TypeReference<Map<String, Object>>() {
        });
        document.put("headerRows", new ArrayList<>(List.of(Map.of("id", "h1"))));
        document.put("rows", new ArrayList<>(List.of(Map.of("id", "r1", "label", rowLabel))));
        return JSON.writeValueAsString(document);
    }

    /** A template with a saved, non-empty first version. */
    protected static String templateWithSheet(TestBrowser browser, String category, String subCategory) {
        String template = template(browser, category, subCategory);
        int number = firstVersion(browser, template);
        assertThat(fill(browser, template, number).status()).isEqualTo(200);
        return template;
    }

    // ---- approval processes and requests ----

    protected static String user(String userId) {
        return "{\"kind\":\"USER\",\"value\":\"" + userId + "\"}";
    }

    protected static String role(String roleId) {
        return "{\"kind\":\"ROLE\",\"value\":\"" + roleId + "\"}";
    }

    protected static String step(String name, String... approvers) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"completion\":\"ANY\",\"approvers\":["
                + String.join(",", approvers) + "]}";
    }

    protected static String skippableStep(String name, String... approvers) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"completion\":\"ANY\",\"approvers\":["
                + String.join(",", approvers) + "],\"skipWhenNobody\":true}";
    }

    /** Makes a process on the template object and returns its API name. */
    protected static String process(TestBrowser admin, String name, String... steps) {
        Response created = admin.postJson(DEFINITIONS + "/" + OBJECT, "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"description\":\"\",\"active\":true,\"entryCriteria\":null,\"allowSelfApproval\":false,"
                + "\"steps\":[" + String.join(",", steps) + "],\"outcomes\":null,\"fallback\":null}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.apiName");
    }

    protected static Response submit(TestBrowser browser, String template, String process) {
        return browser.postJson(RECORDS + "/" + template + "/approval", "{\"process\":\"" + process
                + "\",\"comment\":null}");
    }

    /** Submits a template that must be accepted and returns the identifier of the request. */
    protected static String submitted(TestBrowser browser, String template, String process) {
        Response response = submit(browser, template, process);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.data.summary.id");
    }

    protected static long instanceVersion(TestBrowser browser, String request) {
        Response response = browser.get(INSTANCES + "/" + request);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.<Number>read(response.body(), "$.data.summary.version").longValue();
    }

    protected static String statusOf(TestBrowser browser, String request) {
        return JsonPath.read(browser.get(INSTANCES + "/" + request).body(), "$.data.summary.status");
    }

    protected static Response decide(TestBrowser browser, String request, String action, String comment) {
        return browser.postJson(INSTANCES + "/" + request + "/" + action, "{\"version\":"
                + instanceVersion(browser, request) + ",\"comment\":"
                + (comment == null ? "null" : "\"" + comment + "\"") + "}");
    }

    protected static Response approve(TestBrowser browser, String request) {
        return decide(browser, request, "approve", null);
    }

    protected static Response reject(TestBrowser browser, String request) {
        return decide(browser, request, "reject", "Not good enough");
    }

    protected static Response recall(TestBrowser browser, String request) {
        return decide(browser, request, "recall", null);
    }

    protected static Response cancel(TestBrowser browser, String request) {
        return decide(browser, request, "cancel", "Stopped");
    }

    protected static String code(Response response) {
        return JsonPath.read(response.body(), "$.error.code");
    }

    protected static String message(Response response) {
        return JsonPath.read(response.body(), "$.error.message");
    }

    // ---- what a template reads like to the API ----

    protected static Object field(TestBrowser browser, String template, String field) {
        try {
            return JsonPath.read(browser.get(RECORDS + "/" + template).body(), "$.data.fields." + field);
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }

    protected static String number(TestBrowser browser, String template) {
        return (String) field(browser, template, "number");
    }

    protected static Response lifecycle(TestBrowser browser, String template) {
        return browser.get(TEMPLATES + "/" + template + "/lifecycle");
    }

    protected static String state(TestBrowser browser, String template) {
        Response response = lifecycle(browser, template);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.data.lifecycle.state");
    }

    /** The statuses of the versions of a template, newest first, as its writer sees them. */
    protected static List<String> statuses(TestBrowser browser, String template) {
        Response list = browser.get(versions(template));
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data.versions[*].status");
    }

    /** The ids of the templates the browser's member sees in the master list. */
    protected static List<String> listed(TestBrowser browser) {
        Response page = browser.get(TEMPLATES + "?size=200");
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data.rows[*].id");
    }

    // ---- departments, groups and publishing ----

    /** A public group with these people in it. */
    protected static String group(TestBrowser admin, String name, Person... people) {
        Response created = admin.postJson(GROUPS, "{\"name\":\"" + name + "\",\"description\":\"\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = JsonPath.read(created.body(), "$.data.id");
        for (Person person : people) {
            Response added = admin.postJson(GROUPS + "/" + id + "/members",
                    "{\"membershipId\":\"" + person.membership() + "\"}");
            assertThat(added.status()).as(added.body()).isIn(200, 201, 204);
        }
        return id;
    }

    /** Sets up the group of a department of a location, as the administrator who holds the department setup policy. */
    protected static String department(TestBrowser setup, String location, String department, String groupId) {
        Response made = setup.postJson(DEPARTMENTS, "{\"fields\":{\"name\":\"" + location + " " + department
                + "\",\"location\":\"" + location + "\",\"department\":\"" + department + "\",\"groupId\":\""
                + groupId + "\"}}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return JsonPath.read(made.body(), "$.data.id");
    }

    protected static String audiences(String... pairs) {
        List<String> list = new ArrayList<>();
        for (String pair : pairs) {
            String[] parts = pair.split("/");
            list.add("{\"location\":\"" + parts[0] + "\",\"department\":\"" + parts[1] + "\"}");
        }
        return "[" + String.join(",", list) + "]";
    }

    protected static Response publish(TestBrowser browser, String template, Integer version, String... pairs) {
        return browser.postJson(TEMPLATES + "/" + template + "/publish", "{"
                + (version == null ? "" : "\"versionNo\":" + version + ",") + "\"audiences\":" + audiences(pairs)
                + "}");
    }

    protected static Response unpublish(TestBrowser browser, String template) {
        return browser.postJson(TEMPLATES + "/" + template + "/unpublish", "{}");
    }

    protected static Response retire(TestBrowser browser, String template) {
        return browser.postJson(TEMPLATES + "/" + template + "/retire", "{}");
    }

    // ---- the rows behind ----

    protected static long scalar(Organization organization, String sql, Object... parameters) {
        try {
            return TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.count(connection, sql,
                    parameters));
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    /** The first column of every row of a query run as the owner of the database (the audit table). */
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

    protected static long audited(Organization organization, String type) {
        return Long.parseLong(owner("select count(*) from audit_record where event_type = ? and context_tenant_id = ?",
                type, organization.id().value()).get(0));
    }

    protected static long publications(Organization organization, String template, boolean live) {
        return scalar(organization, "select count(*) from inspection_publication where template_id = ?"
                + (live ? " and revoked_at is null" : ""), java.util.UUID.fromString(template));
    }

    protected static long shares(Organization organization, String template) {
        return scalar(organization, "select count(*) from record_share where record_id = ?",
                java.util.UUID.fromString(template));
    }
}
