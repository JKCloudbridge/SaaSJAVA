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
 * Who may do what with layouts, record pages, applications and layout assignments (Sprint 12, ADR-0068, ADR-0073): the
 * usual tests of every endpoint (allowed, denied, cross-tenant, forged tenant header, platform administrator refused,
 * unauthenticated), the split between viewing, drafting and publishing, and the member's own menu, which any active
 * member may read and no one else's.
 */
@PlatformIntegrationTest
class DefinitionsAccessIT extends DefinitionsSupport {

    /** One call to an endpoint. */
    private record Call(String method, String path, String body) {
    }

    private static final String GADGET = OBJECTS + "/Gadget__c";
    private static final String PAGE_BODY = "{\"layout\":\"Odd__c\",\"components\":["
            + "{\"key\":\"d\",\"type\":\"DETAILS\"}]}";
    private static final String LAYOUT_BODY = "{\"sections\":[{\"label\":\"A\",\"fields\":[]}]}";
    private static final String APP_BODY = "{\"items\":[]}";

    private static final List<Call> EVERY_ENDPOINT = List.of(
            new Call("GET", GADGET + "/layouts", null),
            new Call("GET", GADGET + "/layouts/Odd__c", null),
            new Call("POST", GADGET + "/layouts", layoutRequest("Odd", LAYOUT_BODY)),
            new Call("PUT", GADGET + "/layouts/Odd__c", layoutUpdate("Odd", LAYOUT_BODY, 0)),
            new Call("DELETE", GADGET + "/layouts/Odd__c", null),
            new Call("GET", GADGET + "/record-pages", null),
            new Call("GET", GADGET + "/record-pages/Odd__c", null),
            new Call("POST", GADGET + "/record-pages", layoutRequest("Odd", PAGE_BODY)),
            new Call("PUT", GADGET + "/record-pages/Odd__c", layoutUpdate("Odd", PAGE_BODY, 0)),
            new Call("DELETE", GADGET + "/record-pages/Odd__c", null),
            new Call("GET", APPLICATIONS, null),
            new Call("GET", APPLICATIONS + "/Odd__c", null),
            new Call("POST", APPLICATIONS, layoutRequest("Odd", APP_BODY)),
            new Call("PUT", APPLICATIONS + "/Odd__c", layoutUpdate("Odd", APP_BODY, 0)),
            new Call("DELETE", APPLICATIONS + "/Odd__c", null),
            new Call("GET", GADGET + "/layout-assignments", null),
            new Call("GET", GADGET + "/layout-assignments/default", null),
            new Call("GET", GADGET + "/layout-resolution", null),
            new Call("POST", GADGET + "/layout-assignments", "{\"layout\":\"Odd__c\"}"),
            new Call("PUT", GADGET + "/layout-assignments/default", "{\"layout\":\"Odd__c\",\"version\":0}"),
            new Call("DELETE", GADGET + "/layout-assignments/default", null));

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
        TestBrowser person = as(organization, memberWith(organization, admin));

        for (Call call : EVERY_ENDPOINT) {
            Response refused = person.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(403);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void aViewerReadsAndNothingElse() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        TestBrowser viewer = as(organization, memberWith(organization, admin, "metadata.view"));

        for (Call call : EVERY_ENDPOINT) {
            Response response = viewer.request(call.method(), call.path(), call.body());
            if (call.method().equals("GET")) {
                assertThat(response.status()).as(call + " " + response.body()).isIn(200, 404);
            } else {
                assertThat(response.status()).as(call + " " + response.body()).isEqualTo(403);
            }
        }
    }

    @Test
    void aDrafterMayDraftTheNewKindsOfChangeButNotMakeThemLive() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        TestBrowser drafter = as(organization, memberWith(organization, admin, "metadata.view", "metadata.manage"));
        String set = JsonPath.read(drafter.postJson(SETS, "{\"name\":\"Mine\",\"description\":\"\"}").body(),
                "$.data.id");

        Response layout = addChange(drafter, set, change("CREATE_LAYOUT", "Gadget__c", null, "createLayout",
                layoutRequest("Card", LAYOUT_BODY)));
        Response application = addChange(drafter, set, change("CREATE_APPLICATION", "", null, "createApplication",
                layoutRequest("Menu", APP_BODY)));
        Response checked = validate(drafter, set);
        Response published = publish(drafter, set);
        Response live = createLayout(drafter, "Gadget__c", "Card", LAYOUT_BODY);

        assertThat(layout.status()).as(layout.body()).isEqualTo(201);
        assertThat(application.status()).as(application.body()).isEqualTo(201);
        assertThat(checked.status()).isEqualTo(200);
        assertThat(published.status()).isEqualTo(403);
        assertThat(live.status()).as("a change at once is a publication").isEqualTo(403);
        assertThat(admin.get(GADGET + "/layouts").body()).doesNotContain("Card__c");
    }

    @Test
    void aPublisherMayPublishADraftOfTheNewKinds() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        String set = createSet(admin, "Draft");
        addChange(admin, set, change("CREATE_LAYOUT", "Gadget__c", null, "createLayout",
                layoutRequest("Card", LAYOUT_BODY)));
        TestBrowser publisher = as(organization, memberWith(organization, admin, "metadata.view",
                "metadata.publish"));

        Response published = publish(publisher, set);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(GADGET + "/layouts").body(), "$.data[*].apiName"))
                .containsExactly("Card__c");
    }

    @Test
    void anAdministratorMayDoEverything() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);

        Response layout = createLayout(admin, "Gadget__c", "Card", LAYOUT_BODY);
        Response application = createApplication(admin, "Menu", APP_BODY);
        Response assignment = admin.postJson(GADGET + "/layout-assignments", "{\"layout\":\"Card__c\"}");

        assertThat(layout.status()).as(layout.body()).isEqualTo(201);
        assertThat(application.status()).as(application.body()).isEqualTo(201);
        assertThat(assignment.status()).as(assignment.body()).isEqualTo(201);
    }

    @Test
    void aRefusedMemberIsRecordedWithTheMissingAbility() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        TestBrowser person = as(organization, memberWith(organization, admin, "metadata.view"));

        createLayout(person, "Gadget__c", "Card", LAYOUT_BODY);

        assertThat(admin.get("/api/v1/audit-events?kind=membership.action.refused").body())
                .contains("metadata.layout.create");
    }

    // ---- cross-tenant, forged header ----

    @Test
    void anotherOrganizationNeverSeesOrChangesTheDefinitionsOfThisOne() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithGadget(mine);
        TestBrowser other = adminWithGadget(theirs);
        createLayout(admin, "Gadget__c", "Odd", LAYOUT_BODY);
        createRecordPage(admin, "Gadget__c", "Odd", PAGE_BODY.replace("Odd__c", "Odd__c"));
        createApplication(admin, "Odd", APP_BODY);
        admin.postJson(GADGET + "/layout-assignments", "{\"layout\":\"Odd__c\"}");

        for (String path : List.of(GADGET + "/layouts/Odd__c", GADGET + "/record-pages/Odd__c",
                APPLICATIONS + "/Odd__c", GADGET + "/layout-assignments/default")) {
            assertThat(other.get(path).status()).as(path).isEqualTo(404);
            assertThat(other.request("DELETE", path, null).status()).as(path).isEqualTo(404);
        }
        assertThat(JsonPath.<List<String>>read(other.get(GADGET + "/layouts").body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(other.get(APPLICATIONS).body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(other.get(MY_APPLICATIONS).body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(GADGET + "/layouts").body(), "$.data[*].apiName"))
                .containsExactly("Odd__c");
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithGadget(mine);
        TestBrowser other = adminWithGadget(theirs);
        createLayout(other, "Gadget__c", "Secret", LAYOUT_BODY);
        createApplication(other, "SecretApp", APP_BODY);
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response plain = admin.get(GADGET + "/layouts");
        Response withHeaders = admin.get(GADGET + "/layouts", forged);
        Response apps = admin.get(APPLICATIONS, forged);
        Response mineWithHeaders = admin.get(MY_APPLICATIONS, forged);
        Response create = admin.request("POST", GADGET + "/layouts", layoutRequest("Forged", LAYOUT_BODY), forged);

        assertThat(withHeaders.body()).isEqualTo(plain.body()).doesNotContain("Secret");
        assertThat(apps.body()).doesNotContain("SecretApp");
        assertThat(mineWithHeaders.body()).doesNotContain("SecretApp");
        assertThat(create.status()).isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(other.get(GADGET + "/layouts").body(), "$.data[*].apiName"))
                .doesNotContain("Forged__c");
        assertThat(JsonPath.<List<String>>read(admin.get(GADGET + "/layouts").body(), "$.data[*].apiName"))
                .contains("Forged__c");
    }

    // ---- platform administrator, unauthenticated ----

    @Test
    void aPlatformAdministratorIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        adminWithGadget(organization);
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : EVERY_ENDPOINT) {
            Response refused = platformAdmin.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(404);
        }
        assertThat(platformAdmin.get(MY_APPLICATIONS).status()).isEqualTo(404);
    }

    @Test
    void anUnauthenticatedCallerIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        adminWithGadget(organization);
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : EVERY_ENDPOINT) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
        assertThat(nobody.get(MY_APPLICATIONS).status()).isEqualTo(401);
    }

    // ---- the member's own menu ----

    @Test
    void anyActiveMemberReadsTheirOwnMenuEvenWithoutAnyAbility() {
        Organization organization = organization();
        TestBrowser admin = adminWithGadget(organization);
        createApplication(admin, "Menu",
                applicationDefinition("[]", "[{\"type\":\"OBJECT\",\"object\":\"Gadget__c\"}]"));
        // The menu needs no administrative ability to be read (that is this test's point), but Sprint 18 made the menu
        // security-aware: an entry is listed only when the member may read what it opens (ADR-0128). Give the member
        // read permission on the object the menu's only entry names, so the test still proves its point.
        Holder holder = holderOf(organization, admin);
        allowReading(admin, holder.profileId(), "Gadget__c");
        TestBrowser person = as(organization, holder.member());

        Response mine = person.get(MY_APPLICATIONS);

        assertThat(mine.status()).as(mine.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(mine.body(), "$.data[*].apiName")).containsExactly("Menu__c");
        assertThat(person.get(APPLICATIONS).status()).as("the definitions are for those who may view metadata")
                .isEqualTo(403);
    }
}
