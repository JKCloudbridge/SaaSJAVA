package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.Metadata;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The shipped application as organizations meet it (Functional track FA1, ADR-0175, ADR-0179, ADR-0181): it exists
 * for an organization only while its plan has the feature, asked on every request and never stored; an organization
 * without it sees nothing and gets "not found" where the object would be; losing the feature hides everything and
 * deletes nothing; no row of the organization is written for the application; and a change of plan moves no cache the
 *answers sit in. /
@PlatformIntegrationTest
class SolutionsIT extends SolutionSupport {

    @Autowired
    private Metadata metadata;

    @Autowired
    private Decisions decisions;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private TransactionTemplate transaction;

    private boolean objectExists(Organization organization) {
        return Boolean.TRUE.equals(contexts.call(TenantContext.of(organization.id()),
                () -> transaction.execute(status -> metadata.object(OBJECT).isPresent())));
    }

    private boolean mayRead(Organization organization, Member member) {
        return Boolean.TRUE.equals(contexts.call(TenantContext.of(organization.id()),
                () -> transaction.execute(status -> decisions.can(member.membership(), OBJECT, ObjectAction.READ)
                        .allowed())));
    }

    // ---- an organization without the feature ----

    @Test
    void anOrganizationWithoutTheFeatureSeesNothingAndGetsNotFoundWhereTheObjectWouldBe() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);

        assertThat(applicationsOf(admin)).doesNotContain(APPLICATION);
        assertThat(JsonPath.<List<String>>read(admin.get(METADATA_OBJECTS).body(), "$.data[*].apiName"))
                .doesNotContain(OBJECT);
        assertThat(admin.get(METADATA_OBJECTS + "/" + OBJECT).status()).isEqualTo(404);
        assertThat(admin.get(RECORDS).status()).isEqualTo(404);
        assertThat(admin.postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName() + "\"}}").status()).isEqualTo(404);
        assertThat(admin.get("/api/v1/objects/" + OBJECT + "/form").status()).isEqualTo(404);
        assertThat(admin.get("/api/v1/objects/" + OBJECT + "/recycle-bin").status()).isEqualTo(404);
        assertThat(admin.postJson(RECORDS + "/search", "{\"text\":\"a\"}").status()).isEqualTo(404);
        assertThat(admin.get("/api/v1/metadata/objects/" + OBJECT + "/relationships").status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(admin.get("/api/v1/data-catalogue").body(), "$.data.objects[*].key"))
                .doesNotContain(OBJECT);
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES).body(), "$.data[*].name"))
                .as("no shipped policy is made for an organization that does not have the feature")
                .doesNotContainAnyElementsOf(SHIPPED_POLICIES);
        assertThat(objectExists(organization)).isFalse();
    }

    @Test
    void anUnknownFeatureAndAFeatureTheOrganizationLacksAnswerAlike() {
        Organization lacking = organization();
        TestBrowser admin = adminOf(lacking);

        Response shipped = admin.get(RECORDS);
        Response unknown = admin.get("/api/v1/objects/NoSuchThing/records");

        assertThat(shipped.status()).isEqualTo(unknown.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(shipped.body(), "$.error.code"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.code"));
        assertThat(JsonPath.<String>read(shipped.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
    }

    // ---- an organization with the feature ----

    @Test
    void anOrganizationWithTheFeatureSeesAnEmptyAuditMaintenanceApplication() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);

        Response mine = admin.get(MY_APPLICATIONS);

        assertThat(mine.status()).as(mine.body()).isEqualTo(200);
        String application = "$.data[?(@.apiName=='" + APPLICATION + "')]";
        assertThat(JsonPath.<List<String>>read(mine.body(), application + ".label"))
                .containsExactly("Audit maintenance");
        assertThat(JsonPath.<List<String>>read(mine.body(), application + ".iconKey")).containsExactly("clipboard");
        assertThat(JsonPath.<List<Object>>read(mine.body(), application + ".items[*]")).hasSize(2);
        assertThat(JsonPath.<List<String>>read(mine.body(), application + ".items[0].type"))
                .containsExactly("PLATFORM");
        assertThat(JsonPath.<List<String>>read(mine.body(), application + ".items[0].label"))
                .containsExactly("Templates");
        assertThat(JsonPath.<List<String>>read(mine.body(), application + ".items[0].pageKey"))
                .containsExactly("templates");
        Response records = admin.get(RECORDS);
        assertThat(records.status()).as(records.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(records.body(), "$.data")).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(METADATA_OBJECTS).body(), "$.data[*].apiName"))
                .contains(OBJECT);
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES).body(), "$.data[*].name"))
                .containsAll(SHIPPED_POLICIES);
    }

    @Test
    void theFeatureIsAskedFromTheEntitlementSoAnOverrideOverThePlanDecides() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(applicationsOf(admin)).contains(APPLICATION);

        switchOff(organization);

        assertThat(applicationsOf(admin)).as("switched off over a plan that has it").doesNotContain(APPLICATION);
    }

    // ---- the gate: feature x member x read right ----

    @Test
    void theGateMatrixHoldsForTheMenuTheObjectLookupAndTheRecordEndpoints() {
        Organization organization = organization();
        switchOn(organization);
        TestBrowser admin = adminOf(organization);
        Member reader = memberHolding(organization, admin, "Reader");
        Member plain = plainMember(organization);
        TestBrowser readerBrowser = as(organization, reader);
        TestBrowser plainBrowser = as(organization, plain);

        for (boolean on : new boolean[] {true, false, true}) {
            if (on) {
                switchOn(organization);
            } else {
                switchOff(organization);
            }
            String state = on ? "feature on" : "feature off";

            assertThat(applicationsOf(admin).contains(APPLICATION)).as("admin menu, " + state).isEqualTo(on);
            assertThat(applicationsOf(readerBrowser).contains(APPLICATION)).as("reader menu, " + state).isEqualTo(on);
            assertThat(applicationsOf(plainBrowser)).as("member without a policy, " + state)
                    .doesNotContain(APPLICATION);

            assertThat(admin.get(RECORDS).status()).as("admin list, " + state).isEqualTo(on ? 200 : 404);
            assertThat(readerBrowser.get(RECORDS).status()).as("reader list, " + state).isEqualTo(on ? 200 : 404);
            assertThat(plainBrowser.get(RECORDS).status()).as("member without a policy, list, " + state).isEqualTo(404);
            assertThat(admin.get(METADATA_OBJECTS + "/" + OBJECT).status()).as("admin lookup, " + state)
                    .isEqualTo(on ? 200 : 404);

            assertThat(mayRead(organization, organization.admin())).as("admin decision, " + state).isEqualTo(on);
            assertThat(mayRead(organization, reader)).as("reader decision, " + state).isEqualTo(on);
            assertThat(mayRead(organization, plain)).as("member without a policy, decision, " + state).isFalse();
            assertThat(objectExists(organization)).as("lookup, " + state).isEqualTo(on);
        }
    }

    @Test
    void aReaderMayReadButNotCreateAndTheAnswerForAWriteIsForbiddenNotNotFound() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        Member reader = memberHolding(organization, admin, "Reader");

        Response created = as(organization, reader).postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName() + "\"}}");

        assertThat(created.status()).as(created.body()).isEqualTo(403);
        assertThat(as(organization, reader).get(RECORDS).status()).isEqualTo(200);
    }

    // ---- losing the feature keeps the data ----

    @Test
    void losingTheFeatureHidesEverythingAndKeepsTheDataAndThePolicies() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        // The shipped policies are made lazily, at the first menu request (ADR-0181 section 5); a record create
        // alone never makes them.
        assertThat(applicationsOf(admin)).contains(APPLICATION);
        String name = newName();
        Response created = admin.postJson(RECORDS, "{\"fields\":{\"name\":\"" + name + "\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = JsonPath.read(created.body(), "$.data.id");
        List<String> policyIds = JsonPath.read(admin.get(POLICIES).body(), "$.data[?(@.managed==true)].id");
        assertThat(policyIds).hasSize(SHIPPED_POLICIES.size());

        switchOff(organization);

        assertThat(applicationsOf(admin)).doesNotContain(APPLICATION);
        assertThat(admin.get(RECORDS + "/" + id).status()).isEqualTo(404);
        assertThat(admin.get(RECORDS).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES).body(), "$.data[?(@.managed==true)].id"))
                .as("the shipped policies stay in place after the feature is lost")
                .containsExactlyInAnyOrderElementsOf(policyIds);

        switchOn(organization);

        Response back = admin.get(RECORDS + "/" + id);
        assertThat(back.status()).as(back.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(back.body(), "$.data.fields.name")).as("nothing was deleted").isEqualTo(name);
    }

    // ---- no tenant row for the application ----

    @Test
    void noRowOfTheOrganizationIsWrittenForTheApplicationItsEntriesOrItsPages() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        Member reader = memberHolding(organization, admin, "Reader");

        assertThat(applicationsOf(as(organization, reader))).contains(APPLICATION);
        assertThat(applicationsOf(admin)).contains(APPLICATION);

        assertThat(applicationRows(organization.id()))
                .as("rows in the tables an organization's own applications, tabs, pages and layouts live in")
                .isZero();
        assertThat(JsonPath.<List<Object>>read(admin.get("/api/v1/metadata/applications").body(), "$.data[*]"))
                .as("the organization's own list of applications does not show it")
                .isEmpty();
    }

    // ---- other organizations ----

    @Test
    void anotherOrganizationWithoutTheFeatureSeesNothingOfIt() {
        Organization mine = organizationWithFeature();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        TestBrowser other = adminOf(theirs);
        Response created = admin.postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName() + "\"}}");
        String id = JsonPath.read(created.body(), "$.data.id");
        assertThat(applicationsOf(admin)).contains(APPLICATION);

        assertThat(applicationsOf(other)).doesNotContain(APPLICATION);
        assertThat(other.get(RECORDS + "/" + id).status()).isEqualTo(404);
        assertThat(other.get(RECORDS).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(other.get(POLICIES).body(), "$.data[*].name"))
                .doesNotContainAnyElementsOf(SHIPPED_POLICIES);
    }

    @Test
    void anotherOrganizationWithTheFeatureNeverSeesTheRecordsOfThisOne() {
        Organization mine = organizationWithFeature();
        Organization theirs = organizationWithFeature();
        TestBrowser admin = adminOf(mine);
        TestBrowser other = adminOf(theirs);
        String id = JsonPath.read(admin.postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName() + "\"}}").body(),
                "$.data.id");
        // The shipped policies are made lazily, at the first menu request (ADR-0181 section 5).
        assertThat(applicationsOf(admin)).contains(APPLICATION);
        assertThat(applicationsOf(other)).contains(APPLICATION);

        assertThat(other.get(RECORDS + "/" + id).status()).isEqualTo(404);
        assertThat(JsonPath.<List<Object>>read(other.get(RECORDS).body(), "$.data")).isEmpty();
        assertThat(policyIds(admin)).as("each organization has policies of its own")
                .doesNotContainAnyElementsOf(policyIds(other));
    }

    @Test
    void aForgedTenantHeaderOrHostChangesNothing() {
        Organization mine = organization();
        Organization theirs = organizationWithFeature();
        TestBrowser admin = adminOf(mine);
        TestBrowser other = adminOf(theirs);
        assertThat(applicationsOf(other)).contains(APPLICATION);
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response menu = admin.get(MY_APPLICATIONS, forged);
        Response records = admin.get(RECORDS, forged);
        Response create = admin.request("POST", RECORDS, "{\"fields\":{\"name\":\"" + newName() + "\"}}", forged);

        assertThat(JsonPath.<List<String>>read(menu.body(), "$.data[*].apiName")).doesNotContain(APPLICATION);
        assertThat(records.status()).isEqualTo(404);
        assertThat(create.status()).isEqualTo(404);
        assertThat(JsonPath.<List<Object>>read(other.get(RECORDS).body(), "$.data")).isEmpty();
    }

    @Test
    void theUnauthenticatedAndThePlatformAddressGetNothing() {
        Organization organization = organizationWithFeature();

        Response anonymous = new TestBrowser(port, organization.host()).get(MY_APPLICATIONS);

        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("Audit maintenance");
    }

    // ---- the caches ----

    @Test
    void aChangeOfPlanFeatureChangesEveryAnswerAtOnceAndNoStaleAllowedSurvives() {
        Organization organization = organization();
        switchOn(organization);
        TestBrowser admin = adminOf(organization);
        Member reader = memberHolding(organization, admin, "Reader");
        TestBrowser browser = as(organization, reader);
        // Warm everything that is cached: the security answer by the security version, the metadata snapshot by the
        // metadata version.
        assertThat(mayRead(organization, reader)).isTrue();
        assertThat(mayRead(organization, reader)).isTrue();
        assertThat(objectExists(organization)).isTrue();
        assertThat(browser.get(RECORDS).status()).isEqualTo(200);
        assertThat(applicationsOf(browser)).contains(APPLICATION);

        switchOff(organization);

        assertThat(applicationsOf(browser)).as("menu").doesNotContain(APPLICATION);
        assertThat(objectExists(organization)).as("object lookup").isFalse();
        assertThat(browser.get(RECORDS).status()).as("record endpoint").isEqualTo(404);
        assertThat(mayRead(organization, reader)).as("permission decision").isFalse();

        switchOn(organization);

        assertThat(applicationsOf(browser)).as("menu again").contains(APPLICATION);
        assertThat(objectExists(organization)).as("object lookup again").isTrue();
        assertThat(browser.get(RECORDS).status()).as("record endpoint again").isEqualTo(200);
        assertThat(mayRead(organization, reader)).as("permission decision again").isTrue();
    }

    @Test
    void theMemberKeepsWhatTheyWereGivenWhileTheFeatureIsAwayAndGetsItBack() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        Member reader = memberHolding(organization, admin, "Reader");
        switchOff(organization);
        assertThat(applicationsOf(as(organization, reader))).doesNotContain(APPLICATION);

        switchOn(organization);

        assertThat(applicationsOf(as(organization, reader))).contains(APPLICATION);
    }

    private static List<String> policyIds(TestBrowser browser) {
        return JsonPath.read(browser.get(POLICIES).body(), "$.data[?(@.managed==true)].id");
    }

    @Test
    void theIdentifiersAreOnlyAsUniqueAsThePolicyOfOneOrganization() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(applicationsOf(admin)).contains(APPLICATION);

        List<String> ids = policyIds(admin);

        assertThat(ids).hasSize(SHIPPED_POLICIES.size()).doesNotHaveDuplicates().allSatisfy(id -> UUID.fromString(id));
    }
}
