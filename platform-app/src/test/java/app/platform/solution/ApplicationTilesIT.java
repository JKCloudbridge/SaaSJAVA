package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The five tiles of FA13A and the rule that lets Document Management show the same documents as the Quality Management
 * System (ADR-0225): each tile has a plan feature of its own, the tiles stand first in the grid in a fixed order that
 * is data, the shared objects and access policies exist while either application's feature is on, and nothing is stored
 * twice. The words of the solutions that ship are in these tests because they test what ships.
 */
@PlatformIntegrationTest
class ApplicationTilesIT extends SolutionSupport {

    private static final String DOCUMENTS = "document-management";
    private static final List<String> NEW_TILES = List.of(DOCUMENTS, "capa", "audit-management",
            "supplier-management", "management-core");
    private static final Map<String, String> LABELS = Map.of(DOCUMENTS, "Document Management", "capa", "CAPA",
            "audit-management", "Audit Management", "supplier-management", "Supplier Management",
            "management-core", "Management Core");
    private static final Map<String, Integer> TABS = Map.of(DOCUMENTS, 8, "capa", 1, "audit-management", 1,
            "supplier-management", 3, "management-core", 8);
    private static final List<String> FOUR_OBJECTS = List.of("InspectionTemplate", "InspectionFill",
            "InspectionChangeRequest", "DepartmentGroup");

    private Organization withPlan(String... features) {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 8, 3, features);
        return organization;
    }

    private void override(Organization organization, String feature, boolean on) {
        entitlements.override(organization.id(), feature, on, ActorId.SYSTEM);
    }

    private static List<String> objectNames(TestBrowser admin) {
        return JsonPath.read(admin.get(METADATA_OBJECTS).body(), "$.data[*].apiName");
    }

    // ---- one tile, one switch ----

    @Test
    void eachNewFeatureShowsItsOwnTileWithItsOwnTabsAndNothingElse() {
        for (String feature : NEW_TILES) {
            Organization organization = withPlan(feature);
            TestBrowser admin = adminOf(organization);

            Response mine = admin.get(MY_APPLICATIONS);

            assertThat(mine.status()).as(feature + " " + mine.body()).isEqualTo(200);
            assertThat(JsonPath.<List<String>>read(mine.body(), "$.data[*].apiName")).as(feature)
                    .containsExactly(feature);
            assertThat(JsonPath.<String>read(mine.body(), "$.data[0].label")).isEqualTo(LABELS.get(feature));
            assertThat(JsonPath.<List<Object>>read(mine.body(), "$.data[0].items[*]")).as(feature)
                    .hasSize(TABS.get(feature));
            assertThat(JsonPath.<String>read(mine.body(), "$.data[0].items[0].pageKey")).isEqualTo("home");
        }
    }

    @Test
    void theOrganizationsOwnOverrideDecidesOverThePlanForEachNewFeature() {
        for (String feature : NEW_TILES) {
            Organization planned = withPlan(feature);
            Organization unplanned = organization();
            TestBrowser withPlan = adminOf(planned);
            TestBrowser without = adminOf(unplanned);
            assertThat(applicationsOf(withPlan)).as(feature + " by plan").contains(feature);
            assertThat(applicationsOf(without)).as(feature + " without").doesNotContain(feature);

            override(planned, feature, false);
            override(unplanned, feature, true);

            assertThat(applicationsOf(withPlan)).as(feature + " switched off over a plan that has it")
                    .doesNotContain(feature);
            assertThat(applicationsOf(without)).as(feature + " switched on over a plan that lacks it")
                    .contains(feature);
        }
    }

    @Test
    void aTileIsNotShownToAnotherOrganizationNorToAnAnonymousVisitor() {
        Organization mine = withPlan(DOCUMENTS);
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        TestBrowser other = adminOf(theirs);
        assertThat(applicationsOf(admin)).contains(DOCUMENTS);

        assertThat(applicationsOf(other)).doesNotContain(DOCUMENTS);
        assertThat(objectNames(other)).doesNotContainAnyElementsOf(FOUR_OBJECTS);
        assertThat(JsonPath.<List<String>>read(other.get(POLICIES).body(), "$.data[*].name"))
                .doesNotContainAnyElementsOf(SHIPPED_POLICIES);
        assertThat(new TestBrowser(port, mine.host()).get(MY_APPLICATIONS).status()).isEqualTo(401);
    }

    @Test
    void aForgedHostOrTenantHeaderChangesNothingAboutATile() {
        Organization mine = organization();
        Organization theirs = withPlan(DOCUMENTS);
        TestBrowser admin = adminOf(mine);
        assertThat(applicationsOf(adminOf(theirs))).contains(DOCUMENTS);
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response menu = admin.get(MY_APPLICATIONS, forged);

        assertThat(JsonPath.<List<String>>read(menu.body(), "$.data[*].apiName")).doesNotContain(DOCUMENTS);
        assertThat(admin.get(RECORDS, forged).status()).isEqualTo(404);
    }

    // ---- the order ----

    @Test
    void theNewTilesStandFirstInTheirOrderAndTheOthersFollowAsBefore() {
        Organization organization = withPlan("inspection", "process-applications", DOCUMENTS, "capa",
                "audit-management", "supplier-management", "management-core");
        TestBrowser admin = adminOf(organization);

        List<String> names = applicationsOf(admin);

        assertThat(names.subList(0, 5)).containsExactly(DOCUMENTS, "capa", "audit-management",
                "supplier-management", "management-core");
        assertThat(names.get(5)).isEqualTo("inspection");
        assertThat(names.subList(6, names.size())).isNotEmpty().doesNotContainAnyElementsOf(NEW_TILES)
                .doesNotContain("inspection");
    }

    @Test
    void aTileThatIsOffLeavesTheOthersInPlace() {
        Organization organization = withPlan("inspection", DOCUMENTS, "supplier-management");
        TestBrowser admin = adminOf(organization);

        assertThat(applicationsOf(admin)).containsExactly(DOCUMENTS, "supplier-management", "inspection");
    }

    // ---- Document Management shares the Quality Management System's objects and policies ----

    @Test
    void documentManagementAloneHasTheFourObjectsTheRecordsAndTheSharedPolicies() {
        Organization organization = withPlan(DOCUMENTS);
        TestBrowser admin = adminOf(organization);

        assertThat(applicationsOf(admin)).containsExactly(DOCUMENTS);
        assertThat(objectNames(admin)).containsAll(FOUR_OBJECTS);
        assertThat(admin.get(RECORDS).status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES).body(), "$.data[*].name"))
                .containsAll(SHIPPED_POLICIES);
        Response created = admin.postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName() + "\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    @Test
    void theQualitySystemAloneShowsNoDocumentManagementAndKeepsItsOwnPolicies() {
        Organization organization = withPlan("inspection");
        TestBrowser admin = adminOf(organization);

        assertThat(applicationsOf(admin)).containsExactly("inspection");
        assertThat(JsonPath.<List<String>>read(admin.get(POLICIES).body(), "$.data[*].name"))
                .containsAll(SHIPPED_POLICIES);
    }

    @Test
    void withBothFeaturesBothTilesShowAndEachPolicyExistsOnce() {
        Organization organization = withPlan("inspection", DOCUMENTS);
        TestBrowser admin = adminOf(organization);

        assertThat(applicationsOf(admin)).containsExactly(DOCUMENTS, "inspection");
        assertThat(applicationsOf(admin)).as("asked again, nothing is made twice").containsExactly(DOCUMENTS,
                "inspection");

        List<String> names = JsonPath.read(admin.get(POLICIES).body(), "$.data[?(@.managed==true)].name");
        assertThat(names).containsExactlyInAnyOrderElementsOf(SHIPPED_POLICIES);
    }

    @Test
    void aDocumentMadeThroughOneTileIsInTheOtherBecauseTheyShowTheSameRecords() {
        Organization organization = withPlan("inspection", DOCUMENTS);
        TestBrowser admin = adminOf(organization);
        assertThat(applicationsOf(admin)).contains(DOCUMENTS, "inspection");
        String name = newName();
        Response created = admin.postJson(RECORDS, "{\"fields\":{\"name\":\"" + name + "\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = JsonPath.read(created.body(), "$.data.id");

        override(organization, "inspection", false);
        assertThat(applicationsOf(admin)).as("only Document Management is left").containsExactly(DOCUMENTS);
        Response viaDocuments = admin.get(RECORDS + "/" + id);
        assertThat(viaDocuments.status()).as(viaDocuments.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(viaDocuments.body(), "$.data.fields.name")).isEqualTo(name);

        override(organization, "inspection", true);
        override(organization, DOCUMENTS, false);
        assertThat(applicationsOf(admin)).as("only the Quality Management System is left")
                .containsExactly("inspection");
        Response viaQuality = admin.get(RECORDS + "/" + id);
        assertThat(viaQuality.status()).as(viaQuality.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(viaQuality.body(), "$.data.fields.name")).isEqualTo(name);
    }

    @Test
    void withBothSwitchedOffTheObjectsAnswerNotFoundLikeAnUnknownObject() {
        Organization organization = withPlan("inspection", DOCUMENTS);
        TestBrowser admin = adminOf(organization);
        assertThat(applicationsOf(admin)).contains(DOCUMENTS);

        override(organization, "inspection", false);
        override(organization, DOCUMENTS, false);

        assertThat(applicationsOf(admin)).doesNotContain(DOCUMENTS, "inspection");
        assertThat(objectNames(admin)).doesNotContainAnyElementsOf(FOUR_OBJECTS);
        Response shipped = admin.get(RECORDS);
        Response unknown = admin.get("/api/v1/objects/NoSuchThing/records");
        assertThat(shipped.status()).isEqualTo(unknown.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(shipped.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
    }

    @Test
    void sharedPoliciesWorkForAMemberWhoseOnlyApplicationIsDocumentManagement() {
        Organization organization = withPlan(DOCUMENTS);
        TestBrowser admin = adminOf(organization);
        var reader = memberHolding(organization, admin, "Reader");

        assertThat(applicationsOf(as(organization, reader))).containsExactly(DOCUMENTS);
        assertThat(as(organization, reader).get(RECORDS).status()).isEqualTo(200);
        assertThat(as(organization, reader).postJson(RECORDS, "{\"fields\":{\"name\":\"" + newName() + "\"}}")
                .status()).isEqualTo(403);
    }
}
