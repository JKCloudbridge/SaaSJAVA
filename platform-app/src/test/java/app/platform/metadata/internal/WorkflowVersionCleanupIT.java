package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Old definitions of workflows are removed when nothing names them (ADR-0136): never the current one, never one an
 * execution still points at (the foreign key is the truth about "named"), and never another organization's.
 */
@PlatformIntegrationTest
class WorkflowVersionCleanupIT {

    private static final String WORKFLOWS = "/api/v1/metadata/workflows/ObjectA__c";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private PlatformTransactionManager transactions;

    private static String request(String name, String event) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\",\"active\":true,"
                + "\"trigger\":{\"kind\":\"RECORD_CREATED\"},\"condition\":null,\"actions\":[{\"type\":"
                + "\"PUBLISH_EVENT\",\"name\":\"Ask\",\"settings\":{\"event\":\"" + event + "\"}}]}";
    }

    private static String update(String event, long version) {
        return "{\"label\":\"Watch\",\"description\":\"\",\"active\":true,\"trigger\":{\"kind\":\"RECORD_CREATED\"},"
                + "\"condition\":null,\"actions\":[{\"type\":\"PUBLISH_EVENT\",\"name\":\"Ask\",\"settings\":{"
                + "\"event\":\"" + event + "\"}}],\"version\":" + version + "}";
    }

    private static long count(Organization organization, String sql, Object... parameters) throws SQLException {
        return TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.count(connection, sql,
                parameters));
    }

    private int clean(Organization organization) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> new TransactionTemplate(transactions).execute(status -> store.cleanVersions(Duration.ZERO, 100)));
    }

    @Test
    void anOldDefinitionIsRemovedUnlessItIsTheCurrentOneOrAnExecutionNamesIt() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3, "workflows");
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        Response made = admin.postJson(WORKFLOWS, request("Watch", "first.event"));
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        for (String event : new String[] {"second.event", "third.event", "fourth.event"}) {
            long version = ((Number) com.jayway.jsonpath.JsonPath.read(admin.get(WORKFLOWS + "/Watch__c").body(),
                    "$.data.version")).longValue();
            assertThat(admin.request("PUT", WORKFLOWS + "/Watch__c", update(event, version)).status()).isEqualTo(200);
        }
        assertThat(count(organization, "select count(*) from workflow_version")).isEqualTo(4);
        // An execution that ran under the first version still names it.
        TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection,
                "insert into workflow_execution (workflow_id, workflow_version_id, workflow_api_name, "
                        + "workflow_version_number, object_api_name, record_id, trigger_kind, event_id, "
                        + "idempotency_key, status, finished_at, created_by, updated_by) select w.id, v.id, "
                        + "w.api_name, v.version_number, w.object_api_name, cast(? as uuid), 'RECORD_CREATED', "
                        + "cast(? as uuid), ?, 'COMPLETED', now(), cast(? as uuid), cast(? as uuid) from workflow w "
                        + "join workflow_version v on v.workflow_id = w.id "
                        + "where v.version_number = 1", UUID.randomUUID(), UUID.randomUUID(), "k-" + UUID.randomUUID(),
                ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));

        int removed = clean(organization);

        assertThat(removed).as("the second and the third: not current, not named").isEqualTo(2);
        assertThat(count(organization, "select count(*) from workflow_version")).isEqualTo(2);
        assertThat(count(organization, "select count(*) from workflow_version where version_number = 1")).isEqualTo(1);
        assertThat(count(organization, "select count(*) from workflow_version where version_number = 4")).isEqualTo(1);
        assertThat(clean(organization)).as("a second pass finds nothing more").isZero();
    }

    @Test
    void theDefinitionsOfARemovedWorkflowGoWhenNothingNamesThem() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3, "workflows");
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        admin.postJson(WORKFLOWS, request("Gone", "first.event"));
        admin.request("DELETE", WORKFLOWS + "/Gone__c", null);

        int removed = clean(organization);

        assertThat(removed).isEqualTo(1);
        assertThat(count(organization, "select count(*) from workflow_version")).isZero();
    }

    @Test
    void aRecentDefinitionIsKeptForTheGracePeriod() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3, "workflows");
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        admin.postJson(WORKFLOWS, request("Watch", "first.event"));
        long version = ((Number) com.jayway.jsonpath.JsonPath.read(admin.get(WORKFLOWS + "/Watch__c").body(),
                "$.data.version")).longValue();
        admin.request("PUT", WORKFLOWS + "/Watch__c", update("second.event", version));

        int removed = contexts.call(TenantContext.of(organization.id()), () -> new TransactionTemplate(transactions)
                .execute(status -> store.cleanVersions(Duration.ofDays(7), 100)));

        assertThat(removed).isZero();
        assertThat(count(organization, "select count(*) from workflow_version")).isEqualTo(2);
    }
}
