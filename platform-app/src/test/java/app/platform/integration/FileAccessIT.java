package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestFileServer;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestOrganizations.Member;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Who may read how the files of a connection stand (Sprint 29A, ADR-0242). No endpoint is new: the facts about files
 * are a part of the health of a connection, so the rules of that endpoint hold for them. This proves it for the new
 * part: allowed (an administrator and a member who may see integrations), denied (a member who may not), another
 * organization (the connection does not exist for it), a forged tenant header, and no sign-in. A connection that picks
 * up no files has no such part.
 */
@PlatformIntegrationTest
class FileAccessIT extends IntegrationSupport {

    private static Map<String, Object> fileConnection(String name, boolean pulls) {
        Map<String, Object> body = definition(name, "sftp://files.example.com");
        body.put("connectorType", "file-transfer");
        body.put("authKind", "BASIC");
        body.put("credentialName", "file-login");
        body.put("settings", pulls
                ? Map.of("hostKey", TestFileServer.strangerFingerprint(), "inboundEntity", "item")
                : Map.of("hostKey", TestFileServer.strangerFingerprint()));
        return body;
    }

    private String made(Organization organization, String name, boolean pulls) {
        TestBrowser admin = adminOf(organization);
        admin.request("PUT", "/api/v1/credentials/file-login", json(Map.of("value", "user-a:password-a")));
        Response created = create(admin, fileConnection(name, pulls));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return name;
    }

    @Test
    void anAdministratorAndAMemberWhoMaySeeIntegrationsReadTheFilesOfAConnectionThatPicksThemUp() {
        Organization organization = organization();
        made(organization, "files-in", true);
        made(organization, "files-out", false);
        TestBrowser admin = adminOf(organization);
        Member viewer = memberWithAbilities(organization, admin, "integrations.view");

        for (TestBrowser reader : new TestBrowser[] {admin, as(organization, viewer)}) {
            Response health = reader.get(ROOT + "/files-in/health");
            assertThat(health.status()).as(health.body()).isEqualTo(200);
            assertThat(JsonPath.<Number>read(health.body(), "$.data.files.filesWaiting").intValue()).isZero();
            assertThat(JsonPath.<Number>read(health.body(), "$.data.files.filesSetAside").intValue()).isZero();
            assertThat(health.body()).doesNotContain("sftp://").doesNotContain("files.example.com");

            Response none = reader.get(ROOT + "/files-out/health");
            assertThat(none.status()).as(none.body()).isEqualTo(200);
            assertThat(none.body()).as("no files to speak of").doesNotContain("\"files\":");
        }
    }

    @Test
    void aMemberWhoMayNotSeeIntegrationsAnotherOrganizationAndAForgedHostAreRefused() {
        Organization organization = organization();
        made(organization, "files-in", true);
        TestBrowser admin = adminOf(organization);
        Member nobody = memberWithAbilities(organization, admin, "mappings.view");
        Organization other = organization();
        TestBrowser stranger = adminOf(other);

        assertThat(as(organization, nobody).get(ROOT + "/files-in/health").status()).isEqualTo(403);
        Response foreign = stranger.get(ROOT + "/files-in/health");
        assertThat(foreign.status()).as("not found, as if it did not exist").isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("filesWaiting");
        Response forged = stranger.request("GET", ROOT + "/files-in/health", "", "X-Tenant-Id",
                organization.id().toString(), "X-Forwarded-Host", organization.host());
        assertThat(forged.status()).as("a forged tenant changes nothing").isEqualTo(404);
    }

    @Test
    void nobodyWhoHasNotSignedInReadsThem() {
        Organization organization = organization();
        made(organization, "files-in", true);

        Response anonymous = new TestBrowser(port, organization.host()).get(ROOT + "/files-in/health");

        assertThat(anonymous.status()).isEqualTo(401);
    }
}
