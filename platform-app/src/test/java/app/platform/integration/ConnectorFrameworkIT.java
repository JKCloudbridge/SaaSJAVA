package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.Integrations.ScopeAction;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The exit test of Sprint 26 (ADR-0207, ADR-0211): the sample connector runs through the whole framework against a
 * service the test starts. "Runs through the full framework" means exactly this path, in one organization:
 *
 * <ol>
 *   <li>the catalogue describes the connector type and the form is drawn from that description;
 *   <li>a connection is defined with a credential <em>by reference</em>, the events it listens for and its scope;
 *   <li>it cannot be switched on before a test;
 *   <li>a test makes one call, with the secret resolved at that moment and sent in the header, and passes;
 *   <li>it is switched on, and the platform's two questions ({@link Integrations}) then answer for it;
 *   <li>when the service fails the connection becomes failing and is no longer usable, and a passing test recovers it;
 *   <li>switching it off stops all use at once;
 *   <li>it is removed.
 * </ol>
 *
 * Sending and receiving records is Sprint 28; here the "event stub" is the subscription: which switched-on connections
 * would be told about a record event.
 */
@PlatformIntegrationTest
@Import(TestSecrets.class)
class ConnectorFrameworkIT extends IntegrationSupport {

    private static final String OBJECT = "ObjectA__c";
    private static final String TOKEN = "s3cr3t-token-value";

    @Autowired
    private Integrations integrations;
    @Autowired
    private TenantContexts contexts;
    @Autowired
    private TestSecrets.Store secrets;

    private FakeService service;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private <T> T asked(Organization organization, java.util.function.Supplier<T> question) {
        return contexts.call(TenantContext.of(organization.id()), question);
    }

    private static void aMomentLater() throws InterruptedException {
        Thread.sleep(1200); // the test profile allows one test of a connection per second
    }

    @Test
    void theSampleConnectorRunsThroughTheFullFramework() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        secrets.put(SecretRef.forTenant(organization.id(), "partner-token"), SecretValue.of(TOKEN));
        service.answer("/health", FakeService.Reply.status(200));

        // 1. the catalogue
        Response types = admin.get(ROOT + "/connector-types");
        assertThat(types.status()).as(types.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(types.body(), "$.data[*].key")).contains("rest");
        assertThat(JsonPath.<List<String>>read(types.body(), "$.data[?(@.key=='rest')].settings[*].key"))
                .containsExactly("healthPath", "probeMethod", "expect", "timeoutSeconds");

        // 2. define by reference
        Map<String, Object> body = definition("partner-link", service.url());
        body.put("authKind", "BEARER_TOKEN");
        body.put("credentialName", "partner-token");
        body.put("settings", Map.of("healthPath", "/health"));
        body.put("subscriptions", List.of(Map.of("event", "record.created", "object", OBJECT)));
        body.put("scope", List.of(Map.of("object", OBJECT, "read", true, "create", true, "update", false)));
        Response created = create(admin, body);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.body()).as("the secret is not in the answer").doesNotContain(TOKEN);
        String id = JsonPath.read(created.body(), "$.data.id");
        assertThat(JsonPath.<String>read(created.body(), "$.data.state")).isEqualTo("UNTESTED");
        assertThat(JsonPath.<String>read(created.body(), "$.data.credentialName")).isEqualTo("partner-token");
        assertThat(JsonPath.<Boolean>read(created.body(), "$.data.canEnable")).isFalse();
        assertThat(service.seen()).as("defining a connection calls nobody").isEmpty();

        // 3. not before a test
        Response tooSoon = enable(admin, "partner-link", versionOf(admin, "partner-link"));
        assertThat(tooSoon.status()).as(tooSoon.body()).isEqualTo(409);
        assertThat(state(admin, "partner-link")).isEqualTo("UNTESTED");
        assertThat(asked(organization, () -> integrations.subscribersOf("record.created", OBJECT))).isEmpty();

        // 4. a test: one call, the secret resolved now and sent in the header, a stable reason back
        Response tested = test(admin, "partner-link");
        assertThat(tested.status()).as(tested.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(tested.body(), "$.data.result")).isEqualTo("PASSED");
        assertThat(JsonPath.<String>read(tested.body(), "$.data.reason")).isEqualTo("ok");
        assertThat(JsonPath.<String>read(tested.body(), "$.data.integration.state")).isEqualTo("TESTED");
        assertThat(tested.body()).doesNotContain(TOKEN).doesNotContain("the body is never read");
        assertThat(service.seen()).hasSize(1);
        assertThat(service.seen().get(0).method()).isEqualTo("GET");
        assertThat(service.seen().get(0).path()).isEqualTo("/health");
        assertThat(service.seen().get(0).authorization()).isEqualTo("Bearer " + TOKEN);

        // 5. switched on: the platform's two questions now answer for it
        Response enabled = enable(admin, "partner-link", versionOf(admin, "partner-link"));
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        assertThat(state(admin, "partner-link")).isEqualTo("ENABLED");
        UUID connection = UUID.fromString(id);
        assertThat(asked(organization, () -> integrations.subscribersOf("record.created", OBJECT)))
                .containsExactly(connection);
        assertThat(asked(organization, () -> integrations.subscribersOf("record.updated", OBJECT))).isEmpty();
        assertThat(asked(organization, () -> integrations.subscribersOf("record.created", "ObjectB__c"))).isEmpty();
        assertThat(asked(organization, () -> integrations.allows(connection, OBJECT, ScopeAction.READ))).isTrue();
        assertThat(asked(organization, () -> integrations.allows(connection, OBJECT, ScopeAction.CREATE))).isTrue();
        assertThat(asked(organization, () -> integrations.allows(connection, OBJECT, ScopeAction.UPDATE))).isFalse();
        assertThat(asked(organization, () -> integrations.allows(connection, "ObjectB__c", ScopeAction.READ)))
                .isFalse();

        // 6. the service fails: failing, no longer usable; it recovers when a test passes
        aMomentLater();
        service.answer("/health", FakeService.Reply.status(500));
        Response failed = test(admin, "partner-link");
        assertThat(JsonPath.<String>read(failed.body(), "$.data.result")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(failed.body(), "$.data.reason")).isEqualTo("unexpected_status");
        assertThat(state(admin, "partner-link")).isEqualTo("FAILING");
        assertThat(asked(organization, () -> integrations.subscribersOf("record.created", OBJECT))).isEmpty();
        assertThat(asked(organization, () -> integrations.allows(connection, OBJECT, ScopeAction.READ))).isFalse();
        aMomentLater();
        service.answer("/health", FakeService.Reply.status(200));
        assertThat(JsonPath.<String>read(test(admin, "partner-link").body(), "$.data.result")).isEqualTo("PASSED");
        assertThat(state(admin, "partner-link")).isEqualTo("ENABLED");

        // 7. switching off stops all use at once
        Response disabled = disable(admin, "partner-link");
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);
        assertThat(state(admin, "partner-link")).isEqualTo("DISABLED");
        assertThat(asked(organization, () -> integrations.subscribersOf("record.created", OBJECT))).isEmpty();
        assertThat(asked(organization, () -> integrations.allows(connection, OBJECT, ScopeAction.READ))).isFalse();

        // 8. removal
        Response removed = remove(admin, "partner-link");
        assertThat(removed.status()).as(removed.body()).isEqualTo(200);
        assertThat(admin.get(ROOT + "/partner-link").status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(admin.get(ROOT).body(), "$.data[*].name")).isEmpty();
        assertThat(service.seen()).as("the whole life made exactly the calls of the tests").hasSize(3);
    }
}
