package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who may do what with the records of an object (Sprint 14, ADR-0089): every endpoint is tried as an allowed member, as
 * members who hold some of the permissions on the object, as a member of another organization, with a forged tenant
 * header, as a platform administrator and without signing in. A member who may not even read the object gets the same
 * answer as for an object that does not exist.
 */
@PlatformIntegrationTest
class RecordsAccessIT extends RecordsSupport {

    private static final String FAKE = "018f0000-0000-7000-8000-000000000000";

    private record Call(String method, String path, String body) {
    }

    /** Every endpoint of the record store, with a body that is valid, against a record that does not exist. */
    private static final List<Call> EVERY_ENDPOINT = List.of(
            new Call("POST", RECORDS, "{\"fields\":{\"name__c\":\"x\"}}"),
            new Call("GET", RECORDS, null),
            new Call("GET", RECORDS + "/" + FAKE, null),
            new Call("PATCH", RECORDS + "/" + FAKE, "{\"version\":0,\"fields\":{\"name__c\":\"x\"}}"),
            new Call("DELETE", RECORDS + "/" + FAKE, null),
            new Call("GET", BIN, null),
            new Call("POST", BIN + "/" + FAKE + "/restore", "{}"),
            new Call("DELETE", BIN + "/" + FAKE, null));

    // ---- members who hold some of the permissions ----

    @Test
    void aMemberWhoMayNotReadTheObjectGetsTheAnswerOfAnObjectThatDoesNotExist() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        TestBrowser nobody = as(organization, memberWithActions(organization, admin));

        Response unknown = nobody.get("/api/v1/objects/Nothing__c/records");

        for (Call call : EVERY_ENDPOINT) {
            String path = call.path().replace(FAKE, id);
            Response answer = nobody.request(call.method(), path, call.body());
            assertThat(answer.status()).as(call + " " + answer.body()).isEqualTo(404);
            assertThat(JsonPath.<String>read(answer.body(), "$.error.message"))
                    .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
        }
        assertThat(admin.get(RECORDS + "/" + id).status()).as("and nothing was done").isEqualTo(200);
    }

    @Test
    void aMemberWhoMayOnlyReadReadsAndIsForbiddenEverythingElse() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        TestBrowser reader = as(organization, memberWithActions(organization, admin, "read"));

        assertThat(reader.get(RECORDS).status()).isEqualTo(200);
        assertThat(reader.get(RECORDS + "/" + id).status()).isEqualTo(200);
        assertThat(create(reader, "{\"name__c\":\"x\"}").status()).isEqualTo(403);
        assertThat(patch(reader, id, 0, "{\"name__c\":\"x\"}").status()).isEqualTo(403);
        assertThat(reader.request("DELETE", RECORDS + "/" + id, null).status()).isEqualTo(403);
        assertThat(reader.get(BIN).status()).isEqualTo(403);
        assertThat(reader.postJson(BIN + "/" + id + "/restore", "{}").status()).isEqualTo(403);
        assertThat(reader.request("DELETE", BIN + "/" + id, null).status()).isEqualTo(403);
        assertThat(errorCode(create(reader, "{}"))).isEqualTo("FORBIDDEN");
        Response untouched = admin.get(RECORDS + "/" + id);
        assertThat(versionOf(untouched)).isZero();
        assertThat(JsonPath.<String>read(untouched.body(), "$.data.fields.name__c")).isEqualTo("Person A");
    }

    @Test
    void createAloneAllowsCreatingAndReadingButNotChangingOrDeleting() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        TestBrowser creator = as(organization, memberWithActions(organization, admin, "create"));

        Response created = create(creator, "{\"name__c\":\"x\"}");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(creator.get(RECORDS + "/" + idOf(created)).status()).as("create implies read").isEqualTo(200);
        assertThat(patch(creator, idOf(created), 0, "{\"name__c\":\"y\"}").status()).isEqualTo(403);
        assertThat(creator.request("DELETE", RECORDS + "/" + idOf(created), null).status()).isEqualTo(403);
    }

    @Test
    void updateAloneAllowsChangingButNotCreatingOrDeleting() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        TestBrowser editor = as(organization, memberWithActions(organization, admin, "update"));

        Response changed = patch(editor, id, 0, "{\"name__c\":\"Person B\"}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(create(editor, "{}").status()).isEqualTo(403);
        assertThat(editor.request("DELETE", RECORDS + "/" + id, null).status()).isEqualTo(403);
    }

    @Test
    void deleteAloneAllowsDeletingTheRecycleBinRestoringAndRemovingForGood() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        TestBrowser remover = as(organization, memberWithActions(organization, admin, "delete"));

        assertThat(remover.request("DELETE", RECORDS + "/" + id, null).status()).isEqualTo(204);
        assertThat(JsonPath.<List<String>>read(remover.get(BIN).body(), "$.data[*].id")).containsExactly(id);
        assertThat(remover.postJson(BIN + "/" + id + "/restore", "{}").status()).isEqualTo(200);
        remover.request("DELETE", RECORDS + "/" + id, null);
        assertThat(remover.request("DELETE", BIN + "/" + id, null).status()).isEqualTo(204);
        assertThat(create(remover, "{}").status()).isEqualTo(403);
    }

    @Test
    void aChangeOfPermissionsTakesEffectAtTheNextQuestion() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        Holder holder = holderWithActions(organization, admin, "read", "update");
        TestBrowser member = as(organization, holder.member());
        assertThat(patch(member, id, 0, "{\"name__c\":\"x\"}").status()).isEqualTo(200);

        String profile = holder.profileId();
        Response taken = admin.request("PUT", "/api/v1/profiles/" + profile + "/data-access",
                "{\"objects\":[{\"key\":\"Employee__c\",\"actions\":[\"read\"]}],\"fields\":[]}");

        assertThat(taken.status()).as(taken.body()).isEqualTo(200);
        assertThat(patch(member, id, 1, "{\"name__c\":\"y\"}").status()).isEqualTo(403);
        assertThat(member.get(RECORDS + "/" + id).status()).isEqualTo(200);
    }

    // ---- another organization, a forged header ----

    @Test
    void anotherOrganizationNeverReadsChangesRestoresOrRemovesARecord() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithEmployee(mine);
        TestBrowser other = adminWithEmployee(theirs);
        String id = newRecord(admin, "Person A");
        String binned = newRecord(admin, "Person B");
        admin.request("DELETE", RECORDS + "/" + binned, null);

        assertThat(other.get(RECORDS + "/" + id).status()).isEqualTo(404);
        assertThat(patch(other, id, 0, "{\"name__c\":\"hijacked\"}").status()).isEqualTo(404);
        assertThat(other.request("DELETE", RECORDS + "/" + id, null).status()).isEqualTo(404);
        assertThat(other.postJson(BIN + "/" + binned + "/restore", "{}").status()).isEqualTo(404);
        assertThat(other.request("DELETE", BIN + "/" + binned, null).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(other.get(RECORDS).body(), "$.data[*].id")).isEmpty();
        assertThat(JsonPath.<List<String>>read(other.get(BIN).body(), "$.data[*].id")).isEmpty();

        Response read = admin.get(RECORDS + "/" + id);
        assertThat(versionOf(read)).as("nothing of mine was changed").isZero();
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields.name__c")).isEqualTo("Person A");
        assertThat(JsonPath.<List<String>>read(admin.get(BIN).body(), "$.data[*].id")).containsExactly(binned);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithEmployee(mine);
        TestBrowser other = adminWithEmployee(theirs);
        String secret = newRecord(other, "Secret");
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response listed = admin.get(RECORDS, forged);
        Response read = admin.get(RECORDS + "/" + secret, forged);
        Response created = admin.request("POST", RECORDS, body("{\"name__c\":\"Forged\"}"), forged);

        assertThat(listed.body()).doesNotContain(secret).doesNotContain("Secret");
        assertThat(read.status()).isEqualTo(404);
        assertThat(created.status()).isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(admin.get(RECORDS).body(), "$.data[*].id")).contains(idOf(created));
        assertThat(JsonPath.<List<String>>read(other.get(RECORDS).body(), "$.data[*].id"))
                .containsExactly(secret);
    }

    // ---- platform administrator, unauthenticated ----

    @Test
    void aPlatformAdministratorIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        adminWithEmployee(organization);
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : EVERY_ENDPOINT) {
            Response refused = platformAdmin.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(404);
        }
    }

    @Test
    void anUnauthenticatedCallerIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        adminWithEmployee(organization);
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : EVERY_ENDPOINT) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
    }

    @Test
    void aWriteWithoutTheRequestForgeryHeaderIsRefused() {
        TestBrowser admin = adminWithEmployee(organization());

        Response refused = admin.postJsonWithoutCsrfHeader(RECORDS, body("{\"name__c\":\"x\"}"));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(JsonPath.<List<String>>read(admin.get(RECORDS).body(), "$.data[*].id")).isEmpty();
    }
}
