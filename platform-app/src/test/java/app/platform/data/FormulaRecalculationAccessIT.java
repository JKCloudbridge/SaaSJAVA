package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Who may read and start the recalculation of an object's stored formula results (Sprint 16A, ADR-0113): reading
 * needs the ability to view metadata, starting needs the ability to publish it. Tried as an administrator, as members
 * who hold one ability or none, as an administrator of another organization, with a forged tenant header, as a
 * platform administrator and without signing in. The timer and the start by hand must not run the pass behind the
 * test's back, so the job is switched off: the states asserted are the ones just after the call.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = "platform.data.recalculation.enabled=false")
class FormulaRecalculationAccessIT extends PipelineSupport {

    private static final String STATUS = EMPLOYEES + "/formula-recalculation";

    /** A member whose profile holds exactly these abilities (and no permission on any object). */
    private Member memberWithAbilities(Organization organization, TestBrowser admin, String... abilities) {
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        String listed = String.join(",", Arrays.stream(abilities).map(name -> "\"" + name + "\"").toList());
        Response profile = admin.postJson("/api/v1/profiles", "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[" + listed + "]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        Response set = admin.request("PUT", "/api/v1/members/" + person.membership() + "/profile",
                "{\"profileId\":\"" + JsonPath.<String>read(profile.body(), "$.data.id") + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        return person;
    }

    @Test
    void anAdministratorReadsTheStateAndStartsAPass() {
        TestBrowser admin = adminWithEmployee(organization());

        Response read = admin.get(STATUS);
        Response started = admin.postJson(STATUS, "{}");

        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(read.body(), "$.data.state")).isEqualTo("NEEDED");
        assertThat(JsonPath.<Integer>read(read.body(), "$.data.formulaFields")).isEqualTo(1);
        assertThat(started.status()).as(started.body()).isEqualTo(202);
        assertThat(JsonPath.<String>read(started.body(), "$.data.state")).isEqualTo("PENDING");
    }

    @Test
    void aMemberWithNoAbilityIsForbiddenBoth() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        TestBrowser nobody = as(organization, memberWithActions(organization, admin, "read"));

        assertThat(nobody.get(STATUS).status()).isEqualTo(403);
        assertThat(nobody.postJson(STATUS, "{}").status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(admin.get(STATUS).body(), "$.data.state")).as("and nothing was started")
                .isEqualTo("NEEDED");
    }

    @Test
    void theAbilityToViewMetadataReadsButDoesNotStart() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        TestBrowser viewer = as(organization, memberWithAbilities(organization, admin, "metadata.view"));

        assertThat(viewer.get(STATUS).status()).isEqualTo(200);
        assertThat(viewer.postJson(STATUS, "{}").status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(admin.get(STATUS).body(), "$.data.state")).isEqualTo("NEEDED");
    }

    @Test
    void theAbilityToPublishMetadataStartsButDoesNotRead() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        TestBrowser publisher = as(organization, memberWithAbilities(organization, admin, "metadata.publish"));

        assertThat(publisher.get(STATUS).status()).isEqualTo(403);
        assertThat(publisher.postJson(STATUS, "{}").status()).isEqualTo(202);
        assertThat(JsonPath.<String>read(admin.get(STATUS).body(), "$.data.state")).isEqualTo("PENDING");
    }

    @Test
    void anObjectThatDoesNotExistAndAnObjectOfAPlatformModuleAreNotFound() {
        TestBrowser admin = adminWithEmployee(organization());

        assertThat(admin.get("/api/v1/objects/Nothing__c/formula-recalculation").status()).isEqualTo(404);
        assertThat(admin.postJson("/api/v1/objects/Nothing__c/formula-recalculation", "{}").status())
                .isEqualTo(404);
        assertThat(admin.get("/api/v1/objects/User/formula-recalculation").status()).isEqualTo(404);
    }

    @Test
    void anotherOrganizationNeverSeesOrStartsMyPassAndNeverFindsMyObject() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithEmployee(mine);
        makeObject(admin, "Mine", "Mines");
        TestBrowser other = adminWithEmployee(theirs);
        assertThat(admin.postJson(STATUS, "{}").status()).isEqualTo(202);

        Response theirStatus = other.get(STATUS);

        assertThat(JsonPath.<String>read(theirStatus.body(), "$.data.state")).as("their own, untouched")
                .isEqualTo("NEEDED");
        assertThat(theirStatus.body()).as("no pass of theirs was started").doesNotContain("MANUAL");
        assertThat(other.get("/api/v1/objects/Mine__c/formula-recalculation").status()).isEqualTo(404);
        assertThat(admin.get("/api/v1/objects/Mine__c/formula-recalculation").status()).as("mine, no formulas")
                .isEqualTo(200);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithEmployee(mine);
        TestBrowser other = adminWithEmployee(theirs);
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response started = admin.request("POST", STATUS, "{}", forged);

        assertThat(started.status()).isEqualTo(202);
        assertThat(JsonPath.<String>read(admin.get(STATUS).body(), "$.data.state")).as("mine was started")
                .isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(other.get(STATUS).body(), "$.data.state")).as("theirs was not")
                .isEqualTo("NEEDED");
    }

    @Test
    void aPlatformAdministratorIsRefusedBoth() {
        Organization organization = organization();
        adminWithEmployee(organization);
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        assertThat(platformAdmin.get(STATUS).status()).isEqualTo(404);
        assertThat(platformAdmin.postJson(STATUS, "{}").status()).isEqualTo(404);
    }

    @Test
    void anUnauthenticatedCallerIsRefusedBoth() {
        Organization organization = organization();
        adminWithEmployee(organization);
        TestBrowser nobody = new TestBrowser(port, organization.host());

        assertThat(nobody.get(STATUS).status()).isEqualTo(401);
        assertThat(nobody.postJson(STATUS, "{}").status()).isEqualTo(401);
    }

    @Test
    void aStartWithoutTheRequestForgeryHeaderIsRefused() {
        TestBrowser admin = adminWithEmployee(organization());

        Response refused = admin.postJsonWithoutCsrfHeader(STATUS, "{}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(admin.get(STATUS).body(), "$.data.state")).isEqualTo("NEEDED");
    }
}
