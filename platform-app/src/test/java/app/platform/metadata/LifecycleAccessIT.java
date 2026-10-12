package app.platform.metadata;

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
 * Who may do what with relationships, record types, change sets and releases (Sprint 11, ADR-0068): the usual tests of
 * every endpoint (allowed, denied, cross-tenant, forged tenant header, platform administrator refused, unauthenticated)
 * and the split between viewing, drafting (manage) and publishing.
 */
@PlatformIntegrationTest
class LifecycleAccessIT extends LifecycleSupport {

    /** One call to an endpoint. */
    private record Call(String method, String path, String body) {
    }

    private static final String RT = "{\"name\":\"Odd\",\"label\":\"Odd\"}";
    private static final String CHANGE = change("CREATE_OBJECT", "Thing__c", null, "createObject",
            createObjectRequest("Thing"));

    /** Every endpoint of the sprint, for a change set with this id holding a change with that id. */
    private static List<Call> everyEndpoint(String set, String changeId) {
        return List.of(
                new Call("GET", SETS, null),
                new Call("POST", SETS, "{\"name\":\"another\",\"description\":\"\"}"),
                new Call("GET", SETS + "/" + set, null),
                new Call("DELETE", SETS + "/" + set, null),
                new Call("POST", SETS + "/" + set + "/changes", CHANGE),
                new Call("DELETE", SETS + "/" + set + "/changes/" + changeId, null),
                new Call("POST", SETS + "/" + set + "/validate", "{}"),
                new Call("POST", SETS + "/" + set + "/preview", "{}"),
                new Call("POST", SETS + "/" + set + "/publish", "{}"),
                new Call("GET", RELEASES, null),
                new Call("POST", RELEASES + "/latest/rollback-check", "{}"),
                new Call("POST", RELEASES + "/latest/rollback", "{}"),
                new Call("GET", OBJECTS + "/Gadget__c/relationships", null),
                new Call("GET", OBJECTS + "/Gadget__c/record-types", null),
                new Call("POST", OBJECTS + "/Gadget__c/record-types", RT),
                new Call("GET", OBJECTS + "/Gadget__c/record-types/Odd__c", null),
                new Call("PUT", OBJECTS + "/Gadget__c/record-types/Odd__c",
                        "{\"label\":\"Odd\",\"description\":\"\",\"version\":0}"),
                new Call("DELETE", OBJECTS + "/Gadget__c/record-types/Odd__c", null));
    }

    private static List<Call> everyEndpoint(TestBrowser admin, String set) {
        Response added = addChange(admin, set, CHANGE);
        String changeId = JsonPath.read(added.body(), "$.data.changes[0].id");
        return everyEndpoint(set, changeId);
    }

    private TestBrowser adminWithGadget(Organization organization) {
        TestBrowser admin = adminOf(organization);
        createObject(admin, "Gadget");
        return admin;
    }

    // ---- denied ----

    @Test
    void aMemberWithoutTheAbilitiesIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        List<Call> calls = everyEndpoint(admin, createSet(admin, "Draft"));
        TestBrowser person = as(organization, memberWith(organization, admin));

        for (Call call : calls) {
            Response refused = person.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(403);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void aViewerReadsAndNothingElse() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        List<Call> calls = everyEndpoint(admin, createSet(admin, "Draft"));
        TestBrowser viewer = as(organization, memberWith(organization, admin, "metadata.view"));

        for (Call call : calls) {
            Response response = viewer.request(call.method(), call.path(), call.body());
            if (call.method().equals("GET")) {
                assertThat(response.status()).as(call + " " + response.body()).isIn(200, 404);
            } else {
                assertThat(response.status()).as(call + " " + response.body()).isEqualTo(403);
            }
        }
    }

    @Test
    void aDrafterMayDraftAndCheckButNotPublishOrChangeAtOnce() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        String set = createSet(admin, "Draft");
        TestBrowser drafter = as(organization, memberWith(organization, admin, "metadata.view", "metadata.manage"));

        Response created = drafter.postJson(SETS, "{\"name\":\"Mine\",\"description\":\"\"}");
        String mine = JsonPath.read(created.body(), "$.data.id");
        Response added = addChange(drafter, mine, CHANGE);
        Response checked = validate(drafter, mine);
        Response previewed = drafter.postJson(SETS + "/" + mine + "/preview", "{}");
        Response published = publish(drafter, set);
        Response live = createRecordType(drafter, "Gadget__c", RT);
        Response rolledBack = drafter.postJson(RELEASES + "/latest/rollback", "{}");
        Response discarded = drafter.request("DELETE", SETS + "/" + mine, null);

        assertThat(created.status()).isEqualTo(201);
        assertThat(added.status()).isEqualTo(201);
        assertThat(checked.status()).isEqualTo(200);
        assertThat(previewed.status()).isEqualTo(200);
        assertThat(published.status()).as("publishing is its own ability").isEqualTo(403);
        assertThat(live.status()).as("a change at once is a publication").isEqualTo(403);
        assertThat(rolledBack.status()).isEqualTo(403);
        assertThat(discarded.status()).isEqualTo(204);
    }

    @Test
    void aPublisherMayPublishWhatOthersDraftedAndRollBackButNotDraft() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        String set = createSet(admin, "Draft");
        addChange(admin, set, CHANGE);
        TestBrowser publisher = as(organization, memberWith(organization, admin, "metadata.view",
                "metadata.publish"));

        Response draft = publisher.postJson(SETS, "{\"name\":\"Mine\",\"description\":\"\"}");
        Response checked = validate(publisher, set);
        Response published = publish(publisher, set);
        Response rolledBack = publisher.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(draft.status()).as("drafting is the ability to manage").isEqualTo(403);
        assertThat(checked.status()).isEqualTo(200);
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(objectNames(admin)).doesNotContain("Thing__c");
    }

    @Test
    void anAdministratorMayDoEverything() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        String set = createSet(admin, "Draft");
        addChange(admin, set, CHANGE);

        assertThat(validate(admin, set).status()).isEqualTo(200);
        assertThat(publish(admin, set).status()).isEqualTo(200);
        assertThat(admin.postJson(RELEASES + "/latest/rollback", "{}").status()).isEqualTo(200);
    }

    @Test
    void aMemberRefusedIsRecordedWithTheMissingAbility() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        String set = createSet(admin, "Draft");
        TestBrowser person = as(organization, memberWith(organization, admin, "metadata.view"));

        publish(person, set);

        Response events = admin.get("/api/v1/audit-events?kind=membership.action.refused");
        assertThat(events.body()).contains("metadata.changeset.publish");
    }

    // ---- cross-tenant, forged header ----

    @Test
    void anotherOrganizationNeverSeesOrChangesAChangeSet() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithGadget(mine);
        TestBrowser other = adminWithGadget(theirs);
        String set = createSet(admin, "Mine");
        List<Call> calls = everyEndpoint(admin, set);

        for (Call call : calls) {
            if (call.path().contains(set)) {
                Response response = other.request(call.method(), call.path(), call.body());
                assertThat(response.status()).as(call + " " + response.body()).isEqualTo(404);
            }
        }
        assertThat(JsonPath.<List<String>>read(other.get(SETS).body(), "$.data[*].name")).doesNotContain("Mine");
        assertThat(JsonPath.<String>read(admin.get(SETS + "/" + set).body(), "$.data.status")).isEqualTo("DRAFT");
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithGadget(mine);
        TestBrowser other = adminWithGadget(theirs);
        createSet(other, "Secret");
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response plain = admin.get(SETS);
        Response withHeaders = admin.get(SETS, forged);
        Response create = admin.request("POST", SETS, "{\"name\":\"Forged\",\"description\":\"\"}", forged);

        assertThat(withHeaders.body()).isEqualTo(plain.body()).doesNotContain("Secret");
        assertThat(create.status()).isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(other.get(SETS).body(), "$.data[*].name")).doesNotContain("Forged");
        assertThat(JsonPath.<List<String>>read(admin.get(SETS).body(), "$.data[*].name")).contains("Forged");
    }

    // ---- platform administrator, unauthenticated ----

    @Test
    void aPlatformAdministratorIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        List<Call> calls = everyEndpoint(admin, createSet(admin, "Draft"));
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : calls) {
            Response refused = platformAdmin.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(404);
        }
    }

    @Test
    void anUnauthenticatedCallerIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        List<Call> calls = everyEndpoint(admin, createSet(admin, "Draft"));
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : calls) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
    }
}
