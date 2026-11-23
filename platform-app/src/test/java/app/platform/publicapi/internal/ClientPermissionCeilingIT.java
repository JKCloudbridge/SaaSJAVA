package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Scope is necessary, never sufficient (Sprint 31, ADR-0267, ADR-0270): whatever scopes and objects an application is
 * given, the member behind it decides every object, field and record. For members with different rights an application
 * with every scope returns the same records and the same refusals as that member through the public door; scopes and
 * the object list only narrow; and a change of the member's rights shows at the very next request, because nothing of
 * them is kept in the token.
 */
@PlatformIntegrationTest
class ClientPermissionCeilingIT extends ClientSupport {

    /** The status and the error code of an answer: what must be the same for the person and the application. */
    private record Outcome(int status, String code) {

        static Outcome of(Response response) {
            String code = response.status() >= 400 ? JsonPath.read(response.body(), "$.error.code") : "";
            return new Outcome(response.status(), code);
        }
    }

    private static List<Outcome> sameCallsAs(Caller caller, String existing) {
        return List.of(
                Outcome.of(caller.get(OBJECTS)),
                Outcome.of(caller.get(EMPLOYEES_API)),
                Outcome.of(caller.post(EMPLOYEE_RECORDS + "/query", "{}")),
                Outcome.of(caller.get(EMPLOYEE_RECORDS + "/" + existing)),
                Outcome.of(caller.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Ceiling\"}"))),
                Outcome.of(caller.patch(EMPLOYEE_RECORDS + "/" + existing, "{\"fields\":{\"department__c\":\"X\"}}",
                        "If-Match", "\"9999\"")),
                Outcome.of(caller.delete(EMPLOYEE_RECORDS + "/" + UUID.randomUUID())),
                Outcome.of(caller.get(OBJECTS + "/Nothing__c")));
    }

    @Test
    void forMembersWithDifferentRightsAnApplicationWithEveryScopeGetsExactlyTheAnswersOfTheMember() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        String existing = newEmployee(administrator(organization), "Existing", "Sales");
        List<String[]> rights = List.of(new String[] {}, new String[] {"read"}, new String[] {"read", "create"},
                new String[] {"read", "update"}, new String[] {"read", "delete"},
                new String[] {"read", "create", "update", "delete"});

        for (String[] actions : rights) {
            Integrator who = integrationMember(organization, admin, actions);
            TestBrowser person = as(organization, who.member());
            Registered app = register(person, ALL_SCOPES);
            Caller member = caller(organization.host(), person);
            Caller application = clientCaller(organization, app);

            assertThat(sameCallsAs(application, existing)).as("rights " + List.of(actions))
                    .isEqualTo(sameCallsAs(member, existing));
        }
    }

    @Test
    void anApplicationSeesTheSameRecordsAndTheSameFieldsAsItsMember() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Caller administrator = administrator(organization);
        String id = newEmployee(administrator, "Visible", "Secretive");
        String matrix = "{\"objects\":[{\"key\":\"Employee__c\",\"actions\":[\"read\"]}],\"fields\":["
                + "{\"key\":\"Employee__c.name__c\",\"actions\":[\"read\"]}]}";
        Integrator who = memberWithAbilitiesAndMatrix(organization, admin, matrix, "clients.view", "clients.manage");
        TestBrowser person = as(organization, who.member());
        Registered app = register(person, ALL_SCOPES);
        Caller application = clientCaller(organization, app);
        Caller member = caller(organization.host(), person);

        Response byApplication = application.get(EMPLOYEE_RECORDS + "/" + id);
        Response byMember = member.get(EMPLOYEE_RECORDS + "/" + id);

        assertThat(byApplication.status()).isEqualTo(200);
        assertThat(JsonPath.<java.util.Map<String, Object>>read(byApplication.body(), "$.data.fields").keySet())
                .isEqualTo(JsonPath.<java.util.Map<String, Object>>read(byMember.body(), "$.data.fields").keySet())
                .containsExactly("name__c");
        assertThat(byApplication.body()).doesNotContain("Secretive");
        assertThat(JsonPath.<List<String>>read(application.post(EMPLOYEE_RECORDS + "/query", "{}").body(),
                "$.data[*].id")).isEqualTo(JsonPath.<List<String>>read(member.post(EMPLOYEE_RECORDS + "/query",
                        "{}").body(), "$.data[*].id"));
    }

    @Test
    void aScopeOnlyNarrowsAnApplicationWithoutTheWriteScopeCannotWriteWhateverItsMemberMay() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), "[\"objects.read\",\"records.read\"]");
        Caller application = clientCaller(organization, app);
        String id = newEmployee(administrator(organization), "Existing", "Sales");

        assertThat(application.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"x\"}")).status()).isEqualTo(403);
        assertThat(application.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"name__c\":\"y\"}}", "If-Match",
                "\"0\"").status()).isEqualTo(403);
        assertThat(application.delete(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(403);
        assertThat(application.post(EMPLOYEE_RECORDS + "/" + id + "/restore", "").status()).isEqualTo(403);
        assertThat(application.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"records\":[]}")
                .status()).isEqualTo(403);
        assertThat(application.get(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(200);
        assertThat(administrator(organization).get(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(200);
    }

    @Test
    void anApplicationThatMayOnlyWriteCannotReadAndTheWriteScopeDoesNotIncludeReading() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), "[\"records.write\"]");
        Caller application = clientCaller(organization, app);
        String id = newEmployee(administrator(organization), "Existing", "Sales");

        assertThat(application.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Written\"}")).status())
                .isEqualTo(201);
        assertThat(application.get(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(403);
        assertThat(application.post(EMPLOYEE_RECORDS + "/query", "{}").status()).isEqualTo(403);
        assertThat(application.get(OBJECTS).status()).isEqualTo(403);
    }

    @Test
    void anObjectOutsideTheListAnswersExactlyLikeAnObjectThatDoesNotExist() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, "app-listed", ALL_SCOPES, "[\"Other__c\"]");
        Caller application = clientCaller(organization, app);
        String id = newEmployee(administrator(organization), "Existing", "Sales");

        Response outside = application.get(EMPLOYEES_API);
        Response unknown = administrator(organization).get(OBJECTS + "/Nothing__c");

        assertThat(outside.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(outside.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
        assertThat(code(outside)).isEqualTo(code(unknown));
        assertThat(application.get(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(404);
        assertThat(application.post(EMPLOYEE_RECORDS + "/query", "{}").status()).isEqualTo(404);
        assertThat(application.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"x\"}")).status()).isEqualTo(404);
        assertThat(application.delete(EMPLOYEE_RECORDS + "/" + id).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(application.get(OBJECTS).body(), "$.data[*].apiName"))
                .as("the list names only what the application may use").doesNotContain("Employee__c");
        assertThat(administrator(organization).get(EMPLOYEE_RECORDS + "/" + id).status())
                .as("the member itself still reaches it").isEqualTo(200);
    }

    @Test
    void anApplicationLimitedToAnObjectUsesThatObjectAndNothingBeyondWhatItsMemberMay() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), "app-listed", ALL_SCOPES, "[\"Employee__c\"]");
        Caller application = clientCaller(organization, app);

        assertThat(application.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Listed\"}")).status())
                .isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(application.get(OBJECTS).body(), "$.data[*].apiName"))
                .containsExactly("Employee__c");
    }

    @Test
    void aMissingScopeIsForbiddenBeforeTheObjectListIsAskedSoTheTwoAnswersStayApart() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), "app-read", "[\"records.read\"]", "[\"Employee__c\"]");
        Caller application = clientCaller(organization, app);

        assertThat(application.post(OBJECTS + "/Other__c/records", createBody("{}")).status())
                .as("the scope is missing: forbidden, whatever the object").isEqualTo(403);
        assertThat(application.post(OBJECTS + "/Other__c/records/query", "{}").status())
                .as("the scope is held and the object is outside the list: not found").isEqualTo(404);
    }

    @Test
    void aChangeOfTheMembersRightsShowsAtTheVeryNextRequestBecauseNothingOfThemIsInTheToken() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        String id = newEmployee(administrator(organization), "Existing", "Sales");
        Integrator who = integrationMember(organization, admin, "read", "update");
        TestBrowser person = as(organization, who.member());
        Registered app = register(person, ALL_SCOPES);
        Caller application = clientCaller(organization, app);
        Response before = application.get(EMPLOYEE_RECORDS + "/" + id);
        assertThat(application.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"department__c\":\"A\"}}",
                "If-Match", etag(before)).status()).isEqualTo(200);

        setMatrix(admin, who, employeeMatrix("read"));

        Response reread = application.get(EMPLOYEE_RECORDS + "/" + id);
        assertThat(application.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"department__c\":\"B\"}}",
                "If-Match", etag(reread)).status()).as("the same token, fewer rights").isEqualTo(403);

        setMatrix(admin, who, employeeMatrix());

        assertThat(application.get(EMPLOYEE_RECORDS + "/" + id).status()).as("and no rights at all").isEqualTo(404);
    }
}
