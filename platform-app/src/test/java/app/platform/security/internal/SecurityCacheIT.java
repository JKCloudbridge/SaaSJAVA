package app.platform.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.security.Ability;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.Permissions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The security cache against a real database and three real application instances (ADR-0053): instance A (the
 * test's own, cache on), instance B (cache on) and instance C (cache off). Every change of what a member may do is made
 * through instance A's API and must show on the very next question on every instance, and the three instances must give
 * the same answer at every step (the cache is only a speed-up: the answers equal those of an instance without it).
 */
@PlatformIntegrationTest
class SecurityCacheIT {

    private static final String PROFILES = "/api/v1/profiles";
    private static final String POLICIES = "/api/v1/access-policies";
    private static final String MEMBERS = "/api/v1/members";
    private static final String GROUPS = "/api/v1/groups";

    private static TestApplication instanceB;
    private static TestApplication instanceC;

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private Permissions permissionsA;

    @Autowired
    private Decisions decisionsA;

    @Autowired
    private TenantContexts contextsA;

    @Autowired
    private SecurityCache cacheA;

    @BeforeAll
    static void startTheOtherInstances() {
        instanceB = TestApplication.start();
        instanceC = TestApplication.start("platform.security.cache.enabled=false");
    }

    @AfterAll
    static void stopTheOtherInstances() {
        instanceC.close();
        instanceB.close();
    }

    /** What one instance answers about one member. */
    private record Answer(Set<Ability> abilities, boolean readsObjectA, boolean updatesObjectA) {
    }

    private Answer askA(Organization organization, UUID membership) {
        return ask(contextsA, permissionsA, decisionsA, organization, membership);
    }

    private static Answer askOn(TestApplication instance, Organization organization, UUID membership) {
        return ask(instance.bean(TenantContexts.class), instance.bean(Permissions.class),
                instance.bean(Decisions.class), organization, membership);
    }

    private static Answer ask(TenantContexts contexts, Permissions permissions, Decisions decisions,
            Organization organization, UUID membership) {
        return contexts.call(TenantContext.of(organization.id()), () -> new Answer(
                new TreeSet<>(permissions.effective(membership)),
                decisions.can(membership, "object-a", ObjectAction.READ).allowed(),
                decisions.can(membership, "object-a", ObjectAction.UPDATE).allowed()));
    }

    private static UUID idOf(Response created) {
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    private static String quoted(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            parts.add("\"" + value + "\"");
        }
        return String.join(",", parts);
    }

    /**
     * Asks every instance twice (so that a cache has filled), then says the answers are equal and are the expected one.
     */
    private void expect(Organization organization, UUID membership, String step, Set<Ability> abilities,
            boolean reads, boolean updates) {
        Answer expected = new Answer(new TreeSet<>(abilities), reads, updates);
        for (int round = 0; round < 2; round++) {
            assertThat(askA(organization, membership)).as(step + ": instance A, cache on").isEqualTo(expected);
            assertThat(askOn(instanceB, organization, membership)).as(step + ": instance B, cache on")
                    .isEqualTo(expected);
            assertThat(askOn(instanceC, organization, membership)).as(step + ": instance C, cache off")
                    .isEqualTo(expected);
        }
    }

    @Test
    void everyChangeShowsOnTheNextQuestionOnEveryInstanceAndTheCacheChangesNoAnswer() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID member = person.membership();
        UUID profile = idOf(admin.postJson(PROFILES, "{\"name\":\"profile-a\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[" + quoted("members.view") + "]}"));
        UUID policy = idOf(admin.postJson(POLICIES, "{\"name\":\"policy-a\",\"description\":\"\","
                + "\"abilities\":[" + quoted("members.invite") + "]}"));
        UUID group = idOf(admin.postJson(GROUPS, "{\"name\":\"group-a\"}"));
        long hitsBefore = cacheA.hitCount();

        expect(organization, member, "a plain member holds nothing", Set.of(), false, false);

        // The profile and its licence.
        assertThat(admin.request("PUT", MEMBERS + "/" + member + "/profile", "{\"profileId\":\"" + profile + "\"}")
                .status()).isEqualTo(204);
        expect(organization, member, "profile given", Set.of(Ability.MEMBERS_VIEW), false, false);

        // An access policy, given and taken back.
        admin.postJson(MEMBERS + "/" + member + "/policies", "{\"policyId\":\"" + policy + "\"}");
        expect(organization, member, "policy given", Set.of(Ability.MEMBERS_VIEW, Ability.MEMBERS_INVITE), false,
                false);
        assertThat(admin.request("DELETE", MEMBERS + "/" + member + "/policies/" + policy, null).status())
                .isEqualTo(204);
        expect(organization, member, "policy taken back", Set.of(Ability.MEMBERS_VIEW), false, false);

        // A group that carries a policy: joining and leaving it.
        admin.postJson(GROUPS + "/" + group + "/policies", "{\"policyId\":\"" + policy + "\"}");
        admin.postJson(GROUPS + "/" + group + "/members", "{\"membershipId\":\"" + member + "\"}");
        expect(organization, member, "joined the group", Set.of(Ability.MEMBERS_VIEW, Ability.MEMBERS_INVITE), false,
                false);
        assertThat(admin.request("DELETE", GROUPS + "/" + group + "/members/people/" + member, null).status())
                .isEqualTo(200);
        expect(organization, member, "removed from the group", Set.of(Ability.MEMBERS_VIEW), false, false);

        // An individual grant, given and revoked.
        admin.postJson(MEMBERS + "/" + member + "/grants", "{\"ability\":\"sessions.manage\",\"reason\":\"\"}");
        expect(organization, member, "grant given", Set.of(Ability.MEMBERS_VIEW, Ability.SESSIONS_MANAGE), false,
                false);
        assertThat(admin.request("DELETE", MEMBERS + "/" + member + "/grants/sessions.manage", null).status())
                .isEqualTo(204);
        expect(organization, member, "grant revoked", Set.of(Ability.MEMBERS_VIEW), false, false);

        // The matrix of the profile, filled and replaced.
        admin.request("PUT", PROFILES + "/" + profile + "/data-access",
                "{\"objects\":[{\"key\":\"object-a\",\"actions\":[\"read\",\"update\"]}],\"fields\":[]}");
        expect(organization, member, "matrix filled", Set.of(Ability.MEMBERS_VIEW), true, true);
        admin.request("PUT", PROFILES + "/" + profile + "/data-access",
                "{\"objects\":[{\"key\":\"object-a\",\"actions\":[\"read\"]}],\"fields\":[]}");
        expect(organization, member, "matrix changed", Set.of(Ability.MEMBERS_VIEW), true, false);

        // The licence, taken back and given again.
        assertThat(admin.request("DELETE", MEMBERS + "/" + member + "/licence", null).status()).isEqualTo(204);
        expect(organization, member, "licence taken back", Set.of(), false, false);
        assertThat(admin.request("PUT", MEMBERS + "/" + member + "/licence", null).status()).isEqualTo(204);
        expect(organization, member, "licence given again", Set.of(Ability.MEMBERS_VIEW), true, false);

        // Deactivating the member.
        assertThat(admin.request("POST", MEMBERS + "/" + member + "/deactivate", null).status()).isEqualTo(204);
        expect(organization, member, "member deactivated", Set.of(), false, false);

        assertThat(cacheA.hitCount() - hitsBefore).as("instance A really served answers from its cache")
                .isGreaterThan(10);
        assertThat(instanceB.bean(SecurityCache.class).hitCount()).as("instance B too").isGreaterThan(10);
        assertThat(instanceC.bean(SecurityCache.class).hitCount()).as("instance C has the cache off").isZero();
        assertThat(instanceC.bean(SecurityCache.class).size()).isZero();
    }

    @Test
    void aMemberOfAnotherOrganizationNeverGetsTheAnswerOfThisOne() {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, first.id(), 5, 3);
        TestPlatform.subscribe(plans, subscriptions, second.id(), 5, 3);

        Answer inFirst = askA(first, first.admin().membership());
        askA(first, first.admin().membership());
        Answer ofTheFirstAskedInTheSecond = askA(second, first.admin().membership());

        assertThat(inFirst.abilities()).as("the administrator of the first holds abilities there").isNotEmpty();
        assertThat(ofTheFirstAskedInTheSecond.abilities()).as("and nothing in the second").isEmpty();
    }
}
