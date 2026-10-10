package app.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The audit viewer (Sprint 9, ADR-0056) through the real application and a real PostgreSQL: an organization's
 * administrator sees the events of their own organization and never another's, a member without {@code audit.view}
 * does not, a platform administrator is refused on the organization endpoint and the other way round, a forged tenant
 * header changes nothing, the filters and the paging work, what is for the platform only stays hidden from the
 * organization, and every security change of Sprints 7 and 8 leaves an event the viewer shows.
 */
@PlatformIntegrationTest
class AuditViewerIT {

    private static final String EVENTS = "/api/v1/audit-events";
    private static final String PLATFORM_EVENTS = "/api/v1/platform/audit-events";
    private static final String PROFILES = "/api/v1/profiles";
    private static final String POLICIES = "/api/v1/access-policies";
    private static final String MEMBERS = "/api/v1/members";
    private static final String GROUPS = "/api/v1/groups";
    private static final String ROLES = "/api/v1/roles";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private TenantContexts contexts;

    private Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        return organization;
    }

    private TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    private static UUID idOf(Response created) {
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    private static List<String> types(Response page) {
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data[*].type");
    }

    private void recordInOrganization(Organization organization, AuditRecord record) {
        contexts.run(TenantContext.of(organization.id()), () -> recorder.record(record.inTenant(organization.id())));
    }

    // ---- who sees what ----

    @Test
    void anAdministratorSeesTheirOwnOrganizationsEventsAndNeverAnothers() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        UUID profile = idOf(admin.postJson(PROFILES, "{\"name\":\"profile-mine\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[]}"));
        TestBrowser theirAdmin = adminOf(theirs);
        idOf(theirAdmin.postJson(PROFILES, "{\"name\":\"profile-theirs\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[]}"));

        Response page = admin.get(EVENTS + "?kind=access.profile.created");

        assertThat(types(page)).contains("access.profile.created");
        assertThat(page.body()).contains(profile.toString());
        assertThat(page.body()).doesNotContain("profile-theirs").doesNotContain(theirs.id().value().toString());
        List<String> actors = JsonPath.read(page.body(), "$.data[*].actorUserId");
        assertThat(actors).doesNotContain(theirs.admin().person().user().id().toString());
    }

    @Test
    void aMemberWithoutTheAbilityIsRefusedAndOneWithItSees() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = TestOrganizations.signedIn(port, organization.host(), person.person());

        assertThat(asPerson.get(EVENTS).status()).as("a plain member").isEqualTo(403);

        UUID policy = idOf(admin.postJson(POLICIES, "{\"name\":\"policy-a\",\"description\":\"\","
                + "\"abilities\":[\"audit.view\"]}"));
        // The member needs the licence of a profile to hold a policy that is not licence-bound: give them a profile.
        UUID profile = idOf(admin.postJson(PROFILES, "{\"name\":\"profile-a\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[]}"));
        admin.request("PUT", MEMBERS + "/" + person.membership() + "/profile", "{\"profileId\":\"" + profile + "\"}");
        admin.postJson(MEMBERS + "/" + person.membership() + "/policies", "{\"policyId\":\"" + policy + "\"}");

        assertThat(asPerson.get(EVENTS).status()).as("with audit.view").isEqualTo(200);
        assertThat(asPerson.get(PLATFORM_EVENTS).status()).as("never the platform's events").isIn(403, 404);
    }

    @Test
    void aPlatformAdministratorIsRefusedOnTheOrganizationEndpointAndAnOrganizationAdministratorOnThePlatformOne() {
        Organization organization = organization();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);
        TestBrowser organizationAdmin = adminOf(organization);

        assertThat(platformAdmin.get(EVENTS).status()).as("organization endpoint on the platform host")
                .isEqualTo(404);
        assertThat(organizationAdmin.get(PLATFORM_EVENTS).status()).as("platform endpoint on an organization host")
                .isEqualTo(404);
        TestUsers.TestUser support = TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT);
        assertThat(TestPlatform.signedIn(port, support).get(PLATFORM_EVENTS).status()).as("support").isEqualTo(403);
        assertThat(platformAdmin.get(PLATFORM_EVENTS).status()).as("the administrator").isEqualTo(200);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        idOf(admin.postJson(PROFILES, "{\"name\":\"profile-mine\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[]}"));
        idOf(adminOf(theirs).postJson(PROFILES, "{\"name\":\"profile-theirs\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[]}"));
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response plain = admin.get(EVENTS + "?kind=access.profile");
        Response withHeaders = admin.get(EVENTS + "?kind=access.profile", forged);

        assertThat(withHeaders.status()).isEqualTo(plain.status());
        assertThat(types(withHeaders)).isEqualTo(types(plain));
        assertThat(withHeaders.body()).doesNotContain("profile-theirs");
    }

    // ---- what stays hidden ----

    @Test
    void whatIsForThePlatformOnlyIsNotShownToTheOrganizationAndTypedTextNeverIs() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        recordInOrganization(organization, AuditRecord.of("platform.role.granted", AuditOutcome.SUCCESS));
        recordInOrganization(organization, AuditRecord.of("platform.support_access.requested", AuditOutcome.SUCCESS)
                .with("reason_text", "typed by a platform person").with("grant", "grant-1"));
        recordInOrganization(organization, AuditRecord.of("auth.sign_in.failed", AuditOutcome.FAILURE)
                .because("not_a_member"));
        recordInOrganization(organization, AuditRecord.of("access.action.refused", AuditOutcome.DENIED)
                .because("missing_ability"));

        Response page = admin.get(EVENTS);

        assertThat(types(page)).doesNotContain("platform.role.granted")
                .contains("platform.support_access.requested", "auth.sign_in.failed", "access.action.refused");
        assertThat(page.body()).doesNotContain("typed by a platform person").doesNotContain("reason_text")
                .doesNotContain("not_a_member");
        assertThat(page.body()).as("the reason of an access refusal may be shown").contains("missing_ability");
    }

    @Test
    void thePlatformSeesItsOwnEventsAndNotAnOrganizationsAdministration() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        idOf(admin.postJson(PROFILES, "{\"name\":\"profile-mine\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[]}"));
        recordInOrganization(organization, AuditRecord.of("platform.role.granted", AuditOutcome.SUCCESS));
        TestBrowser platformAdmin = TestPlatform.signedIn(port,
                TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));

        Response page = platformAdmin.get(PLATFORM_EVENTS + "?limit=200");

        assertThat(types(page)).contains("platform.role.granted").doesNotContain("access.profile.created");
    }

    // ---- filters and paging ----

    @Test
    void theFiltersNarrowTheResultAndAMalformedOneIsRefusedInWords() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        UUID profile = idOf(admin.postJson(PROFILES, "{\"name\":\"profile-a\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[]}"));
        idOf(admin.postJson(GROUPS, "{\"name\":\"group-a\"}"));
        String me = organization.admin().person().user().id().toString();

        assertThat(types(admin.get(EVENTS + "?kind=access.group"))).isNotEmpty()
                .allMatch(t -> t.startsWith("access.group"));
        assertThat(types(admin.get(EVENTS + "?kind=access.profile.created&target=" + profile))).hasSize(1);
        assertThat(types(admin.get(EVENTS + "?actor=" + UUID.randomUUID()))).isEmpty();
        assertThat(types(admin.get(EVENTS + "?actor=" + me + "&kind=access.group.created"))).hasSize(1);
        assertThat(types(admin.get(EVENTS + "?from=" + Instant.now().plusSeconds(3600)))).isEmpty();
        assertThat(types(admin.get(EVENTS + "?to=" + Instant.now().minusSeconds(3600 * 24 * 365L)))).isEmpty();
        assertThat(admin.get(EVENTS + "?kind=Not%20A%20Kind").status()).isEqualTo(400);
        assertThat(admin.get(EVENTS + "?target=has%20space").status()).isEqualTo(400);
        assertThat(admin.get(EVENTS + "?actor=not-a-uuid").status()).isEqualTo(400);
        assertThat(admin.get(EVENTS + "?from=" + Instant.now() + "&to=" + Instant.now().minusSeconds(60)).status())
                .isEqualTo(400);
        assertThat(admin.get(EVENTS + "?limit=0").status()).isEqualTo(400);
    }

    @Test
    void pagesFollowEachOtherNewestFirstWithoutRepeatsOrGaps() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        for (int i = 0; i < 7; i++) {
            idOf(admin.postJson(GROUPS, "{\"name\":\"group-" + i + "\"}"));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = admin.get(EVENTS + "?kind=access.group.created&limit=3"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).isEqualTo(200);
            seen.addAll(JsonPath.read(page.body(), "$.data[*].id"));
            Boolean more = JsonPath.read(page.body(), "$.pagination.hasMore");
            cursor = more ? JsonPath.read(page.body(), "$.pagination.nextCursor") : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        assertThat(pages).isEqualTo(3);
        List<String> times = JsonPath.read(admin.get(EVENTS + "?kind=access.group.created").body(),
                "$.data[*].occurredAt");
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(admin.get(EVENTS + "?cursor=garbage").status()).as("a bad cursor starts again").isEqualTo(200);
    }

    // ---- every security change of Sprints 7 and 8 is audited and visible ----

    /** One administrative action and the audit type it must leave. */
    private record Step(String expectedType, java.util.function.Function<Scenario, Response> action) {
    }

    /** The objects the actions work on. */
    private static final class Scenario {
        final TestBrowser admin;
        final UUID member;
        UUID profile;
        UUID policy;
        UUID role;
        UUID group;

        Scenario(TestBrowser admin, UUID member) {
            this.admin = admin;
            this.member = member;
        }
    }

    private static final List<Step> STEPS = List.of(
            new Step("access.profile.created", s -> {
                Response r = s.admin.postJson(PROFILES, "{\"name\":\"profile-a\",\"description\":\"\","
                        + "\"licenceType\":\"user\",\"abilities\":[\"members.view\"]}");
                s.profile = UUID.fromString(JsonPath.read(r.body(), "$.data.id"));
                return r;
            }),
            new Step("access.profile.updated", s -> s.admin.request("PUT", PROFILES + "/" + s.profile,
                    "{\"name\":\"profile-b\",\"description\":\"\",\"licenceType\":\"user\","
                            + "\"abilities\":[\"members.view\",\"members.invite\"]}")),
            new Step("access.policy.created", s -> {
                Response r = s.admin.postJson(POLICIES, "{\"name\":\"policy-a\",\"description\":\"\","
                        + "\"abilities\":[\"licences.manage\"]}");
                s.policy = UUID.fromString(JsonPath.read(r.body(), "$.data.id"));
                return r;
            }),
            new Step("access.policy.updated", s -> s.admin.request("PUT", POLICIES + "/" + s.policy,
                    "{\"name\":\"policy-b\",\"description\":\"\",\"abilities\":[\"licences.manage\"]}")),
            new Step("access.role.created", s -> {
                Response r = s.admin.postJson(ROLES, "{\"name\":\"role-a\"}");
                s.role = UUID.fromString(JsonPath.read(r.body(), "$.data.id"));
                return r;
            }),
            new Step("access.role.updated", s -> s.admin.request("PUT", ROLES + "/" + s.role,
                    "{\"name\":\"role-b\"}")),
            new Step("access.member.profile_set", s -> s.admin.request("PUT", MEMBERS + "/" + s.member + "/profile",
                    "{\"profileId\":\"" + s.profile + "\"}")),
            new Step("access.member.role_set", s -> s.admin.request("PUT", MEMBERS + "/" + s.member + "/role",
                    "{\"roleId\":\"" + s.role + "\"}")),
            new Step("access.member.policy_assigned", s -> s.admin.postJson(MEMBERS + "/" + s.member + "/policies",
                    "{\"policyId\":\"" + s.policy + "\"}")),
            new Step("access.member.policy_unassigned", s -> s.admin.request("DELETE",
                    MEMBERS + "/" + s.member + "/policies/" + s.policy, null)),
            new Step("access.member.grant_given", s -> s.admin.postJson(MEMBERS + "/" + s.member + "/grants",
                    "{\"ability\":\"sessions.manage\",\"reason\":\"\"}")),
            new Step("access.member.grant_revoked", s -> s.admin.request("DELETE",
                    MEMBERS + "/" + s.member + "/grants/sessions.manage", null)),
            new Step("access.group.created", s -> {
                Response r = s.admin.postJson(GROUPS, "{\"name\":\"group-a\"}");
                s.group = UUID.fromString(JsonPath.read(r.body(), "$.data.id"));
                return r;
            }),
            new Step("access.group.updated", s -> s.admin.request("PUT", GROUPS + "/" + s.group,
                    "{\"name\":\"group-b\"}")),
            new Step("access.group.member_added", s -> s.admin.postJson(GROUPS + "/" + s.group + "/members",
                    "{\"membershipId\":\"" + s.member + "\"}")),
            new Step("access.group.policy_given", s -> s.admin.postJson(GROUPS + "/" + s.group + "/policies",
                    "{\"policyId\":\"" + s.policy + "\"}")),
            new Step("access.group.policy_taken", s -> s.admin.request("DELETE",
                    GROUPS + "/" + s.group + "/policies/" + s.policy, null)),
            new Step("access.group.member_removed", s -> s.admin.request("DELETE",
                    GROUPS + "/" + s.group + "/members/people/" + s.member, null)),
            new Step("access.data.changed", s -> s.admin.request("PUT", PROFILES + "/" + s.profile + "/data-access",
                    "{\"objects\":[{\"key\":\"ObjectA__c\",\"actions\":[\"read\"]}],\"fields\":[]}")),
            new Step("access.member.licence_taken_back", s -> s.admin.request("DELETE",
                    MEMBERS + "/" + s.member + "/licence", null)),
            new Step("membership.deactivated", s -> s.admin.postJson(MEMBERS + "/" + s.member + "/deactivate", "{}")),
            new Step("membership.reactivated", s -> s.admin.postJson(MEMBERS + "/" + s.member + "/reactivate", "{}")),
            new Step("access.group.deleted", s -> s.admin.request("DELETE", GROUPS + "/" + s.group, null)),
            new Step("access.policy.deleted", s -> s.admin.request("DELETE", POLICIES + "/" + s.policy, null)),
            new Step("access.role.deleted", s -> s.admin.request("DELETE", ROLES + "/" + s.role, null)));

    @Test
    void everySecurityChangeOfTheEarlierSprintsLeavesAnAuditEventTheViewerShows() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        Scenario scenario = new Scenario(admin, person.membership());
        Set<String> expected = new HashSet<>();
        Map<String, Integer> statuses = new java.util.LinkedHashMap<>();

        for (Step step : STEPS) {
            Response response = step.action().apply(scenario);
            statuses.put(step.expectedType(), response.status());
            expected.add(step.expectedType());
        }

        Response page = admin.get(EVENTS + "?limit=200");
        assertThat(types(page)).as("statuses of the actions " + statuses).containsAll(expected);
        assertThat(statuses.values()).as("every action succeeded: " + statuses).allMatch(s -> s >= 200 && s < 300);
    }

    @Test
    void aRefusedAdministrativeActionIsAuditedWithItsReasonAndShownToTheAdministrator() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = TestOrganizations.signedIn(port, organization.host(), person.person());

        assertThat(asPerson.get(PROFILES).status()).isEqualTo(403);
        assertThat(asPerson.get(MEMBERS).status()).isEqualTo(403);

        Response page = admin.get(EVENTS + "?kind=membership.action.refused");
        assertThat(types(page)).contains("membership.action.refused");
        assertThat(page.body()).contains("missing_ability");
        assertThat(types(admin.get(EVENTS + "?kind=access.action.refused"))).contains("access.action.refused");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noEvents")
    void anOrganizationWithNothingToShowGetsAnEmptyListNotAnError(String name, String query) {
        Organization organization = organization();

        Response page = adminOf(organization).get(EVENTS + query);

        assertThat(page.status()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(page.body(), "$.data")).isEmpty();
        assertThat((Boolean) JsonPath.read(page.body(), "$.pagination.hasMore")).isFalse();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> noEvents() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("a kind that never happened",
                        "?kind=data.record.deleted"),
                org.junit.jupiter.params.provider.Arguments.of("the far future", "?from=2999-01-01T00:00:00Z"));
    }

}
