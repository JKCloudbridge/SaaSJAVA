package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the connector framework (Sprint 26, ADR-0012, ADR-0208, ADR-0210): connections are made, changed,
 * tested in every way a test can end, switched on and off, removed, and refused in the ways a definition can be wrong,
 * with the application and the security framework logging at debug level. What the organization typed (the name and
 * label of a connection, the address and its path, a check path, the name of a credential, the name of a header)
 * and the secret itself are never logged, never written to the audit trail and never echoed by a refusal. The trail
 * does show that a connection changed and was tested, by identifiers, keys and counts.
 */
@PlatformIntegrationTest
@Import(TestSecrets.class)
@TestPropertySource(properties = {
    "logging.file.name=" + IntegrationFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class IntegrationFlowsLogsAreCleanIT extends IntegrationSupport {

    static final String LOG_FILE = "target/it-logs/integration-flows-clean.log";

    @Autowired
    private TestSecrets.Store secrets;

    private FakeService service;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedAndNoSecretIsLoggedOrWrittenToTheTrailOrEchoedByARefusal() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        String name = typed("link");
        String label = typed("label").replace("-", " ");
        String path = typed("/base");
        String check = typed("/check");
        String credential = typed("credential");
        String secret = typed("secret-value");
        String header = typed("X-Key");
        String hostile = "10.77.88.99";
        String hostileLabel = typed("markup") + "<script>";
        secrets.put(SecretRef.forTenant(organization.id(), credential), SecretValue.of(secret));
        service.answer(check, FakeService.Reply.status(200));

        Map<String, Object> body = definition(name, service.url() + path);
        body.put("label", label);
        body.put("authKind", "API_KEY_HEADER");
        body.put("authHeaderName", header);
        body.put("credentialName", credential);
        body.put("settings", Map.of("healthPath", check));
        body.put("subscriptions", List.of(Map.of("event", "record.created", "object", "ObjectA__c")));
        body.put("scope", List.of(Map.of("object", "ObjectA__c", "read", true, "create", false, "update", false)));
        assertThat(create(admin, body).status()).isEqualTo(201);

        List<Response> refusals = new ArrayList<>();
        Map<String, Object> hostileBody = definition(typed("hostile"), "https://" + hostile + "/" + typed("x"));
        hostileBody.put("label", hostileLabel);
        hostileBody.put("authKind", "BASIC");
        hostileBody.put("credentialName", "Bad Credential " + typed("c"));
        refusals.add(create(admin, hostileBody));
        refusals.add(create(admin, body));

        Response tested = test(admin, name);
        assertThat(tested.status()).as(tested.body()).isEqualTo(200);
        long version = versionOf(admin, name);
        Thread.sleep(1200);
        service.answer(check, FakeService.Reply.status(404));
        assertThat(test(admin, name).status()).isEqualTo(200);
        Thread.sleep(1200);
        secrets.breakDown(true);
        assertThat(test(admin, name).status()).isEqualTo(200);
        secrets.breakDown(false);
        Thread.sleep(1200);
        service.answer(check, FakeService.Reply.status(200));
        assertThat(test(admin, name).status()).isEqualTo(200);
        refusals.add(enable(admin, name, version));
        assertThat(enable(admin, name, versionOf(admin, name)).status()).isEqualTo(200);
        refusals.add(change(admin, name, body));
        refusals.add(remove(admin, name));
        assertThat(disable(admin, name).status()).isEqualTo(200);
        assertThat(remove(admin, name).status()).isEqualTo(200);
        refusals.add(admin.get(ROOT + "/" + name));

        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        assertThat(trail).as("the name is not in the trail").doesNotContain(name);
        // The name is part of the address of the endpoints, so the request log may show it; nothing else typed may.
        for (String text : List.of(label, path, check, credential, secret, header, hostile,
                hostileLabel.replace("<script>", ""))) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            for (Response refused : refusals) {
                if (refused.status() >= 400) {
                    assertThat(refused.body()).as("a refusal does not echo: " + text).doesNotContain(text);
                }
            }
        }
        assertThat(refusals.stream().filter(one -> one.status() >= 400).count()).as("the refusals happened")
                .isGreaterThanOrEqualTo(5);
        assertThat(trail).as("the trail shows that connections changed and were tested")
                .contains("integration.created").contains("integration.tested").contains("integration.enabled")
                .contains("integration.disabled").contains("integration.removed");
    }
}
