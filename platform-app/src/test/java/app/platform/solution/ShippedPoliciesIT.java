package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.security.ObjectAction;
import app.platform.security.ShippedAccessPolicies;
import app.platform.security.ShippedAccessPolicies.Declaration;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The access policies the platform ships with the solution (Functional track FA1, ADR-0175, ADR-0181): made as rows of
 * the organization at its first use after it gained the feature, once, whatever happens at the same moment; assignable
 * to people and not licence-bound; neither editable nor removable by anyone, in the API and in the database; kept after
 * the feature is lost; and brought up to date when a release changes what they give.
 */
@PlatformIntegrationTest
class ShippedPoliciesIT extends SolutionSupport {

    private static final String FIXED_WORDS = "This access policy comes with the platform and cannot be changed or "
            + "removed. You can give it to members and groups.";

    @Autowired
    private ShippedAccessPolicies policies;

    @Autowired
    private TenantContexts contexts;

    private List<String> shippedIds(TestBrowser admin) {
        return JsonPath.read(admin.get(POLICIES).body(), "$.data[?(@.managed==true)].id");
    }

    // ---- made once, idempotent ----

    @Test
    void thePoliciesAreMadeAtTheFirstUseAfterTheFeatureIsGainedAndNotBefore() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        assertThat(shippedIds(admin)).isEmpty();

        switchOn(organization);
        assertThat(shippedIds(admin)).as("nothing is made until somebody uses the organization").isEmpty();
        assertThat(applicationsOf(admin)).contains(APPLICATION);

        Response list = admin.get(POLICIES);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[?(@.managed==true)].name"))
                .containsExactlyInAnyOrderElementsOf(SHIPPED_POLICIES);
        assertThat(JsonPath.<List<Object>>read(list.body(), "$.data[?(@.managed==true && @.requiredLicenceType)]"))
                .as("a shipped policy is not licence-bound").isEmpty();
        assertThat(JsonPath.<List<Object>>read(list.body(), "$.data[?(@.managed==true)].abilities[*]"))
                .as("it gives no ability yet").isEmpty();
    }

    @Test
    void askingAgainMakesNothingNewAndKeepsTheIdentifiers() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        List<String> first = shippedIds(admin);

        for (int i = 0; i < 3; i++) {
            applicationsOf(admin);
        }

        assertThat(shippedIds(admin)).containsExactlyInAnyOrderElementsOf(first).hasSize(SHIPPED_POLICIES.size());
    }

    @Test
    void severalRequestsAtTheSameMomentMakeEachPolicyOnce() throws Exception {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        List<TestBrowser> browsers = new ArrayList<>();
        browsers.add(admin);
        for (int i = 0; i < 5; i++) {
            browsers.add(as(organization, plainMember(organization)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(browsers.size());
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (TestBrowser browser : browsers) {
                answers.add(pool.submit(() -> browser.get(MY_APPLICATIONS).status()));
            }
            for (Future<Integer> answer : answers) {
                assertThat(answer.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES).body(), "$.data[?(@.managed==true)].name"))
                .containsExactlyInAnyOrderElementsOf(SHIPPED_POLICIES);
    }

    @Test
    void theRightsAreTheReadRightOnTheFirstObjectAndNothingElse() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);

        for (String name : SHIPPED_POLICIES) {
            Response access = admin.get(POLICIES + "/" + policyId(admin, name) + "/data-access");

            assertThat(access.status()).as(name + ": " + access.body()).isEqualTo(200);
            assertThat(JsonPath.<List<String>>read(access.body(), "$.data.objects[*].key")).as(name)
                    .containsExactly(OBJECT);
            assertThat(JsonPath.<List<String>>read(access.body(), "$.data.objects[0].actions")).as(name)
                    .containsExactly("read");
            assertThat(JsonPath.<List<Object>>read(access.body(), "$.data.fields")).as(name).isEmpty();
        }
    }

    // ---- assigned, not changed, not removed ----

    @Test
    void anAdministratorCanGiveAShippedPolicyToAMemberAndTakeItBack() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        Member member = memberHolding(organization, admin, "Filler");
        TestBrowser browser = as(organization, member);

        assertThat(applicationsOf(browser)).contains(APPLICATION);
        Response taken = admin.request("DELETE", MEMBERS + "/" + member.membership() + "/policies/"
                + policyId(admin, "Filler"), null);

        assertThat(taken.status()).as(taken.body()).isIn(200, 204);
        assertThat(applicationsOf(browser)).doesNotContain(APPLICATION);
        assertThat(JsonPath.<List<Integer>>read(admin.get(POLICIES).body(), "$.data[?(@.name=='Filler')].members"))
                .containsExactly(0);
    }

    @Test
    void nobodyCanChangeOrRemoveAShippedPolicyThroughTheApi() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        String id = policyId(admin, "Reader");

        Response rename = admin.request("PUT", POLICIES + "/" + id,
                "{\"name\":\"Mine\",\"description\":\"\",\"abilities\":[\"members.view\"]}");
        Response remove = admin.request("DELETE", POLICIES + "/" + id, null);
        Response rights = admin.request("PUT", POLICIES + "/" + id + "/data-access",
                "{\"objects\":[{\"key\":\"" + OBJECT + "\",\"actions\":[\"read\",\"update\"]}],\"fields\":[]}");

        for (Response refused : List.of(rename, remove, rights)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("CONFLICT");
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).isEqualTo(FIXED_WORDS);
        }
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES + "/" + id + "/data-access").body(),
                "$.data.objects[0].actions")).containsExactly("read");
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES).body(), "$.data[?(@.managed==true)].name"))
                .containsExactlyInAnyOrderElementsOf(SHIPPED_POLICIES);
    }

    @Test
    void aMemberWhoMayNotManageAccessCannotRemoveOneEither() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        Member plain = plainMember(organization);
        applicationsOf(admin);

        Response refused = as(organization, plain).request("DELETE", POLICIES + "/" + policyId(admin, "Author"), null);

        assertThat(refused.status()).as("not allowed to manage access at all").isEqualTo(403);
    }

    @Test
    void aPolicyWithTheSameNameAnAdministratorMadeIsNotTakenOverAndTheShippedOneGetsASuffix() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Response own = admin.postJson(POLICIES,
                "{\"name\":\"Author\",\"description\":\"my own\",\"abilities\":[\"members.view\"]}");
        assertThat(own.status()).as(own.body()).isEqualTo(201);
        String ownId = JsonPath.read(own.body(), "$.data.id");
        switchOn(organization);

        assertThat(applicationsOf(admin)).contains(APPLICATION);

        Response list = admin.get(POLICIES);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[?(@.managed==false)].name")).containsExactly("Author");
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[?(@.managed==false)].id")).containsExactly(ownId);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[?(@.managed==true)].name"))
                .containsExactlyInAnyOrder("Author (platform)", "Approver", "Reader", "Filler");
        assertThat(admin.request("DELETE", POLICIES + "/" + ownId, null).status()).as("their own policy is theirs")
                .isIn(200, 204);
    }

    @Test
    void thePoliciesStayWhenTheFeatureIsLostAndAreTheSameOnesWhenItReturns() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        List<String> ids = shippedIds(admin);

        switchOff(organization);
        applicationsOf(admin);
        assertThat(shippedIds(admin)).containsExactlyInAnyOrderElementsOf(ids);

        switchOn(organization);
        applicationsOf(admin);
        assertThat(shippedIds(admin)).containsExactlyInAnyOrderElementsOf(ids);
    }

    // ---- the database holds it too ----

    @Test
    void theDatabaseRefusesToInsertChangeOrRemoveAShippedPolicyForTheApplicationRole() throws SQLException {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        UUID id = UUID.fromString(policyId(admin, "Reader"));

        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "update access_policy set name = 'Renamed', version = version + 1, updated_by = ? "
                        + "where id = ?", UUID.randomUUID(), id))).hasMessageContaining("shipped policy guard");
        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "update access_policy set abilities = '{members.view}', version = version + 1, "
                        + "updated_by = ? where id = ?", UUID.randomUUID(), id)))
                .hasMessageContaining("shipped policy guard");
        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "update access_policy set deleted_at = now(), deleted_by = ?, version = version + 1, "
                        + "updated_by = ? where id = ?", UUID.randomUUID(), UUID.randomUUID(), id)))
                .hasMessageContaining("shipped policy guard");
        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "update access_policy set managed_by = null, managed_key = null, managed_hash = null, "
                        + "version = version + 1, updated_by = ? where id = ?", UUID.randomUUID(), id)))
                .hasMessageContaining("shipped policy guard");
        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "insert into access_policy (name, managed_by, managed_key, managed_hash, created_by, "
                        + "updated_by) values ('Forged', 'solution', 'inspection.forged', 'x', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID()))).hasMessageContaining("shipped policy guard");
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES).body(), "$.data[?(@.managed==true)].name"))
                .containsExactlyInAnyOrderElementsOf(SHIPPED_POLICIES);
    }

    @Test
    void anOrdinaryPolicyCannotBecomeAShippedOne() throws SQLException {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        String id = JsonPath.read(admin.postJson(POLICIES,
                "{\"name\":\"Ordinary\",\"description\":\"\",\"abilities\":[]}").body(), "$.data.id");

        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "update access_policy set managed_by = 'solution', managed_key = 'inspection.x', "
                        + "managed_hash = 'x', version = version + 1, updated_by = ? where id = ?",
                UUID.randomUUID(), UUID.fromString(id)))).hasMessageContaining("shipped policy guard");
        int updated = TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection,
                "update access_policy set description = 'still free', version = version + 1, updated_by = ? "
                        + "where id = ?", UUID.randomUUID(), UUID.fromString(id)));
        assertThat(updated).isEqualTo(1);
    }

    // ---- brought up to date ----

    @Test
    void aReleaseThatChangesWhatAPolicyGivesIsPickedUpWithOneComparison() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        String readerId = policyId(admin, "Reader");
        Declaration changed = new Declaration("inspection.reader", "Reader", "Changed by a release.",
                Map.of(OBJECT, Set.of(ObjectAction.READ, ObjectAction.UPDATE)));
        List<Declaration> declared = List.of(changed);

        boolean before = contexts.call(TenantContext.of(organization.id()),
                () -> policies.upToDate("solution", declared));
        ShippedAccessPolicies.Result result = contexts.call(TenantContext.of(organization.id()),
                () -> policies.ensure("solution", declared));
        boolean after = contexts.call(TenantContext.of(organization.id()),
                () -> policies.upToDate("solution", declared));
        ShippedAccessPolicies.Result again = contexts.call(TenantContext.of(organization.id()),
                () -> policies.ensure("solution", declared));

        assertThat(before).isFalse();
        assertThat(result).isEqualTo(new ShippedAccessPolicies.Result(0, 1));
        assertThat(after).isTrue();
        assertThat(again).isEqualTo(new ShippedAccessPolicies.Result(0, 0));
        assertThat(policyId(admin, "Reader")).as("the same row, brought up to date").isEqualTo(readerId);
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES + "/" + readerId + "/data-access").body(),
                "$.data.objects[0].actions")).containsExactlyInAnyOrder("read", "update");

        // The next request of the organization finds the policy differs from what this release declares and puts it back.
        assertThat(applicationsOf(admin)).contains(APPLICATION);
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES + "/" + readerId + "/data-access").body(),
                "$.data.objects[0].actions")).containsExactly("read");
    }

    @Test
    void theFingerprintChangesWithAnythingTheDeclarationSays() {
        Declaration base = new Declaration("inspection.reader", "Reader", "d", Map.of(OBJECT, Set.of(ObjectAction.READ)));

        assertThat(base.fingerprint()).isEqualTo(new Declaration("inspection.reader", "Reader", "d",
                Map.of(OBJECT, Set.of(ObjectAction.READ))).fingerprint());
        assertThat(base.fingerprint()).isNotEqualTo(new Declaration("inspection.reader", "Reader 2", "d",
                Map.of(OBJECT, Set.of(ObjectAction.READ))).fingerprint());
        assertThat(base.fingerprint()).isNotEqualTo(new Declaration("inspection.reader", "Reader", "e",
                Map.of(OBJECT, Set.of(ObjectAction.READ))).fingerprint());
        assertThat(base.fingerprint()).isNotEqualTo(new Declaration("inspection.reader", "Reader", "d",
                Map.of(OBJECT, Set.of(ObjectAction.READ, ObjectAction.CREATE))).fingerprint());
        assertThat(base.fingerprint()).isNotEqualTo(new Declaration("inspection.other", "Reader", "d",
                Map.of(OBJECT, Set.of(ObjectAction.READ))).fingerprint());
    }
}
