package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.licensing.Plans;
import app.platform.licensing.SubscriptionChange;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the Sprint 22 integration tests of workflow definitions share: the organization and the {@code Order__c}
 * object of the expression tests, made with a plan that has the feature {@code workflows}, and short ways to write
 * the requests of workflows. Everything goes through the real API.
 */
abstract class WorkflowTestSupport extends ExpressionTestSupport {

    protected static final String WORKFLOWS = "/api/v1/metadata/workflows";
    protected static final String ORDER_WORKFLOWS = WORKFLOWS + "/Order__c";

    @Autowired
    private Plans workflowPlans;

    @Autowired
    private Subscriptions workflowSubscriptions;

    /** An organization whose plan has workflows. */
    @Override
    protected Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(workflowPlans, workflowSubscriptions, organization.id(), 5, 3, "workflows");
        return organization;
    }

    /** An organization whose plan has nothing but the licences. */
    protected Organization organizationWithoutTheFeature() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(workflowPlans, workflowSubscriptions, organization.id(), 5, 3);
        return organization;
    }

    /** Moves the organization to a plan that has nothing but the licences. */
    protected void loseTheFeature(Organization organization) {
        String key = TestPlatform.plan(workflowPlans, 5,
                Math.max(3, TestPlatform.adminLicencesInUse(organization.id())));
        workflowSubscriptions.change(organization.id(), new SubscriptionChange(key, null, null, null),
                app.platform.sharedkernel.ActorId.SYSTEM);
    }

    protected static String announce(String name, String event) {
        return "{\"type\":\"PUBLISH_EVENT\",\"name\":\"" + name + "\",\"settings\":{\"event\":\"" + event + "\"}}";
    }

    protected static String trigger(String kind) {
        return "{\"kind\":\"" + kind + "\"}";
    }

    protected static String fieldsChanged(String... fields) {
        return "{\"kind\":\"FIELD_CHANGED\",\"fields\":[" + String.join(",",
                Arrays.stream(fields).map(field -> "\"" + field + "\"").toList()) + "]}";
    }

    protected static String workflowRequest(String name, String trigger, String condition, boolean active,
            String... actions) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\",\"active\":" + active
                + ",\"trigger\":" + trigger + ",\"condition\":"
                + (condition == null ? "null" : "\"" + condition + "\"") + ",\"actions\":["
                + String.join(",", actions) + "]}";
    }

    protected static String workflowUpdate(String label, String trigger, String condition, boolean active,
            long version, String... actions) {
        return "{\"label\":\"" + label + "\",\"description\":\"\",\"active\":" + active + ",\"trigger\":" + trigger
                + ",\"condition\":" + (condition == null ? "null" : "\"" + condition + "\"") + ",\"actions\":["
                + String.join(",", actions) + "],\"version\":" + version + "}";
    }

    protected static Response createWorkflow(TestBrowser admin, String name, String trigger, String condition,
            String... actions) {
        return admin.postJson(ORDER_WORKFLOWS, workflowRequest(name, trigger, condition, true, actions));
    }

    protected static void workflowExists(TestBrowser admin, String name, String trigger, String condition,
            String... actions) {
        Response created = createWorkflow(admin, name, trigger, condition, actions);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    protected static long workflowVersion(TestBrowser admin, String workflow) {
        return JsonPath.<Number>read(admin.get(ORDER_WORKFLOWS + "/" + workflow).body(), "$.data.version")
                .longValue();
    }

    protected static List<String> workflowNames(TestBrowser admin) {
        Response list = admin.get(WORKFLOWS + "?object=Order__c");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[*].apiName");
    }

    protected static long count(Organization organization, String sql, Object... parameters) {
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

    /** Makes one finished execution of the workflow in the organization, as the dispatcher would have left it. */
    protected static String anExecutionOf(Organization organization, String workflow) {
        String key = "test-" + java.util.UUID.randomUUID();
        run(organization, "insert into workflow_execution (workflow_id, workflow_version_id, workflow_api_name, "
                + "workflow_version_number, object_api_name, record_id, trigger_kind, event_id, idempotency_key, "
                + "status, finished_at, created_by, updated_by) select w.id, v.id, w.api_name, v.version_number, "
                + "w.object_api_name, cast(? as uuid), 'RECORD_CREATED', cast(? as uuid), ?, 'COMPLETED', now(), "
                + "cast(? as uuid), cast(? as uuid) from workflow w "
                + "join workflow_version v on v.workflow_id = w.id where w.api_name = ?",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), key,
                app.platform.sharedkernel.ActorId.SYSTEM.value(), app.platform.sharedkernel.ActorId.SYSTEM.value(),
                workflow);
        try {
            return TenantFixtures.asTenant(organization.id(), connection -> {
                try (var statement = connection.prepareStatement("select id::text from workflow_execution "
                        + "where idempotency_key = ?")) {
                    statement.setString(1, key);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        return result.getString(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }
}
