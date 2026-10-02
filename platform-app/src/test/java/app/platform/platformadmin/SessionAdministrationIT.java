package app.platform.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Administrative sessions (Sprint 6, ADR-0036): a platform administrator or support looks at what a person holds and
 * signs them out everywhere. The listing shows when and where, never a token or a hash; an unknown address and a person
 * with nothing look the same; every use is audited with the address only as a hash.
 */
@PlatformIntegrationTest
class SessionAdministrationIT {

    private static final String LOOKUP = "/api/v1/platform/sessions/lookup";
    private static final String SIGN_OUT = "/api/v1/platform/sessions/sign-out";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private TestUser admin;
    private TestBrowser console;
    private TestBrowser support;

    @BeforeEach
    void setUp() {
        admin = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        console = TestPlatform.signedIn(port, admin);
        support = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT));
    }

    private static String body(String email, String reason) {
        return "{\"email\":\"" + email + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String shape(Response response) {
        Map<String, String> headers = new TreeMap<>();
        response.headers().forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (!lower.equals("date") && !lower.equals("x-request-id") && !lower.equals("x-trace-id")
                    && !lower.equals("set-cookie") && !lower.equals("content-length")) {
                headers.put(lower, String.join("|", values));
            }
        });
        return response.status() + " " + headers + " " + response.body().replaceAll("\"requestId\":\"[^\"]*\"", "-")
                .replaceAll("\"traceId\":\"[^\"]*\"", "-");
    }

    @Test
    void thePersonSessionsAreListedWithWhereAndWhenButNeverATokenOrAHash() {
        Organization organization = TestOrganizations.create(users);
        TestUser person = organization.admin().person();
        TestOrganizations.signedIn(port, organization.host(), person);
        TestPlatform.signedIn(port, person);

        Response seen = support.postJson(LOOKUP, body(person.email(), "customer reports a strange sign-in"));

        assertThat(seen.status()).isEqualTo(200);
        List<String> kinds = JsonPath.read(seen.body(), "$.data[*].kind");
        assertThat(kinds).as("the signed-in browsers (the short sign-in step is over once the sign-in is complete)")
                .contains("TOKENS");
        List<String> organizations = JsonPath.read(seen.body(), "$.data[*].organization");
        assertThat(organizations).contains(organization.tenant().slug());
        assertThat(JsonPath.<List<Object>>read(seen.body(), "$.data[?(!@.organization)]"))
                .as("the platform host sign-in has no organization").isNotEmpty();
        List<Map<String, Object>> entries = JsonPath.read(seen.body(), "$.data");
        assertThat(entries).allSatisfy(entry -> assertThat(entry.keySet())
                .isSubsetOf("kind", "organization", "started", "expires").contains("kind", "started", "expires"));
        assertThat(seen.body()).doesNotContain("access_token").doesNotContain("sha256:");
    }

    @Test
    void anUnknownAddressAndAPersonWithNothingGiveTheSameAnswerAndSigningThemOutIsSilent() {
        TestUser nothing = TestUsers.create(users);
        String unknown = "nobody-" + UUID.randomUUID() + "@example.test";

        Response noSessions = console.postJson(LOOKUP, body(nothing.email(), "check"));
        Response noAccount = console.postJson(LOOKUP, body(unknown, "check"));

        assertThat(noSessions.status()).isEqualTo(200);
        assertThat(shape(noAccount)).isEqualTo(shape(noSessions));
        Response signedOutUnknown = console.postJson(SIGN_OUT, body(unknown, "check"));
        Response signedOutKnown = console.postJson(SIGN_OUT, body(nothing.email(), "check"));
        assertThat(signedOutUnknown.status()).isEqualTo(204);
        assertThat(shape(signedOutKnown)).isEqualTo(shape(signedOutUnknown));
    }

    @Test
    void signingAPersonOutEverywhereEndsEveryHostAndIsAuditedWithoutTheAddress() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestUser person = organization.admin().person();
        TestBrowser inOrganization = TestOrganizations.signedIn(port, organization.host(), person);
        TestBrowser onPlatform = TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, person);
        assertThat(inOrganization.get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(onPlatform.get("/api/v1/auth/me").status()).isEqualTo(200);

        Response out = support.postJson(SIGN_OUT, body(person.email(), "account takeover suspected"));

        assertThat(out.status()).isEqualTo(204);
        assertThat(inOrganization.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(onPlatform.get("/api/v1/auth/me").status()).isEqualTo(401);
        Response afterwards = console.postJson(LOOKUP, body(person.email(), "check"));
        assertThat(JsonPath.<List<Object>>read(afterwards.body(), "$.data")).as("nothing live is left").isEmpty();
        assertThat(IdentityDb.auditOfType("platform.sessions.signed_out_everywhere")).anyMatch(record ->
                record.attributes().contains("identifier_hash") && !record.attributes().contains(person.email()));
        assertThat(IdentityDb.auditOfType("platform.sessions.listed")).anyMatch(record ->
                record.userId() != null && !record.attributes().contains(person.email()));
        assertThat(IdentityDb.entireAuditTableAsText()).as("the address is never written to the audit trail")
                .doesNotContain(person.email());
        // The person can sign in again: it was a sign-out, not a suspension.
        assertThat(TestOrganizations.signedIn(port, organization.host(), person).get("/api/v1/auth/me").status())
                .isEqualTo(200);
    }

    @Test
    void aBadAddressOrAMissingReasonIsRefusedBeforeAnythingHappens() {
        assertThat(console.postJson(LOOKUP, "{\"email\":\"\",\"reason\":\"x\"}").status()).isEqualTo(400);
        assertThat(console.postJson(LOOKUP, "{\"email\":\"person-a@example.test\"}").status()).isEqualTo(400);
        assertThat(console.postJson(SIGN_OUT, "{\"email\":\"person-a@example.test\",\"reason\":\"\"}").status())
                .isEqualTo(400);
        assertThat(console.postJson(LOOKUP, body("not an address", "x")).status()).as("the same empty answer")
                .isEqualTo(200);
        assertThat(admin).isNotNull();
    }
}
