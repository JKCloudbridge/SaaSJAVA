package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.metadata.CompiledRule;
import app.platform.metadata.MetadataResolver;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Publications on three real instances, each with its own relay (ADR-0080): instance A (the test's own, cache on),
 * instance B (cache on) and instance C (cache off). Every release writes an event; the outbox hands each event to one
 * instance, and the handler there warms the catalogue. What this test proves: every event is handled exactly once in
 * all (the markers say so), whichever instance claimed it; and whether it was handled, delayed or not yet handled,
 * every instance gives the right answer on its next question, because the answer comes from the version in the
 * database and never from the event.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms"})
class MetadataWarmingIT {

    private static final String OBJECTS = "/api/v1/metadata/objects";
    private static final String CONSUMER = "metadata.warm-cache";

    private static TestApplication instanceB;
    private static TestApplication instanceC;

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private MetadataResolver resolverA;

    @Autowired
    private TenantContexts contextsA;

    @BeforeAll
    static void startTheOtherInstances() {
        instanceB = TestApplication.start("platform.outbox.enabled=true", "platform.outbox.poll-interval=50ms");
        instanceC = TestApplication.start("platform.outbox.enabled=true", "platform.outbox.poll-interval=50ms",
                "platform.metadata.cache.enabled=false");
    }

    @AfterAll
    static void stopTheOtherInstances() {
        instanceC.close();
        instanceB.close();
    }

    private static void ok(Response response) {
        assertThat(response.status()).as(response.body()).isBetween(200, 204);
    }

    private static List<String> rules(TenantContexts contexts, MetadataResolver resolver, Organization organization) {
        return contexts.call(TenantContext.of(organization.id()), () -> resolver.validationRules("Order__c").stream()
                .map(CompiledRule::apiName).toList());
    }

    private void everyInstanceSees(Organization organization, String step, List<String> expected) {
        assertThat(rules(contextsA, resolverA, organization)).as(step + ": instance A").isEqualTo(expected);
        assertThat(rules(instanceB.bean(TenantContexts.class), instanceB.bean(MetadataResolver.class), organization))
                .as(step + ": instance B").isEqualTo(expected);
        assertThat(rules(instanceC.bean(TenantContexts.class), instanceC.bean(MetadataResolver.class), organization))
                .as(step + ": instance C, cache off").isEqualTo(expected);
    }

    private static long count(Organization organization, String sql, Object... parameters) throws SQLException {
        return TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.count(connection, sql,
                parameters));
    }

    @Test
    void everyEventIsHandledExactlyOnceByOneInstanceAndEveryInstanceIsRightOnItsNextQuestion() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());

        ok(admin.postJson(OBJECTS, "{\"name\":\"Order\",\"label\":\"Order\",\"pluralLabel\":\"Orders\"}"));
        ok(admin.postJson(OBJECTS + "/Order__c/fields", "{\"name\":\"quantity\",\"label\":\"Quantity\","
                + "\"type\":\"NUMBER\"}"));
        everyInstanceSees(organization, "no rule yet", List.of());

        ok(admin.postJson(OBJECTS + "/Order__c/validation-rules", "{\"name\":\"NoNegative\",\"label\":\"N\","
                + "\"expression\":\"quantity__c < 0\",\"errorMessage\":\"Message\"}"));
        everyInstanceSees(organization, "a rule was added", List.of("NoNegative__c"));

        ok(admin.postJson(OBJECTS + "/Order__c/validation-rules", "{\"name\":\"Huge\",\"label\":\"H\","
                + "\"expression\":\"quantity__c > 1000\",\"errorMessage\":\"Message\"}"));
        everyInstanceSees(organization, "another rule was added", List.of("NoNegative__c", "Huge__c"));

        ok(admin.request("DELETE", OBJECTS + "/Order__c/validation-rules/NoNegative__c", null));
        everyInstanceSees(organization, "a rule was removed", List.of("Huge__c"));

        long events = count(organization, "select count(*) from outbox_event where event_type = ?",
                "metadata.published");
        assertThat(events).as("one event for every release").isGreaterThanOrEqualTo(5);
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(count(organization, "select count(*) from outbox_event where event_type = ? "
                        + "and status <> 'DELIVERED'", "metadata.published")).as("every event was delivered")
                        .isZero());

        assertThat(count(organization, "select count(*) from processed_event where consumer = ?", CONSUMER))
                .as("every event was handled once in all, by whichever instance claimed it").isEqualTo(events);
        everyInstanceSees(organization, "after the events were handled", List.of("Huge__c"));
    }
}
