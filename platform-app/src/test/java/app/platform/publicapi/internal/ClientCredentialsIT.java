package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The exit test of Sprint 31 (ADR-0267 to ADR-0270): an administrator registers a connected application and reads its
 * secret once, the application asks the token endpoint for a token with that secret and calls every operation of the
 * public API, the audit trail names the application next to the member, and after a rotation the old secret fails at
 * once. The token carries scopes, is short-lived, and has no refresh token.
 */
@PlatformIntegrationTest
class ClientCredentialsIT extends ClientSupport {

    @Test
    void anApplicationIsRegisteredReadsItsSecretOnceAndThenUsesEveryOperationOfThePublicApi() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);

        Registered app = register(admin, "exit-app", ALL_SCOPES, null);
        Response token = tokenRequest(organization.host(), app.clientId(), app.secret(), null);

        assertThat(app.secret()).startsWith("cs_");
        assertThat(app.clientId()).matches("ca_[A-Za-z0-9]{24}");
        assertThat(token.status()).as(token.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(token.body(), "$.token_type")).isEqualToIgnoringCase("Bearer");
        assertThat(JsonPath.<Number>read(token.body(), "$.expires_in").intValue()).isBetween(800, 900);
        assertThat(token.body()).as("no refresh token for a machine").doesNotContain("refresh_token");
        assertThat(JsonPath.<String>read(token.body(), "$.scope")).contains("objects.read").contains("records.read")
                .contains("records.write");

        Caller program = clientCaller(organization, app);
        assertThat(program.get(API + "/openapi").status()).isEqualTo(200);
        assertThat(program.get(OBJECTS).status()).as("listObjects").isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(program.get(OBJECTS).body(), "$.data[*].apiName"))
                .contains("Employee__c");
        assertThat(program.get(EMPLOYEES_API).status()).as("describeObject").isEqualTo(200);
        Response created = program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Made by software\","
                + "\"department__c\":\"Sales\"}"));
        assertThat(created.status()).as("createRecord " + created.body()).isEqualTo(201);
        String id = id(created);
        assertThat(program.get(EMPLOYEE_RECORDS + "/" + id).status()).as("getRecord").isEqualTo(200);
        assertThat(program.post(EMPLOYEE_RECORDS + "/query", "{}").status()).as("queryRecords").isEqualTo(200);
        Response changed = program.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"department__c\":\"Support\"}}",
                "If-Match", etag(created));
        assertThat(changed.status()).as("updateRecord " + changed.body()).isEqualTo(200);
        Response bulk = program.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"records\":["
                + "{\"fields\":{\"name__c\":\"Bulk by software\"}}]}");
        assertThat(bulk.status()).as("bulkRecords " + bulk.body()).isEqualTo(200);
        assertThat(program.delete(EMPLOYEE_RECORDS + "/" + id).status()).as("deleteRecord").isEqualTo(204);
        assertThat(program.post(EMPLOYEE_RECORDS + "/" + id + "/restore", "").status()).as("restoreRecord")
                .isEqualTo(200);
    }

    @Test
    void theSecretIsInTheAnswerThatMadeItAndNowhereElse() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);

        Registered app = register(admin, ALL_SCOPES);

        assertThat(read(admin, app.id()).body()).doesNotContain(app.secret());
        assertThat(admin.get(CLIENTS).body()).doesNotContain(app.secret());
        assertThat(app.response().header("Cache-Control").orElse("")).contains("no-store");
        assertThat(JsonPath.<List<?>>read(read(admin, app.id()).body(), "$.data.secrets")).hasSize(1);
        assertThat(read(admin, app.id()).body()).doesNotContain("sha256").doesNotContain("secretHash")
                .doesNotContain("clientSecret");
    }

    @Test
    void theSecretMayBeSentInTheFormBodyAsWellAsInTheHeader() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);

        Response token = tokenRequestInBody(organization.host(), app.clientId(), app.secret());

        assertThat(token.status()).as(token.body()).isEqualTo(200);
    }

    @Test
    void anApplicationActsAsTheMemberWhoRegisteredItAndTheListSaysSo() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);

        Response detail = read(admin, app.id());

        assertThat(JsonPath.<Boolean>read(detail.body(), "$.data.app.actsAsYou")).isTrue();
        assertThat(JsonPath.<Boolean>read(detail.body(), "$.data.app.running")).isTrue();
        assertThat(JsonPath.<String>read(detail.body(), "$.data.app.state")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Integer>read(detail.body(), "$.data.app.tokenMinutes")).isEqualTo(15);
        assertThat(JsonPath.<Integer>read(detail.body(), "$.data.app.activeSecrets")).isEqualTo(1);
    }

    @Test
    void theAuditTrailNamesTheApplicationNextToTheMemberForWhatItChanged() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        Caller program = clientCaller(organization, app);

        Response created = program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Audited\"}"));
        String id = id(created);
        Response byPerson = administrator(organization).post(EMPLOYEE_RECORDS,
                createBody("{\"name__c\":\"By person\"}"));

        List<String> viaClient = owner("select attributes ->> 'via_client' from audit_record where record_id = ? "
                + "and attributes ->> 'via_client' is not null", id);
        assertThat(viaClient).as("the change made through the application names it").isNotEmpty()
                .containsOnly(app.clientId());
        assertThat(owner("select actor_user_id::text from audit_record where record_id = ? "
                + "and attributes ->> 'via_client' is not null", id)).as("and the member is still the actor")
                .containsOnly(organization.admin().person().user().id().toString());
        assertThat(owner("select count(*) from audit_record where record_id = ? and "
                + "attributes ->> 'via_client' is not null", id(byPerson))).as("a person's own change names no client")
                .containsExactly("0");
    }

    @Test
    void registeringAndRotatingAreAuditedByIdentifierAndNeverBySecret() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, "audited-app", ALL_SCOPES, null);
        Response rotation = rotate(admin, app.id(), 0, false);
        String second = newSecretOf(rotation);

        List<String> trail = owner("select event_type || ' ' || attributes::text from audit_record "
                + "where event_type like 'clients.%' and attributes ->> 'client' = ?", app.clientId());

        assertThat(trail).anyMatch(line -> line.startsWith("clients.created "));
        assertThat(trail).anyMatch(line -> line.startsWith("clients.secret_created "));
        assertThat(trail).anyMatch(line -> line.startsWith("clients.secret_rotated "));
        trail.forEach(line -> assertThat(line).doesNotContain(app.secret()).doesNotContain(second)
                .doesNotContain("audited-app").doesNotContain("for tests"));
    }

    @Test
    void afterARotationTheOldSecretFailsAtOnceAndTheNewOneWorks() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        assertThat(tokenRequest(organization.host(), app.clientId(), app.secret(), null).status()).isEqualTo(200);

        Response rotation = rotate(admin, app.id(), 0, false);
        String fresh = newSecretOf(rotation);

        assertInvalidClient(tokenRequest(organization.host(), app.clientId(), app.secret(), null));
        assertThat(tokenRequest(organization.host(), app.clientId(), fresh, null).status()).isEqualTo(200);
        assertThat(fresh).isNotEqualTo(app.secret());
    }

    @Test
    void aTokenCarriesOnlyTheScopesAskedForAndTheOnesThePublicApiNeeds() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);

        Caller readOnly = clientCaller(organization, app, "records.read");

        assertThat(readOnly.post(EMPLOYEE_RECORDS + "/query", "{}").status()).isEqualTo(200);
        assertThat(readOnly.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"x\"}")).status()).isEqualTo(403);
        assertThat(readOnly.get(OBJECTS).status()).as("records.read does not include objects.read").isEqualTo(403);
    }

    @Test
    void aScopeTheApplicationDoesNotHoldIsInvalidScope() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), "[\"records.read\"]");

        Response token = tokenRequest(organization.host(), app.clientId(), app.secret(), "records.write");

        assertThat(token.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(token.body(), "$.error")).isEqualTo("invalid_scope");
    }

    @Test
    void anAdministratorMayGiveAShorterTokenLifeWithinTheLimits() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);

        Response created = admin.postJson(CLIENTS, registerBody("short-life", ALL_SCOPES, null, 5));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        Response token = tokenRequest(organization.host(), JsonPath.read(created.body(), "$.data.detail.app.clientId"),
                JsonPath.read(created.body(), "$.data.clientSecret"), null);

        assertThat(JsonPath.<Number>read(token.body(), "$.expires_in").intValue()).isLessThanOrEqualTo(300);
        assertThat(admin.postJson(CLIENTS, registerBody("too-short", ALL_SCOPES, null, 1)).status()).isEqualTo(400);
        assertThat(admin.postJson(CLIENTS, registerBody("too-long", ALL_SCOPES, null, 61)).status()).isEqualTo(400);
    }
}
