package app.platform.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The licence rule of access policies (Sprint 8, ADR-0046): a policy that needs the same licence type as the member's
 * profile uses no licence of its own (the profile's licence covers it, and the policy counts only while the member
 * holds
 * it); a policy of a different type takes one from that type's pool; changing the profile re-balances in both
 * directions
 * without asking for more than the member ends up using; a profile can only belong to a seat licence type.
 */
@PlatformIntegrationTest
class PolicyLicenceRuleIT {

    private static final String PROFILES = "/api/v1/profiles";
    private static final String POLICIES = "/api/v1/access-policies";
    private static final String MEMBERS = "/api/v1/members";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    private Organization organization(int userLicences, int adminLicences) {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), userLicences, adminLicences);
        return organization;
    }

    private static UUID idOf(Response created) {
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    private static UUID profile(TestBrowser admin, String name, String licenceType, String... abilities) {
        List<String> quoted = java.util.Arrays.stream(abilities).map(ability -> "\"" + ability + "\"").toList();
        return idOf(admin.postJson(PROFILES, "{\"name\":\"" + name + "\",\"description\":\"\",\"licenceType\":\""
                + licenceType + "\",\"abilities\":[" + String.join(",", quoted) + "]}"));
    }

    private static UUID policy(TestBrowser admin, String name, String licenceType, String... abilities) {
        List<String> quoted = java.util.Arrays.stream(abilities).map(ability -> "\"" + ability + "\"").toList();
        return idOf(admin.postJson(POLICIES, "{\"name\":\"" + name + "\",\"description\":\"\",\"abilities\":["
                + String.join(",", quoted) + "],\"requiredLicenceType\":\"" + licenceType + "\"}"));
    }

    private static Response setProfile(TestBrowser admin, UUID membership, UUID profile) {
        return admin.request("PUT", MEMBERS + "/" + membership + "/profile", "{\"profileId\":\"" + profile + "\"}");
    }

    private static Response assign(TestBrowser admin, UUID membership, UUID policy) {
        return admin.postJson(MEMBERS + "/" + membership + "/policies", "{\"policyId\":\"" + policy + "\"}");
    }

    private static int assigned(TestBrowser admin, String type) {
        List<Integer> value = JsonPath.read(admin.get("/api/v1/licences").body(),
                "$.data[?(@.licenceType=='" + type + "')].assigned");
        return value.get(0);
    }

    private static List<String> abilitiesOf(TestBrowser browser) {
        return JsonPath.read(browser.get("/api/v1/auth/me").body(), "$.data.abilities");
    }

    @Test
    void aPolicyOfTheProfilesLicenceTypeUsesNoLicenceOfItsOwnAndCountsOnlyWhileTheProfileLicenceIsHeld() {
        Organization organization = organization(5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = TestOrganizations.signedIn(port, organization.host(), person.person());
        UUID base = profile(admin, "profile-a", "user");
        UUID policy = policy(admin, "policy-a", "user", "members.view");
        assertThat(setProfile(admin, person.membership(), base).status()).isEqualTo(204);
        assertThat(assigned(admin, "user")).isEqualTo(1);

        assertThat(assign(admin, person.membership(), policy).status()).isEqualTo(204);

        assertThat(assigned(admin, "user")).as("the count does not go down").isEqualTo(1);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        assertThat(JsonPath.<List<Integer>>read(admin.get("/api/v1/licences").body(),
                "$.data[?(@.licenceType=='user')].assigned")).containsExactly(1);

        // Without the licence of the profile there is nothing that covers the policy.
        assertThat(admin.request("DELETE", MEMBERS + "/" + person.membership() + "/licence", null).status())
                .isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).isEmpty();
        assertThat(admin.request("PUT", MEMBERS + "/" + person.membership() + "/licence", null).status())
                .isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");

        assertThat(admin.request("DELETE", MEMBERS + "/" + person.membership() + "/policies/" + policy, null)
                .status()).isEqualTo(204);
        assertThat(assigned(admin, "user")).as("nothing to give back").isEqualTo(1);
    }

    @Test
    void aPolicyOfAnotherLicenceTypeTakesOneFromThatPoolAndGivesItBack() {
        Organization organization = organization(5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = TestOrganizations.signedIn(port, organization.host(), person.person());
        UUID base = profile(admin, "profile-a", "user");
        UUID policy = policy(admin, "policy-a", "admin", "members.view");
        setProfile(admin, person.membership(), base);
        int adminBefore = assigned(admin, "admin");

        assertThat(assign(admin, person.membership(), policy).status()).isEqualTo(204);

        assertThat(assigned(admin, "admin")).isEqualTo(adminBefore + 1);
        assertThat(assigned(admin, "user")).isEqualTo(1);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        assertThat(admin.request("DELETE", MEMBERS + "/" + person.membership() + "/policies/" + policy, null)
                .status()).isEqualTo(204);
        assertThat(assigned(admin, "admin")).isEqualTo(adminBefore);
    }

    @Test
    void changingTheProfileBalancesThePolicyLicenceInBothDirectionsWithoutAskingForMoreThanIsUsed() {
        // The administrator holds one admin licence; the pool has exactly two, so one is free.
        Organization organization = organization(5, 2);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID userProfile = profile(admin, "profile-a", "user");
        UUID adminProfile = profile(admin, "profile-b", "admin");
        UUID policy = policy(admin, "policy-a", "admin", "members.view");
        setProfile(admin, person.membership(), userProfile);
        assign(admin, person.membership(), policy);
        assertThat(assigned(admin, "admin")).as("the policy uses the last free one").isEqualTo(2);

        // An admin profile covers the policy: the policy's own licence goes back first, so the change succeeds
        // although no admin licence was free.
        assertThat(setProfile(admin, person.membership(), adminProfile).status()).isEqualTo(204);
        assertThat(assigned(admin, "admin")).as("the profile's licence now covers the policy").isEqualTo(2);
        assertThat(assigned(admin, "user")).isZero();

        // Back to a user profile: the policy needs a licence of its own again, and the freed one is taken.
        assertThat(setProfile(admin, person.membership(), userProfile).status()).isEqualTo(204);
        assertThat(assigned(admin, "admin")).isEqualTo(2);
        assertThat(assigned(admin, "user")).isEqualTo(1);
    }

    @Test
    void changingToAProfileWhosePolicyLicenceIsNotFreeIsRefusedAndChangesNothing() {
        // Pool: two admin licences, held by the administrator and by the profile of a second member; a third member
        // with a user profile cannot be given a policy of the admin type (none is free).
        Organization organization = organization(5, 2);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        Member holder = TestOrganizations.join(users, organization.tenant(), false);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID userProfile = profile(admin, "profile-a", "user");
        UUID adminProfile = profile(admin, "profile-b", "admin");
        UUID policy = policy(admin, "policy-a", "admin", "members.view");
        setProfile(admin, person.membership(), userProfile);
        assertThat(setProfile(admin, holder.membership(), adminProfile).status()).isEqualTo(204);

        Response refused = assign(admin, person.membership(), policy);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("No licence");
        assertThat(assigned(admin, "admin")).isEqualTo(2);
    }

    @Test
    void aProfileCanOnlyBelongToASeatLicenceType() {
        Organization organization = organization(5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        plans.addLicenceType("addon-a", "Add-on A", LicenceTypeView.ADD_ON, ActorId.SYSTEM);

        Response refused = admin.postJson(PROFILES, "{\"name\":\"profile-a\",\"description\":\"\","
                + "\"licenceType\":\"addon-a\",\"abilities\":[]}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("seat");
        List<String> kinds = JsonPath.read(admin.get("/api/v1/licence-types").body(),
                "$.data[?(@.key=='addon-a' || @.key=='user' || @.key=='admin')].kind");
        assertThat(kinds).containsExactlyInAnyOrder("ADD_ON", "SEAT", "SEAT");
    }
}
