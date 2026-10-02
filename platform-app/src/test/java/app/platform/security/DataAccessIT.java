package app.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Object and field permissions and the decision API (Sprint 8, ADR-0049, ADR-0050) through the real application and a
 * real PostgreSQL: matrices of a profile, an access policy and an individual grant, combined by the union and asked
 * through {@link Decisions}; the profile's permissions counting only while its licence is held; groups passing a
 * policy's
 * permissions; the administrator profile holding everything; validation against the catalogue; and the usual
 * authorization
 * checks (a member with only some abilities, a platform person, another organization, a forged tenant header, giving
 * away what one does not hold).
 */
@PlatformIntegrationTest
class DataAccessIT {

    private static final String PROFILES = "/api/v1/profiles";
    private static final String POLICIES = "/api/v1/access-policies";
    private static final String MEMBERS = "/api/v1/members";
    private static final String GROUPS = "/api/v1/groups";
    private static final String CATALOGUE = "/api/v1/data-catalogue";
    private static final String MINE = "/api/v1/data-access/mine";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private Decisions decisions;

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

    private TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    private static UUID idOf(Response created) {
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    private static UUID createProfile(TestBrowser admin, String name, String... abilities) {
        List<String> quoted = new ArrayList<>();
        for (String ability : abilities) {
            quoted.add("\"" + ability + "\"");
        }
        return idOf(admin.postJson(PROFILES, "{\"name\":\"" + name + "\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[" + String.join(",", quoted) + "]}"));
    }

    private static UUID createPolicy(TestBrowser admin, String name, String... abilities) {
        List<String> quoted = new ArrayList<>();
        for (String ability : abilities) {
            quoted.add("\"" + ability + "\"");
        }
        return idOf(admin.postJson(POLICIES, "{\"name\":\"" + name + "\",\"description\":\"\",\"abilities\":["
                + String.join(",", quoted) + "]}"));
    }

    private static Response setProfile(TestBrowser admin, UUID membership, UUID profile) {
        return admin.request("PUT", MEMBERS + "/" + membership + "/profile", "{\"profileId\":\"" + profile + "\"}");
    }

    /** A matrix body: objects as {@code key=action,action} and fields likewise. */
    private static String matrix(List<String> objects, List<String> fields) {
        return "{\"objects\":" + entries(objects) + ",\"fields\":" + entries(fields) + "}";
    }

    private static String entries(List<String> lines) {
        List<String> parts = new ArrayList<>();
        for (String line : lines) {
            String[] keyAndActions = line.split("=", 2);
            List<String> actions = new ArrayList<>();
            if (!keyAndActions[1].isEmpty()) {
                for (String action : keyAndActions[1].split(",")) {
                    actions.add("\"" + action + "\"");
                }
            }
            parts.add("{\"key\":\"" + keyAndActions[0] + "\",\"actions\":[" + String.join(",", actions) + "]}");
        }
        return "[" + String.join(",", parts) + "]";
    }

    private static Response putMatrix(TestBrowser admin, String path, List<String> objects, List<String> fields) {
        return admin.request("PUT", path + "/data-access", matrix(objects, fields));
    }

    private Decision can(Organization organization, UUID membership, String object, ObjectAction action) {
        return contexts.call(TenantContext.of(organization.id()), () -> decisions.can(membership, object, action));
    }

    private Decision can(Organization organization, UUID membership, String object, String field,
            FieldAction action) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> decisions.can(membership, object, field, action));
    }

    // ---- the catalogue ----

    @Test
    void theCatalogueListsTheObjectsAndFieldsAndTheActionsWithWhatTheyImply() {
        TestBrowser admin = adminOf(organization());

        Response catalogue = admin.get(CATALOGUE);

        assertThat(catalogue.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.objects[*].key"))
                .containsExactly("object-a", "object-b");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.objects[0].fields[*].key"))
                .containsExactly("field-a", "field-b", "field-c");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.objectActions[*].key"))
                .containsExactly("read", "create", "update", "delete", "view-all", "modify-all");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.objectActions[?(@.key=='update')].implies[*]"))
                .containsExactly("read");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.fieldActions[?(@.key=='edit')].implies[*]"))
                .containsExactly("read");
    }

    // ---- a profile's matrix, combined and asked ----

    @Test
    void aProfilesMatrixCountsForItsMembersWhileTheyHoldItsLicence() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = as(organization, person);
        UUID profile = createProfile(admin, "profile-a");
        Response saved = putMatrix(admin, PROFILES + "/" + profile, List.of("object-a=read,update"),
                List.of("object-a.field-a=edit", "object-a.field-b=read"));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(saved.body(), "$.data.objects[0].actions[*]"))
                .containsExactly("read", "update");

        assertThat(can(organization, person.membership(), "object-a", ObjectAction.READ).allowed())
                .as("not their profile yet").isFalse();
        assertThat(setProfile(admin, person.membership(), profile).status()).isEqualTo(204);

        assertThat(can(organization, person.membership(), "object-a", ObjectAction.READ).allowed()).isTrue();
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.UPDATE).allowed()).isTrue();
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.DELETE).allowed()).isFalse();
        assertThat(can(organization, person.membership(), "object-b", ObjectAction.READ).allowed()).isFalse();
        assertThat(can(organization, person.membership(), "object-a", "field-a", FieldAction.EDIT).allowed()).isTrue();
        assertThat(can(organization, person.membership(), "object-a", "field-a", FieldAction.READ).allowed()).isTrue();
        assertThat(can(organization, person.membership(), "object-a", "field-b", FieldAction.READ).allowed()).isTrue();
        assertThat(can(organization, person.membership(), "object-a", "field-b", FieldAction.EDIT).allowed())
                .isFalse();
        assertThat(can(organization, person.membership(), "object-a", "field-c", FieldAction.READ).allowed())
                .as("no permission for this field").isFalse();
        Response mine = asPerson.get(MINE);
        assertThat(mine.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(mine.body(), "$.data.objects[0].actions[*]"))
                .as("what they may do, implied actions written out").containsExactly("read", "update");
        assertThat(JsonPath.<List<String>>read(mine.body(), "$.data.fields[*].key"))
                .containsExactly("object-a.field-a", "object-a.field-b");

        // Licence, permission and entitlement change independently: without the licence the profile gives nothing,
        // with it again everything is back, and the matrix itself was never touched.
        assertThat(admin.request("DELETE", MEMBERS + "/" + person.membership() + "/licence", null).status())
                .isEqualTo(204);
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.READ).allowed()).isFalse();
        assertThat(JsonPath.<List<String>>read(asPerson.get(MINE).body(), "$.data.objects[*]")).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(PROFILES + "/" + profile + "/data-access").body(),
                "$.data.objects[0].actions[*]")).containsExactly("read", "update");
        assertThat(admin.request("PUT", MEMBERS + "/" + person.membership() + "/licence", null).status())
                .isEqualTo(204);
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.READ).allowed()).isTrue();
    }

    @Test
    void replacingAMatrixEndsTheLinesThatAreNotInItAndTakesEffectAtOnce() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a");
        setProfile(admin, person.membership(), profile);
        putMatrix(admin, PROFILES + "/" + profile, List.of("object-a=delete", "object-b=read"),
                List.of("object-a.field-a=read"));
        assertThat(can(organization, person.membership(), "object-b", ObjectAction.READ).allowed()).isTrue();

        Response second = putMatrix(admin, PROFILES + "/" + profile, List.of("object-a=read", "object-b="), List.of());

        assertThat(second.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(second.body(), "$.data.objects[*].key")).containsExactly("object-a");
        assertThat(can(organization, person.membership(), "object-b", ObjectAction.READ).allowed()).isFalse();
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.DELETE).allowed()).isFalse();
        assertThat(can(organization, person.membership(), "object-a", "field-a", FieldAction.READ).allowed())
                .isFalse();
        assertThat(IdentityDb.auditOfType("access.data.changed"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));
    }

    // ---- the other containers ----

    @Test
    void anAccessPolicyAnIndividualGrantAndAGroupsPolicyAddUpWithTheProfile() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a");
        setProfile(admin, person.membership(), profile);
        putMatrix(admin, PROFILES + "/" + profile, List.of("object-a=read"), List.of());
        UUID policy = createPolicy(admin, "policy-a");
        putMatrix(admin, POLICIES + "/" + policy, List.of("object-b=update"), List.of("object-b.field-a=edit"));
        UUID groupPolicy = createPolicy(admin, "policy-b");
        putMatrix(admin, POLICIES + "/" + groupPolicy, List.of("object-a=delete"), List.of());
        UUID group = idOf(admin.postJson(GROUPS, "{\"name\":\"group-a\"}"));

        assertThat(can(organization, person.membership(), "object-b", ObjectAction.UPDATE).allowed()).isFalse();
        admin.postJson(MEMBERS + "/" + person.membership() + "/policies", "{\"policyId\":\"" + policy + "\"}");
        assertThat(can(organization, person.membership(), "object-b", ObjectAction.UPDATE).allowed()).isTrue();
        assertThat(can(organization, person.membership(), "object-b", "field-a", FieldAction.EDIT).allowed())
                .isTrue();

        assertThat(can(organization, person.membership(), "object-a", ObjectAction.DELETE).allowed()).isFalse();
        admin.postJson(GROUPS + "/" + group + "/policies", "{\"policyId\":\"" + groupPolicy + "\"}");
        admin.postJson(GROUPS + "/" + group + "/members", "{\"membershipId\":\"" + person.membership() + "\"}");
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.DELETE).allowed())
                .as("through the group").isTrue();

        assertThat(can(organization, person.membership(), "object-a", ObjectAction.CREATE).allowed()).isFalse();
        Response grant = putMatrix(admin, MEMBERS + "/" + person.membership(), List.of("object-a=create"),
                List.of());
        assertThat(grant.status()).isEqualTo(200);
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.CREATE).allowed())
                .as("an individual grant").isTrue();
        Response effective = admin.get(MEMBERS + "/" + person.membership() + "/access");
        assertThat(JsonPath.<List<String>>read(effective.body(),
                "$.data.data.objects[?(@.key=='object-a')].actions[*]"))
                .containsExactly("read", "create", "delete");

        // Everything the person held through containers goes away with the containers, at once.
        admin.request("DELETE", GROUPS + "/" + group + "/members/people/" + person.membership(), null);
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.DELETE).allowed()).isFalse();
        admin.request("DELETE", MEMBERS + "/" + person.membership() + "/policies/" + policy, null);
        assertThat(can(organization, person.membership(), "object-b", ObjectAction.UPDATE).allowed()).isFalse();
        putMatrix(admin, MEMBERS + "/" + person.membership(), List.of(), List.of());
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.CREATE).allowed()).isFalse();
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.READ).allowed())
                .as("the profile still gives its part").isTrue();
    }

    @Test
    void deactivatingAMemberEndsTheirIndividualGrantsAndEveryAnswerIsNo() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a");
        setProfile(admin, person.membership(), profile);
        putMatrix(admin, PROFILES + "/" + profile, List.of("object-a=read"), List.of());
        putMatrix(admin, MEMBERS + "/" + person.membership(), List.of("object-b=read"), List.of());
        assertThat(can(organization, person.membership(), "object-a", ObjectAction.READ).allowed()).isTrue();

        assertThat(admin.postJson(MEMBERS + "/" + person.membership() + "/deactivate", "{}").status())
                .isEqualTo(204);

        assertThat(can(organization, person.membership(), "object-a", ObjectAction.READ).allowed()).isFalse();
        assertThat(can(organization, person.membership(), "object-b", ObjectAction.READ).allowed()).isFalse();
        assertThat(putMatrix(admin, MEMBERS + "/" + person.membership(), List.of("object-b=read"), List.of())
                .status()).as("no permission for a member who is not active").isEqualTo(409);
    }

    @Test
    void theAdministratorProfileHoldsEverythingButOnlyForWhatExists() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        UUID membership = organization.admin().membership();
        UUID administrator = UUID.fromString(JsonPath.<List<String>>read(admin.get(PROFILES).body(),
                "$.data[?(@.name=='Organization administrator')].id").get(0));

        Response view = admin.get(PROFILES + "/" + administrator + "/data-access");
        assertThat(JsonPath.<Boolean>read(view.body(), "$.data.everything")).isTrue();
        assertThat(putMatrix(admin, PROFILES + "/" + administrator, List.of("object-a=read"), List.of()).status())
                .isEqualTo(409);
        assertThat(JsonPath.<Boolean>read(admin.get(MINE).body(), "$.data.everything")).isTrue();

        for (ObjectAction action : ObjectAction.values()) {
            assertThat(can(organization, membership, "object-a", action).allowed()).as(action.key()).isTrue();
            assertThat(can(organization, membership, "object-z", action).allowed()).as("unknown object").isFalse();
        }
        assertThat(can(organization, membership, "object-a", "field-c", FieldAction.EDIT).allowed()).isTrue();
        assertThat(can(organization, membership, "object-a", "field-z", FieldAction.READ).allowed())
                .as("unknown field").isFalse();
    }

    @Test
    void theBulkFormGivesTheObjectsAndFieldsOfOneMemberInOneAnswer() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a");
        setProfile(admin, person.membership(), profile);
        putMatrix(admin, PROFILES + "/" + profile, List.of("object-a=update"),
                List.of("object-a.field-a=edit", "object-a.field-b=read"));

        ObjectAccess access = contexts.call(TenantContext.of(organization.id()),
                () -> decisions.accessTo(person.membership(), "object-a"));
        java.util.Map<String, ObjectAccess> all = contexts.call(TenantContext.of(organization.id()),
                () -> decisions.accessToAll(person.membership()));

        assertThat(access.actions()).containsExactlyInAnyOrder(ObjectAction.READ, ObjectAction.UPDATE);
        assertThat(access.readableFields()).containsExactlyInAnyOrder("field-a", "field-b");
        assertThat(access.editableFields()).containsExactly("field-a");
        assertThat(all.keySet()).containsExactly("object-a");
        assertThat(contexts.call(TenantContext.of(organization.id()),
                () -> decisions.accessTo(person.membership(), "object-z"))).isEqualTo(ObjectAccess.none("object-z"));
    }

    // ---- validation ----

    @Test
    void aMatrixCanOnlyNameWhatExistsAndTheActionsThePlatformKnows() {
        TestBrowser admin = adminOf(organization());
        UUID profile = createProfile(admin, "profile-a");
        String path = PROFILES + "/" + profile;

        assertThat(putMatrix(admin, path, List.of("object-z=read"), List.of()).status()).isEqualTo(400);
        assertThat(putMatrix(admin, path, List.of("object-a=fly"), List.of()).status()).isEqualTo(400);
        assertThat(putMatrix(admin, path, List.of(), List.of("object-a.field-z=read")).status()).isEqualTo(400);
        assertThat(putMatrix(admin, path, List.of(), List.of("object-a=read")).status()).as("not a field key")
                .isEqualTo(400);
        assertThat(putMatrix(admin, path, List.of(), List.of("object-a.field-a=modify-all")).status())
                .as("an object action on a field").isEqualTo(400);
        assertThat(admin.request("PUT", path + "/data-access", "{}").status()).isEqualTo(400);
        assertThat(putMatrix(admin, path, List.of("object-a=read"), List.of()).status()).isEqualTo(200);
    }

    // ---- authorization ----

    @Test
    void aMemberWithOnlySomeAbilitiesIsRefusedOnEveryMatrixEndpointButMayReadTheirOwn() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        Member target = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a", "members.view", "members.invite");
        setProfile(admin, person.membership(), profile);
        UUID policy = createPolicy(admin, "policy-a");
        TestBrowser asPerson = as(organization, person);
        String body = matrix(List.of("object-a=read"), List.of());

        List<Response> denied = List.of(
                asPerson.get(CATALOGUE),
                asPerson.get(PROFILES + "/" + profile + "/data-access"),
                asPerson.request("PUT", PROFILES + "/" + profile + "/data-access", body),
                asPerson.get(POLICIES + "/" + policy + "/data-access"),
                asPerson.request("PUT", POLICIES + "/" + policy + "/data-access", body),
                asPerson.get(MEMBERS + "/" + target.membership() + "/data-access"),
                asPerson.request("PUT", MEMBERS + "/" + target.membership() + "/data-access", body));
        for (int i = 0; i < denied.size(); i++) {
            assertThat(denied.get(i).status()).as("denied call " + i).isEqualTo(403);
        }
        assertThat(asPerson.get(MINE).status()).as("their own matrix is theirs to read").isEqualTo(200);
    }

    @Test
    void aMemberWhoMayInviteCannotGiveAProfileWhosePermissionsTheyDoNotHold() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member inviter = TestOrganizations.join(users, organization.tenant(), false);
        UUID inviterProfile = createProfile(admin, "profile-a", "members.invite");
        setProfile(admin, inviter.membership(), inviterProfile);
        putMatrix(admin, PROFILES + "/" + inviterProfile, List.of("object-a=read"), List.of());
        UUID bigger = createProfile(admin, "profile-b");
        putMatrix(admin, PROFILES + "/" + bigger, List.of("object-a=read", "object-b=delete"), List.of());
        UUID same = createProfile(admin, "profile-c");
        putMatrix(admin, PROFILES + "/" + same, List.of("object-a=read"), List.of());
        TestBrowser asInviter = as(organization, inviter);

        Response refused = asInviter.postJson("/api/v1/invitations", "{\"email\":\"person-a@example.test\","
                + "\"profileId\":\"" + bigger + "\"}");
        Response allowed = asInviter.postJson("/api/v1/invitations", "{\"email\":\"person-b@example.test\","
                + "\"profileId\":\"" + same + "\"}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("permissions");
        assertThat(allowed.status()).as(allowed.body()).isIn(200, 201, 202, 204);
    }

    @Test
    void aPlatformAdministratorHasNoAuthorityOverMatrices() {
        Organization organization = organization();
        TestUser platformAdmin = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser onPlatform = TestPlatform.signedIn(port, platformAdmin);
        UUID profile = createProfile(adminOf(organization), "profile-a");

        for (String path : List.of(CATALOGUE, MINE, PROFILES + "/" + profile + "/data-access",
                MEMBERS + "/" + organization.admin().membership() + "/data-access")) {
            assertThat(onPlatform.get(path).status()).as(path).isEqualTo(404);
        }
        assertThat(onPlatform.request("PUT", PROFILES + "/" + profile + "/data-access",
                matrix(List.of("object-a=read"), List.of())).status()).isEqualTo(404);
    }

    @Test
    void anIdentifierOfAnotherOrganizationIsSimplyNotFound() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        TestBrowser theirAdmin = adminOf(theirs);
        UUID theirProfile = createProfile(theirAdmin, "profile-a");
        UUID theirPolicy = createPolicy(theirAdmin, "policy-a");
        Member theirMember = TestOrganizations.join(users, theirs.tenant(), false);
        String body = matrix(List.of("object-a=read"), List.of());

        assertThat(admin.get(PROFILES + "/" + theirProfile + "/data-access").status()).isEqualTo(404);
        assertThat(admin.request("PUT", PROFILES + "/" + theirProfile + "/data-access", body).status())
                .isEqualTo(404);
        assertThat(admin.get(POLICIES + "/" + theirPolicy + "/data-access").status()).isEqualTo(404);
        assertThat(admin.request("PUT", POLICIES + "/" + theirPolicy + "/data-access", body).status())
                .isEqualTo(404);
        assertThat(admin.get(MEMBERS + "/" + theirMember.membership() + "/data-access").status()).isEqualTo(404);
        assertThat(admin.request("PUT", MEMBERS + "/" + theirMember.membership() + "/data-access", body).status())
                .isEqualTo(404);
        assertThat(can(mine, theirMember.membership(), "object-a", ObjectAction.READ).allowed())
                .as("a member of another organization is nobody here").isFalse();
    }

    @Test
    void aForgedTenantHeaderChangesNothingForReadsAndWrites() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        UUID profile = createProfile(admin, "profile-a");
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
                "X-Organization", theirs.tenant().slug()};

        for (String path : List.of(CATALOGUE, MINE, PROFILES + "/" + profile + "/data-access")) {
            Response plain = admin.get(path);
            Response withHeaders = admin.get(path, forged);
            assertThat(withHeaders.status()).as(path).isEqualTo(plain.status());
            assertThat(strip(withHeaders.body())).as(path).isEqualTo(strip(plain.body()));
        }
        assertThat(admin.request("PUT", PROFILES + "/" + profile + "/data-access",
                matrix(List.of("object-a=read"), List.of()), forged).status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(PROFILES + "/" + profile + "/data-access").body(),
                "$.data.objects[*].key")).containsExactly("object-a");
    }

    private static String strip(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "-").replaceAll("\"traceId\":\"[^\"]*\"", "-");
    }
}
