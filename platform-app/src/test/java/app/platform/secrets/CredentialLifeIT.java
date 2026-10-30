package app.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.FakeService;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exit test of Sprint 27 (ADR-0212, ADR-0213): a credential can be created, used, rotated and audited without ever
 * being readable through any API. The life is lived through the endpoints and a real connection test against a service
 * the test starts: the store resolves the value on the test's own worker thread, the service sees the header, and no
 * answer of the platform ever holds the value.
 */
@PlatformIntegrationTest
class CredentialLifeIT extends CredentialSupport {

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

    private static String header(FakeService service, int call) {
        return service.seen().get(call).authorization();
    }

    @Test
    void aCredentialIsCreatedUsedRotatedRolledBackAndRemovedAndNeverReadable() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);

        // created: the answer is facts only
        Response made = write(admin, "partner-token", "canary-one");
        assertThat(made.status()).as(made.body()).isEqualTo(200);
        assertThat(made.body()).doesNotContain("canary");
        assertThat(JsonPath.<Integer>read(made.body(), "$.data.version")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(made.body(), "$.data.usedBy")).isZero();
        assertThat(made.body()).doesNotContain("\"lastUsedAt\":\"");
        assertThat(JsonPath.<Boolean>read(made.body(), "$.data.canRollBack")).isFalse();

        // used: a connection test resolves it at that moment and sends it in the header
        assertThat(create(admin, signedIn("partner-link", service.url(), "partner-token")).status()).isEqualTo(201);
        Response first = test(admin, "partner-link");
        assertThat(JsonPath.<String>read(first.body(), "$.data.result")).as(first.body()).isEqualTo("PASSED");
        assertThat(header(service, 0)).isEqualTo("Bearer canary-one");
        assertThat(first.body()).doesNotContain("canary");
        Response listed = credentials(admin);
        assertThat(listed.body()).doesNotContain("canary");
        assertThat(JsonPath.<Integer>read(listed.body(), "$.data[0].usedBy")).isEqualTo(1);
        assertThat(JsonPath.<Object>read(listed.body(), "$.data[0].lastUsedAt")).isNotNull();
        assertThat(state(admin, "partner-link")).isEqualTo("TESTED");

        // rotated: a new version, and the test that vouched for the old one is void
        Response rotated = write(admin, "partner-token", "canary-two");
        assertThat(JsonPath.<Integer>read(rotated.body(), "$.data.version")).isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(rotated.body(), "$.data.canRollBack")).isTrue();
        Response connection = admin.get(ROOT + "/partner-link");
        assertThat(JsonPath.<String>read(connection.body(), "$.data.state")).isEqualTo("UNTESTED");
        assertThat(JsonPath.<String>read(connection.body(), "$.data.lastTest.result")).isEqualTo("VOIDED");
        assertThat(JsonPath.<String>read(connection.body(), "$.data.lastTest.reason")).isEqualTo("credential_changed");
        assertThat(JsonPath.<Boolean>read(connection.body(), "$.data.canEnable")).isFalse();
        assertThat(enable(admin, "partner-link", versionOf(admin, "partner-link")).status()).isEqualTo(409);

        // used again: the next test proves the new value
        aMomentLater();
        assertThat(JsonPath.<String>read(test(admin, "partner-link").body(), "$.data.result")).isEqualTo("PASSED");
        assertThat(header(service, 1)).isEqualTo("Bearer canary-two");

        // a rotation of a credential a connection that is on uses: it keeps running, its test is void
        assertThat(enable(admin, "partner-link", versionOf(admin, "partner-link")).status()).isEqualTo(200);
        write(admin, "partner-token", "canary-three");
        assertThat(state(admin, "partner-link")).isEqualTo("ENABLED");
        assertThat(JsonPath.<String>read(admin.get(ROOT + "/partner-link").body(), "$.data.lastTest.result"))
                .isEqualTo("VOIDED");

        // it cannot be removed while a connection that is on uses it
        Response refused = removeCredential(admin, "partner-token");
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(disable(admin, "partner-link").status()).isEqualTo(200);

        // rolled back: the version before the current one becomes the current value, as a new version
        Response back = rollBack(admin, "partner-token");
        assertThat(back.status()).as(back.body()).isEqualTo(200);
        assertThat(back.body()).doesNotContain("canary");
        assertThat(JsonPath.<Integer>read(back.body(), "$.data.version")).isEqualTo(4);
        aMomentLater();
        assertThat(JsonPath.<String>read(test(admin, "partner-link").body(), "$.data.result")).isEqualTo("PASSED");
        assertThat(header(service, 2)).isEqualTo("Bearer canary-two");

        // removed: every version goes, and the connection says its credential is missing
        assertThat(removeCredential(admin, "partner-token").status()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(credentials(admin).body(), "$.data")).isEmpty();
        aMomentLater();
        Response missing = test(admin, "partner-link");
        assertThat(JsonPath.<String>read(missing.body(), "$.data.reason")).isEqualTo("credential_missing");
        assertThat(countRows(organization.id(), "select count(*) from secret_version")).isZero();
        assertThat(service.seen()).as("the service was called exactly by the tests that passed").hasSize(3);

        // audited: who and what, never the value
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        assertThat(trail).contains("credentials.created", "credentials.rotated", "credentials.rolled_back",
                "credentials.deleted", "credentials.first_used");
        assertThat(trail).doesNotContain("canary");
    }

    @Test
    void aRollbackWithNothingBeforeItIsRefusedAndAnUnknownNameIsNotFound() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        write(admin, "single-version", "canary-single");

        assertThat(rollBack(admin, "single-version").status()).isEqualTo(409);
        assertThat(rollBack(admin, "no-such-key").status()).isEqualTo(404);
        assertThat(removeCredential(admin, "no-such-key").status()).isEqualTo(404);
    }

    @Test
    void onlyTheLastFewVersionsAreKeptAndTheHistoryOnlyMovesForward() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);

        for (int i = 1; i <= 5; i++) {
            assertThat(JsonPath.<Integer>read(write(admin, "busy-key", "canary-" + i).body(), "$.data.version"))
                    .isEqualTo(i);
        }

        assertThat(countRows(organization.id(), "select count(*) from secret_version")).isEqualTo(3);
        assertThat(countRows(organization.id(), "select count(*) from secret_version where ordinal = 5")).isOne();
        assertThat(JsonPath.<Integer>read(rollBack(admin, "busy-key").body(), "$.data.version")).isEqualTo(6);
        assertThat(countRows(organization.id(), "select count(*) from secret_version")).isEqualTo(3);
    }

    @Test
    void aRotationIsSeenByAnotherInstanceAtItsNextUse() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        write(admin, "shared-token", "canary-before");
        create(admin, signedIn("shared-link", service.url(), "shared-token"));
        try (TestApplication second = TestApplication.start()) {
            TestBrowser onSecond = TestOrganizations.signedIn(second.port(), organization.host(),
                    organization.admin().person());
            assertThat(JsonPath.<String>read(test(onSecond, "shared-link").body(), "$.data.result"))
                    .isEqualTo("PASSED");
            assertThat(header(service, 0)).isEqualTo("Bearer canary-before");

            write(admin, "shared-token", "canary-after");
            aMomentLater();

            assertThat(JsonPath.<String>read(test(onSecond, "shared-link").body(), "$.data.result"))
                    .isEqualTo("PASSED");
            assertThat(header(service, 1)).isEqualTo("Bearer canary-after");
        }
    }
}
