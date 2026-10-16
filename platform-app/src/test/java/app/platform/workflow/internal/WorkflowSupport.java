package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What the Sprint 22 integration tests of the runtime share (ADR-0133 to ADR-0137): an organization whose plan has the
 * feature {@code workflows}, an administrator, the sample object {@code ObjectA__c} (three text fields) with a number
 * and a date added, short ways to make workflows and records through the real API, and ways to drive the dispatcher and
 * to look at the rows of an organization. Generic data only.
 *
 * <p>The tests that use it switch the outbox relay on (it is the thing that turns an event into an execution) and
 * switch the dispatcher off, then run the worker and the scheduler themselves, so every state between "queued" and
 * "ended" can be looked at.
 */
abstract class WorkflowSupport {

    protected static final String METADATA_OBJECTS = "/api/v1/metadata/objects";
    protected static final String WORKFLOWS = "/api/v1/metadata/workflows";
    protected static final String EXECUTIONS = "/api/v1/workflows/executions";
    protected static final String OBJECT = "ObjectA__c";
    protected static final String RECORDS = "/api/v1/objects/" + OBJECT + "/records";

    @LocalServerPort
    protected int port;

    @Autowired
    protected Users users;

    @Autowired
    protected Plans plans;

    @Autowired
    protected Subscriptions subscriptions;

    @Autowired
    protected Dispatcher dispatcher;

    /** An organization whose plan has workflows. */
    protected Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3, "workflows");
        return organization;
    }

    /** An organization whose plan has nothing but the licences. */
    protected Organization organizationWithoutTheFeature() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        return organization;
    }

    protected TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    /** The administrator of an organization whose sample object has a number and a date field as well. */
    protected TestBrowser adminWithData(Organization organization) {
        TestBrowser admin = adminOf(organization);
        for (String field : List.of("{\"name\":\"quantity\",\"label\":\"quantity\",\"type\":\"NUMBER\"}",
                "{\"name\":\"closeDate\",\"label\":\"closeDate\",\"type\":\"DATE\"}")) {
            Response made = admin.postJson(METADATA_OBJECTS + "/" + OBJECT + "/fields", field);
            assertThat(made.status()).as(made.body()).isEqualTo(201);
        }
        return admin;
    }

    // ---- workflows ----

    protected static String action(String name, String event) {
        return "{\"type\":\"PUBLISH_EVENT\",\"name\":\"" + name + "\",\"settings\":{\"event\":\"" + event + "\"}}";
    }

    protected static String recordTrigger(String kind) {
        return "{\"kind\":\"" + kind + "\"}";
    }

    protected static String fieldChanged(String... fields) {
        return "{\"kind\":\"FIELD_CHANGED\",\"fields\":[" + String.join(",",
                java.util.Arrays.stream(fields).map(field -> "\"" + field + "\"").toList()) + "]}";
    }

    protected static String createRequest(String name, String trigger, String condition, String... actions) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\",\"active\":true,"
                + "\"trigger\":" + trigger + ",\"condition\":" + (condition == null ? "null" : "\"" + condition + "\"")
                + ",\"actions\":[" + String.join(",", actions) + "]}";
    }

    protected static String updateRequest(String label, boolean active, String trigger, String condition,
            long version, String... actions) {
        return "{\"label\":\"" + label + "\",\"description\":\"\",\"active\":" + active + ",\"trigger\":" + trigger
                + ",\"condition\":" + (condition == null ? "null" : "\"" + condition + "\"") + ",\"actions\":["
                + String.join(",", actions) + "],\"version\":" + version + "}";
    }

    protected static Response createWorkflow(TestBrowser admin, String name, String trigger, String condition,
            String... actions) {
        return admin.postJson(WORKFLOWS + "/" + OBJECT, createRequest(name, trigger, condition, actions));
    }

    /** Makes a workflow that must succeed, and returns its API name. */
    protected static String workflowExists(TestBrowser admin, String name, String trigger, String condition,
            String... actions) {
        Response created = createWorkflow(admin, name, trigger, condition, actions);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.apiName");
    }

    protected static long versionOf(TestBrowser admin, String workflow) {
        return JsonPath.<Number>read(admin.get(WORKFLOWS + "/" + OBJECT + "/" + workflow).body(), "$.data.version")
                .longValue();
    }

    protected static Response change(TestBrowser admin, String workflow, String trigger, String condition,
            boolean active, String... actions) {
        return admin.request("PUT", WORKFLOWS + "/" + OBJECT + "/" + workflow,
                updateRequest(workflow, active, trigger, condition, versionOf(admin, workflow), actions));
    }

    protected static Response switchTo(TestBrowser admin, String workflow, boolean active) {
        return admin.postJson(WORKFLOWS + "/" + OBJECT + "/" + workflow + "/activation",
                "{\"active\":" + active + ",\"version\":" + versionOf(admin, workflow) + "}");
    }

    // ---- records ----

    /** Makes a record and returns its identifier. */
    protected static String record(TestBrowser admin, String fieldsJson) {
        Response created = admin.postJson(RECORDS, "{\"fields\":" + fieldsJson + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    protected static Response changeRecord(TestBrowser admin, String id, String fieldsJson) {
        long version = JsonPath.<Number>read(admin.get(RECORDS + "/" + id).body(), "$.data.version").longValue();
        return admin.request("PATCH", RECORDS + "/" + id, "{\"fields\":" + fieldsJson + ",\"version\":" + version
                + "}");
    }

    // ---- driving the runtime ----

    /** Waits until the outbox has handed every event of the organization to its handlers. */
    protected static void settle(Organization organization) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(
                () -> scalar(organization, "select count(*) from outbox_event where status = 'PENDING'") == 0);
    }

    /** Runs the worker until it finds nothing more to claim. */
    protected void runWorker() {
        runWorker(dispatcher);
    }

    protected static void runWorker(Dispatcher which) {
        int rounds = 0;
        while (which.pollExecutionsOnce() > 0 && ++rounds < 50) {
            // Keep claiming: a round that finds nothing ends the loop.
        }
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

    /** The executions of the organization in the order they were made: status, reason, version and attempts. */
    protected static List<Map<String, String>> executions(Organization organization) {
        return rows(organization, "select workflow_api_name, workflow_version_number, trigger_kind, status, reason, "
                + "attempts, failure_type, record_id::text as record_id, event_id is not null as from_event, "
                + "firing_at is not null as from_firing from workflow_execution order by created_at, id");
    }

    protected static List<String> statuses(Organization organization) {
        return executions(organization).stream().map(row -> row.get("status")).toList();
    }

    protected static List<String> reasons(Organization organization) {
        return executions(organization).stream().map(row -> row.get("reason")).toList();
    }

    /** How many events of this exact type the organization's outbox holds. */
    protected static long signals(Organization organization, String type) {
        return scalar(organization, "select count(*) from outbox_event where event_type = ?", type);
    }
}
