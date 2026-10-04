package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the Sprint 9 flows (ADR-0054, ADR-0056): changes that leave audit records, the organization's viewer
 * and the platform's viewer, the filters, refused filters, with the application and the security framework logging at
 * debug level. What a person typed (a description, a value recorded as an old or new value) is never logged and never
 * shown by the viewer, a refused filter does not echo what was typed, and the text form of a record holds no value.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + AuditFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class AuditFlowsLogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/audit-flows-clean.log";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private TenantContexts contexts;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedOrShownAcrossTheAuditFlows() throws IOException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        String description = typed("group-description");
        String oldTyped = typed("old-typed-value");
        String newTyped = typed("new-typed-value");
        String reasonTyped = typed("typed-reason");
        List<String> responses = new ArrayList<>();

        admin.postJson("/api/v1/groups", "{\"name\":\"" + typed("group-name") + "\",\"description\":\""
                + description + "\"}");
        contexts.run(TenantContext.of(organization.id()), () -> {
            recorder.record(AuditRecord.of("access.probe.changed", AuditOutcome.SUCCESS).inTenant(organization.id())
                    .onObject("object-a", "record-1").changing(oldTyped, newTyped));
            recorder.record(AuditRecord.of("platform.support_access.requested", AuditOutcome.SUCCESS)
                    .inTenant(organization.id()).with("reason_text", reasonTyped));
        });
        Response organizationPage = admin.get("/api/v1/audit-events?limit=200");
        responses.add(organizationPage.body());
        responses.add(admin.get("/api/v1/audit-events?kind=access.group&limit=5").body());
        responses.add(admin.get("/api/v1/audit-events?target=record-1").body());
        responses.add(admin.get("/api/v1/audit-events?kind=Not%20A%20Kind").body());
        responses.add(platform.get("/api/v1/platform/audit-events?limit=200").body());
        List<String> refused = List.of(admin.get("/api/v1/audit-events?kind=" + typed("Typed Kind").replace(" ", "%20"))
                .body());

        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        assertThat(log).as("typed text is not logged").doesNotContain(description).doesNotContain(reasonTyped);
        assertThat(log).as("recorded values are not logged").doesNotContain(oldTyped).doesNotContain(newTyped);
        assertThat(organizationPage.body()).as("the viewer shows the recorded change, as the API's data")
                .contains(oldTyped).contains(newTyped);
        for (String body : responses) {
            assertThat(body).as("typed reasons never leave the platform's side").doesNotContain(reasonTyped)
                    .doesNotContain(description);
        }
        assertThat(refused.get(0)).as("a refused filter does not echo what was typed").doesNotContain("Typed")
                .contains("VALIDATION_ERROR");
        assertThat((String) JsonPath.read(responses.get(3), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(AuditRecord.of("access.x.y", AuditOutcome.SUCCESS).changing(oldTyped, newTyped).toString())
                .doesNotContain(oldTyped);
    }
}
