package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestSignIn;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The token of a connected application reaches the public API and nothing else (Sprint 31, ADR-0271), proved by
 * walking every mapped endpoint of the internal interface: without this rule the token would be a full session of the
 * member it acts as, and an application could register, change or remove applications. It is also bound to the host of
 * its organization: another host, the platform host and forged tenant headers change nothing.
 */
@PlatformIntegrationTest
class ClientTokenBoundaryIT extends ClientSupport {

    @Autowired
    private RequestMappingHandlerMapping mappings;

    private record Endpoint(String method, String path) {

        String key() {
            return method + " " + path;
        }
    }

    private List<Endpoint> internalEndpoints() {
        Set<String> seen = new TreeSet<>();
        List<Endpoint> found = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (var pattern : info.getPathPatternsCondition().getPatterns()) {
                String path = pattern.getPatternString().replaceAll("\\{[^}]+}", "x");
                if (!path.startsWith("/api/v1/")) {
                    continue;
                }
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    methods = Set.of(RequestMethod.GET);
                }
                for (RequestMethod method : methods) {
                    Endpoint endpoint = new Endpoint(method.name(), path);
                    if (seen.add(endpoint.key())) {
                        found.add(endpoint);
                    }
                }
            }
        }
        return found;
    }

    private Caller application(Organization organization) {
        return clientCaller(organization, register(adminOf(organization), ALL_SCOPES));
    }

    @Test
    void everyMappedEndpointOfTheInternalInterfaceRefusesTheTokenOfAnApplication() {
        Organization organization = organizationWithClients();
        Caller program = application(organization);
        List<Endpoint> all = internalEndpoints();
        assertThat(all).as("the walk found the endpoints").hasSizeGreaterThan(100);

        List<String> reached = new ArrayList<>();
        for (Endpoint endpoint : all) {
            Response answer = program.request(endpoint.method(), endpoint.path(), "{}");
            if (answer.status() != 403) {
                reached.add(endpoint.key() + " answered " + answer.status());
            }
        }

        assertThat(reached).as("an application's token is refused with 403 everywhere but the public API").isEmpty();
    }

    @Test
    void theSamePersonsOwnTokenIsNotRefusedAtTheSameAddressesSoTheRuleIsAboutTheApplication() {
        Organization organization = organizationWithClients();
        Caller person = administrator(organization);

        assertThat(person.get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(person.get(CLIENTS).status()).isEqualTo(200);
        assertThat(person.get("/api/v1/members").status()).isEqualTo(200);
    }

    @Test
    void anApplicationCannotRegisterChangeOrRemoveApplicationsOrSeeTheirSecrets() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Registered other = register(admin, ALL_SCOPES);
        Caller program = clientCaller(organization, app);

        assertThat(program.post(CLIENTS, registerBody("by-software", ALL_SCOPES, null, null)).status()).isEqualTo(403);
        assertThat(program.get(CLIENTS).status()).isEqualTo(403);
        assertThat(program.get(CLIENTS + "/" + other.id()).status()).isEqualTo(403);
        assertThat(program.post(CLIENTS + "/" + other.id() + "/secrets/rotate", "{}").status()).isEqualTo(403);
        assertThat(program.post(CLIENTS + "/" + other.id() + "/disable", "{}").status()).isEqualTo(403);
        assertThat(program.delete(CLIENTS + "/" + other.id()).status()).isEqualTo(403);

        assertThat(JsonPath.<List<?>>read(admin.get(CLIENTS).body(), "$.data")).hasSize(2);
        assertThat(JsonPath.<String>read(read(admin, other.id()).body(), "$.data.app.state")).isEqualTo("ACTIVE");
        assertThat(tokenRequest(organization.host(), other.clientId(), other.secret(), null).status())
                .as("the other application's secret was not rotated").isEqualTo(200);
    }

    @Test
    void anApplicationCannotAskForTheTokenOfAScriptOrAnotherSignInOrChangeAPassword() {
        Organization organization = organizationWithClients();
        Caller program = application(organization);

        assertThat(program.post("/api/v1/embedded-tokens", "{}").status()).isEqualTo(403);
        assertThat(program.post("/api/v1/auth/sign-out-all", "{}").status()).isEqualTo(403);
        assertThat(program.post("/api/v1/auth/password", "{}").status()).isEqualTo(403);
        assertThat(program.get("/api/v1/auth/me").status()).isEqualTo(403);
    }

    @Test
    void theTokenAnApplicationHoldsDoesNotWorkOnThePlatformHostOrOnAnotherOrganizationsHost() {
        Organization mine = organizationWithClients();
        Organization other = organizationWithClients();
        Caller program = application(mine);

        Response onPlatformHost = program.onHost(TestSignIn.PLATFORM_HOST).get(OBJECTS);
        Response onOtherHost = program.onHost(other.host()).get(OBJECTS);

        assertThat(onPlatformHost.status()).isIn(401, 403, 404);
        assertThat(onOtherHost.status()).isEqualTo(401);
        assertThat(program.onHost(other.host()).get("/api/v1/members").status()).isEqualTo(401);
        assertThat(program.get(OBJECTS).status()).isEqualTo(200);
    }

    @Test
    void forgedTenantHeadersChangeNothingForAnApplication() {
        Organization mine = organizationWithClients();
        Organization other = organizationWithClients();
        newEmployee(administrator(other), "Theirs", "Sales");
        Caller program = application(mine);
        Caller forged = program.withHeaders("X-Tenant-Id", other.id().value().toString(),
                "X-Forwarded-Host", other.host(), "X-Organization", other.tenant().slug());

        Response objects = forged.get(OBJECTS);
        Response records = forged.post(EMPLOYEE_RECORDS + "/query", "{}");

        assertThat(objects.status()).isEqualTo(200);
        assertThat(records.status()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(records.body(), "$.data")).as("none of the other organization's records")
                .isEmpty();
        assertThat(forged.get("/api/v1/members").status()).isEqualTo(403);
    }

    @Test
    void theTokenEndpointOfAnotherOrganizationDoesNotKnowTheApplication() {
        Organization mine = organizationWithClients();
        Organization other = organizationWithClients();
        Registered app = register(adminOf(mine), ALL_SCOPES);

        assertInvalidClient(tokenRequest(other.host(), app.clientId(), app.secret(), null));
        assertInvalidClient(tokenRequest(TestSignIn.PLATFORM_HOST, app.clientId(), app.secret(), null));
    }

    @Test
    void theTokenOfAnApplicationIsNotAcceptedWhenSentOnlyAsACookie() {
        Organization organization = organizationWithClients();
        Caller program = application(organization);

        Response viaCookie = program.withToken(null).get(OBJECTS, "Cookie", "platform_at=" + program.token());

        assertThat(viaCookie.status()).as("the public interface takes a header and nothing else").isEqualTo(401);
    }
}
