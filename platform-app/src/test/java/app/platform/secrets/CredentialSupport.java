package app.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.IntegrationSupport;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.Map;

/**
 * What the tests of the credential store share (Sprint 27): the short ways to call the credential endpoints, a
 * connection definition that signs in with a credential, and a count of rows as the application role under one
 * organization. Values and names in these tests are made up.
 */
public abstract class CredentialSupport extends IntegrationSupport {

    protected static final String CREDENTIALS = "/api/v1/credentials";

    protected static Response write(TestBrowser browser, String name, String value) {
        return browser.request("PUT", CREDENTIALS + "/" + name, json(Map.of("value", value)));
    }

    protected static Response credentials(TestBrowser browser) {
        return browser.get(CREDENTIALS);
    }

    protected static Response rollBack(TestBrowser browser, String name) {
        return browser.postJson(CREDENTIALS + "/" + name + "/rollback", "{}");
    }

    protected static Response removeCredential(TestBrowser browser, String name) {
        return browser.request("DELETE", CREDENTIALS + "/" + name, "");
    }

    /** A connection to the service that signs in with a bearer token held in the credential. */
    protected static Map<String, Object> signedIn(String name, String url, String credential) {
        Map<String, Object> body = definition(name, url);
        body.put("authKind", "BEARER_TOKEN");
        body.put("credentialName", credential);
        body.put("settings", Map.of("healthPath", "/health"));
        return body;
    }

    protected static int versionOfCredential(TestBrowser browser, String name) {
        Response list = credentials(browser);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.<java.util.List<Integer>>read(list.body(), "$.data[?(@.name=='" + name + "')].version").get(0);
    }

    /** How many rows a statement counts, as the application role working for the organization. */
    protected static long countRows(TenantId tenant, String sql, Object... parameters) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection, sql, parameters));
    }

    protected static void aMomentLater() throws InterruptedException {
        Thread.sleep(1200); // the test profile allows one test of a connection per second
    }
}
