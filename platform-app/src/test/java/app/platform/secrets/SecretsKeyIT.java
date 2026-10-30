package app.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.integration.FakeService;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The key that protects the stored keys (Sprint 27, ADR-0212, ADR-0214): an application without a valid key does not
 * start; an instance that does not hold the key a version was sealed with cannot open it, says so with a stable reason
 * and writes one audit record, never a value; an instance that still holds the old key as a previous key opens it and
 * wraps its data key again under the new one, and the organization recovers by replacing the value.
 */
@PlatformIntegrationTest
class SecretsKeyIT extends CredentialSupport {

    private static final String TEST_KEY = Base64.getEncoder()
            .encodeToString("test-key-encryption-key-32-bytes".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_KEY = Base64.getEncoder()
            .encodeToString("other-key-encryption-key-32-byte".getBytes(StandardCharsets.UTF_8));

    private FakeService service;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
        service.answer("/health", FakeService.Reply.status(200));
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private static String reason(app.platform.testsupport.TestHttp.Response tested) {
        return JsonPath.read(tested.body(), "$.data.reason");
    }

    @Test
    void anApplicationWithoutAValidKeyDoesNotStart() {
        assertThatThrownBy(() -> TestApplication.start("platform.secrets.kek.active-id=",
                "platform.secrets.kek.active-key=")).hasStackTraceContaining("platform.secrets.kek.active-id");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> TestApplication.start("platform.secrets.kek.active-key=" + shortKey))
                .hasStackTraceContaining("32 random bytes");
        assertThatThrownBy(() -> TestApplication.start("platform.secrets.kek.active-key=" + TEST_KEY,
                "platform.secrets.kek.active-file=/some/where.txt")).hasStackTraceContaining("not both");
    }

    @Test
    void anInstanceWithoutTheKeyCannotOpenAVersionAndSaysSoOnceAnHour() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        write(admin, "sealed-token", "canary-sealed");
        create(admin, signedIn("sealed-link", service.url(), "sealed-token"));

        try (TestApplication other = TestApplication.start("platform.secrets.kek.active-id=other-kek",
                "platform.secrets.kek.active-key=" + OTHER_KEY)) {
            TestBrowser onOther = TestOrganizations.signedIn(other.port(), organization.host(),
                    organization.admin().person());

            var first = test(onOther, "sealed-link");
            aMomentLater();
            var second = test(onOther, "sealed-link");

            assertThat(reason(first)).isEqualTo("credential_store_unavailable");
            assertThat(reason(second)).isEqualTo("credential_store_unavailable");
            assertThat(first.body()).doesNotContain("canary").doesNotContain("key_unavailable");
            assertThat(service.seen()).as("no call was made without a credential").isEmpty();
        }
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        assertThat(trail).contains("credentials.resolve_failed").doesNotContain("canary");
        // One record for the hour; a second only if the two tests fell on either side of the turn of the hour.
        assertThat(countOf(trail, "credentials.resolve_failed")).isBetween(1, 2);
        assertThat(countRows(organization.id(), "select coalesce(sum(failures), 0) from secret_usage")).isEqualTo(2);
    }

    @Test
    void anInstanceThatKeepsTheOldKeyOpensTheVersionAndWrapsItsKeyAgainAndReplacingTheValueRecoversTheOldInstance()
            throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        write(admin, "moved-token", "canary-moved");
        create(admin, signedIn("moved-link", service.url(), "moved-token"));

        try (TestApplication rotated = TestApplication.start("platform.secrets.kek.active-id=other-kek",
                "platform.secrets.kek.active-key=" + OTHER_KEY, "platform.secrets.kek.previous[0].id=test-kek",
                "platform.secrets.kek.previous[0].key=" + TEST_KEY)) {
            TestBrowser onRotated = TestOrganizations.signedIn(rotated.port(), organization.host(),
                    organization.admin().person());

            assertThat(reason(test(onRotated, "moved-link"))).isEqualTo("ok");
            assertThat(service.seen().get(0).authorization()).isEqualTo("Bearer canary-moved");
            assertThat(countRows(organization.id(), "select count(*) from secret_version where key_id = 'other-kek'"))
                    .as("the data key is wrapped under the new key").isOne();
        }

        aMomentLater();
        assertThat(reason(test(admin, "moved-link"))).as("the old instance no longer holds the key it needs")
                .isEqualTo("credential_store_unavailable");
        write(admin, "moved-token", "canary-replaced");
        aMomentLater();
        assertThat(reason(test(admin, "moved-link"))).isEqualTo("ok");
        assertThat(service.seen().get(1).authorization()).isEqualTo("Bearer canary-replaced");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
