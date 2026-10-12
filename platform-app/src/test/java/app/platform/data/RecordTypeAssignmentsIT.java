package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which profiles may use a record type (Sprint 15A, ADR-0098), through the real API: the rule (no assignment means open
 * to every profile), the defaults of a profile, every refusal, the dependency with the record type, a profile that is
 * deleted, a change set that makes a type and its assignments together, the rollback that undoes them, and who may do
 * what.
 */
@PlatformIntegrationTest
class RecordTypeAssignmentsIT extends PipelineSupport {

    private static final String DEAL = METADATA_OBJECTS + "/Deal__c";
    private static final String RELEASES = "/api/v1/metadata/releases";
    private static final String PROFILES = "/api/v1/profiles";

    private TestBrowser deals(Organization organization) {
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Deal", "Deals");
        for (String type : new String[] {
            "{\"name\":\"NewBusiness\",\"label\":\"New business\",\"defaultType\":true}",
            "{\"name\":\"Renewal\",\"label\":\"Renewal\"}",
            "{\"name\":\"Partner\",\"label\":\"Partner\"}",
            "{\"name\":\"Closed\",\"label\":\"Closed\",\"active\":false}"}) {
            Response created = admin.postJson(DEAL + "/record-types", type);
            assertThat(created.status()).as(created.body()).isEqualTo(201);
        }
        return admin;
    }

    private static String profile(TestBrowser admin, String name) {
        Response created = admin.postJson(PROFILES, "{\"name\":\"" + name + "-" + UUID.randomUUID()
                + "\",\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[]}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    private static String profiles(String type) {
        return DEAL + "/record-types/" + type + "/profiles";
    }

    private static Response assign(TestBrowser admin, String type, String profile, boolean makeDefault) {
        return admin.postJson(profiles(type), "{\"profileId\":\"" + profile + "\",\"defaultForProfile\":"
                + makeDefault + "}");
    }

    private static List<String> keys(Response listed) {
        return JsonPath.read(listed.body(), "$.data[*].key");
    }

    private static boolean isDefault(TestBrowser admin, String type, String profile) {
        return JsonPath.read(admin.get(profiles(type) + "/" + profile).body(), "$.data.defaultForProfile");
    }

    // ---- the rule and the reading ----

    @Test
    void aTypeWithNoAssignmentListsNobodyWhichMeansItIsOpenToEveryProfile() {
        TestBrowser admin = deals(organization());

        Response ofType = admin.get(profiles("Renewal__c"));
        Response ofObject = admin.get(DEAL + "/record-type-assignments");

        assertThat(ofType.status()).isEqualTo(200);
        assertThat(keys(ofType)).isEmpty();
        assertThat(keys(ofObject)).isEmpty();
    }

    @Test
    void anAssignmentIsMadeListedWithTheNameOfTheProfileAndCannotBeMadeTwice() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");

        Response created = assign(admin, "Renewal__c", sales, false);
        Response again = assign(admin, "Renewal__c", sales, false);

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.data.key")).isEqualTo("Renewal__c~" + sales);
        assertThat(JsonPath.<String>read(created.body(), "$.data.profileName")).startsWith("sales-");
        assertThat(JsonPath.<Boolean>read(created.body(), "$.data.defaultForProfile")).isFalse();
        assertThat(keys(admin.get(profiles("Renewal__c")))).containsExactly("Renewal__c~" + sales);
        assertThat(keys(admin.get(DEAL + "/record-type-assignments"))).containsExactly("Renewal__c~" + sales);
        assertThat(keys(admin.get(profiles("Partner__c")))).as("another type is not touched").isEmpty();
        assertThat(again.status()).isEqualTo(400);
        assertThat(problemAt(again, "profileId")).isEqualTo("This profile may use the record type already.");
    }

    // ---- the default of a profile ----

    @Test
    void aProfileHasOneDefaultPerObjectAndMakingAnotherTheDefaultTakesTheMarkOffTheFirst() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");
        assign(admin, "NewBusiness__c", sales, true);

        Response second = assign(admin, "Renewal__c", sales, true);

        assertThat(second.status()).as(second.body()).isEqualTo(201);
        assertThat(isDefault(admin, "Renewal__c", sales)).isTrue();
        assertThat(isDefault(admin, "NewBusiness__c", sales)).as("the first lost the mark").isFalse();
    }

    @Test
    void aDefaultCanBeSetAndTakenOffWithTheVersionReadAndAStaleVersionIsRefused() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");
        Response made = assign(admin, "Renewal__c", sales, false);
        long version = ((Number) JsonPath.read(made.body(), "$.data.version")).longValue();

        Response set = admin.request("PUT", profiles("Renewal__c") + "/" + sales,
                "{\"defaultForProfile\":true,\"version\":" + version + "}");
        Response stale = admin.request("PUT", profiles("Renewal__c") + "/" + sales,
                "{\"defaultForProfile\":false,\"version\":" + version + "}");
        long now = ((Number) JsonPath.read(set.body(), "$.data.version")).longValue();
        Response off = admin.request("PUT", profiles("Renewal__c") + "/" + sales,
                "{\"defaultForProfile\":false,\"version\":" + now + "}");

        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(set.body(), "$.data.defaultForProfile")).isTrue();
        assertThat(stale.status()).isEqualTo(409);
        assertThat(errorCode(stale)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(off.body(), "$.data.defaultForProfile")).isFalse();
    }

    // ---- every refusal ----

    @Test
    void aProfileThatDoesNotExistAndADefaultThatIsNotInUseAreRefusedWithTheirPlace() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");

        Response ghost = assign(admin, "Renewal__c", UUID.randomUUID().toString(), false);
        Response closed = assign(admin, "Closed__c", sales, true);
        Response closedNotDefault = assign(admin, "Closed__c", sales, false);

        assertThat(ghost.status()).isEqualTo(400);
        assertThat(problemAt(ghost, "profileId")).isEqualTo("This profile does not exist.");
        assertThat(closed.status()).isEqualTo(400);
        assertThat(problemAt(closed, "defaultForProfile")).contains("not in use");
        assertThat(closedNotDefault.status()).as("a profile may be allowed a type that is not in use")
                .isEqualTo(201);
    }

    @Test
    void aRecordTypeThatDoesNotExistAnObjectThatDoesNotExistAndAPlatformObjectAreRefused() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");
        String body = "{\"profileId\":\"" + sales + "\"}";

        Response noType = admin.postJson(profiles("Nope__c"), body);
        Response noObject = admin.postJson(METADATA_OBJECTS + "/Nothing__c/record-types/Renewal__c/profiles", body);
        Response platformObject = admin.postJson(METADATA_OBJECTS + "/User/record-types/Renewal__c/profiles", body);

        assertThat(noType.status()).as("the lifecycle refuses an assignment of a type that does not exist")
                .isEqualTo(409);
        assertThat(noObject.status()).isEqualTo(404);
        assertThat(platformObject.status()).isEqualTo(409);
        assertThat(keys(admin.get(DEAL + "/record-type-assignments"))).isEmpty();
    }

    @Test
    void anAssignmentThatDoesNotExistIsNotFound() {
        TestBrowser admin = deals(organization());

        assertThat(admin.get(profiles("Renewal__c") + "/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(admin.get(profiles("Renewal__c") + "/not-a-profile").status()).isEqualTo(404);
        assertThat(admin.request("DELETE", profiles("Renewal__c") + "/" + UUID.randomUUID(), null).status())
                .isEqualTo(404);
    }

    // ---- the record type and the profile ----

    @Test
    void aRecordTypeThatStillHasAssignmentsCannotBeRemovedAndTheRefusalNamesThemUntilTheyAreRemoved() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");
        assign(admin, "Renewal__c", sales, false);

        Response refused = admin.request("DELETE", DEAL + "/record-types/Renewal__c", null);
        admin.request("DELETE", profiles("Renewal__c") + "/" + sales, null);
        Response removed = admin.request("DELETE", DEAL + "/record-types/Renewal__c", null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("record type assignment").contains("Renewal__c");
        assertThat(removed.status()).as(removed.body()).isEqualTo(204);
    }

    @Test
    void deletingAProfileIsNeverBlockedAndItsAssignmentStaysVisibleWithoutANameUntilItIsRemoved() {
        TestBrowser admin = deals(organization());
        String doomed = profile(admin, "doomed");
        assign(admin, "Partner__c", doomed, false);

        Response deleted = admin.request("DELETE", PROFILES + "/" + doomed, null);
        Response listed = admin.get(profiles("Partner__c"));
        Response removed = admin.request("DELETE", profiles("Partner__c") + "/" + doomed, null);

        assertThat(deleted.status()).as(deleted.body()).isIn(200, 204);
        assertThat(keys(listed)).containsExactly("Partner__c~" + doomed);
        assertThat(listed.body()).as("no name for a profile that is gone").doesNotContain("profileName");
        assertThat(removed.status()).isEqualTo(204);
        assertThat(keys(admin.get(profiles("Partner__c")))).isEmpty();
    }

    // ---- change sets and rollback ----

    @Test
    void aChangeSetMakesATypeAndItsAssignmentsTogetherAndARollbackUndoesBoth() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");
        String set = createSet(admin, "gold");
        Response type = admin.postJson(SETS + "/" + set + "/changes", "{\"kind\":\"CREATE_RECORD_TYPE\","
                + "\"objectApiName\":\"Deal__c\",\"createRecordType\":{\"name\":\"Gold\",\"label\":\"Gold\"}}");
        Response assignment = admin.postJson(SETS + "/" + set + "/changes",
                "{\"kind\":\"CREATE_RECORD_TYPE_ASSIGNMENT\",\"objectApiName\":\"Deal__c\","
                        + "\"createRecordTypeAssignment\":{\"recordType\":\"Gold__c\",\"profileId\":\"" + sales
                        + "\",\"defaultForProfile\":true}}");
        Response published = publish(admin, set);

        assertThat(type.status()).as(type.body()).isEqualTo(201);
        assertThat(assignment.status()).as(assignment.body()).isEqualTo(201);
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(keys(admin.get(DEAL + "/record-type-assignments"))).containsExactly("Gold__c~" + sales);
        assertThat(isDefault(admin, "Gold__c", sales)).isTrue();

        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(keys(admin.get(DEAL + "/record-type-assignments"))).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(DEAL + "/record-types").body(), "$.data[*].apiName"))
                .doesNotContain("Gold__c");
    }

    @Test
    void aChangeSetThatAssignsATypeNobodyMakesIsRefusedWholeAndLeavesNothing() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");
        String set = createSet(admin, "broken");
        admin.postJson(SETS + "/" + set + "/changes",
                "{\"kind\":\"CREATE_RECORD_TYPE_ASSIGNMENT\",\"objectApiName\":\"Deal__c\","
                        + "\"createRecordTypeAssignment\":{\"recordType\":\"Ghost__c\",\"profileId\":\"" + sales
                        + "\"}}");

        Response published = publish(admin, set);

        assertThat(published.status()).isEqualTo(409);
        assertThat(keys(admin.get(DEAL + "/record-type-assignments"))).isEmpty();
    }

    @Test
    void aRollbackPutsTheMarkBackOnThePreviousDefault() {
        TestBrowser admin = deals(organization());
        String sales = profile(admin, "sales");
        assign(admin, "NewBusiness__c", sales, true);
        assign(admin, "Renewal__c", sales, true);
        assertThat(isDefault(admin, "NewBusiness__c", sales)).isFalse();

        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(keys(admin.get(DEAL + "/record-type-assignments"))).containsExactly("NewBusiness__c~" + sales);
        assertThat(isDefault(admin, "NewBusiness__c", sales)).as("the first default is the default again").isTrue();
    }

    // ---- who may do what ----

    @Test
    void aMemberWithNoMetadataAbilityCanNeitherReadNorChangeAssignments() {
        Organization organization = organization();
        TestBrowser admin = deals(organization);
        String sales = profile(admin, "sales");
        assign(admin, "Renewal__c", sales, false);
        TestBrowser member = as(organization, holderWith(organization, admin, "Deal__c", "[]", "read").member());
        String path = profiles("Renewal__c");

        assertThat(member.get(path).status()).isEqualTo(403);
        assertThat(member.get(DEAL + "/record-type-assignments").status()).isEqualTo(403);
        assertThat(member.get(path + "/" + sales).status()).isEqualTo(403);
        assertThat(member.postJson(path, "{\"profileId\":\"" + sales + "\"}").status()).isEqualTo(403);
        assertThat(member.request("PUT", path + "/" + sales, "{\"defaultForProfile\":true,\"version\":0}").status())
                .isEqualTo(403);
        assertThat(member.request("DELETE", path + "/" + sales, null).status()).isEqualTo(403);
        assertThat(keys(admin.get(path))).as("nothing was changed").containsExactly("Renewal__c~" + sales);
    }

    @Test
    void anotherOrganizationNeverSeesOrChangesAnAssignmentEvenWithAForgedHeader() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = deals(mine);
        TestBrowser other = deals(theirs);
        String sales = profile(admin, "sales");
        assign(admin, "Renewal__c", sales, false);
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        assertThat(keys(other.get(profiles("Renewal__c")))).isEmpty();
        assertThat(keys(other.get(DEAL + "/record-type-assignments"))).isEmpty();
        assertThat(other.get(profiles("Renewal__c") + "/" + sales).status()).isEqualTo(404);
        assertThat(other.request("DELETE", profiles("Renewal__c") + "/" + sales, null).status()).isEqualTo(404);
        assertThat(assign(other, "Renewal__c", sales, false).status()).as("a profile of another organization")
                .isEqualTo(400);
        Response forgedList = admin.get(profiles("Renewal__c"), forged);
        assertThat(keys(forgedList)).as("the forged header changes nothing").containsExactly("Renewal__c~" + sales);
        assertThat(keys(admin.get(profiles("Renewal__c")))).containsExactly("Renewal__c~" + sales);
    }
}
