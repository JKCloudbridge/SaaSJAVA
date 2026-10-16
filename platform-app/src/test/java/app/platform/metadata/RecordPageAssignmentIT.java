package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which record page an object uses for a profile and a record type (Sprint 20, ADR-0131): the fixed order of the rules
 * (profile with record type, record type, profile, default), assignments that name something that does not exist, the
 * profile that is deleted (its assignments are ignored, deleting is never blocked), the release and the undo that
 * every change makes, and the limit per object.
 */
@PlatformIntegrationTest
class RecordPageAssignmentIT extends DefinitionsSupport {

    private static final String ASSIGNMENTS = OBJECTS + "/Deal__c/record-page-assignments";
    private static final String RESOLUTION = OBJECTS + "/Deal__c/record-page-resolution";

    private TestBrowser adminWithFourPagesAndARecordType(Organization organization) {
        TestBrowser admin = adminWithDealAndContact(organization);
        layoutExists(admin, "Deal__c", "Details", DEAL_LAYOUT);
        for (String name : List.of("Standard", "ForProfile", "ForType", "ForBoth")) {
            Response page = createRecordPage(admin, "Deal__c", name, pageDefinition("Details__c"));
            assertThat(page.status()).as(page.body()).isEqualTo(201);
        }
        Response type = createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        assertThat(type.status()).as(type.body()).isEqualTo(201);
        return admin;
    }

    private static Response assign(TestBrowser admin, String profile, String recordType, String page) {
        return admin.postJson(ASSIGNMENTS, "{" + (profile == null ? "" : "\"profileId\":\"" + profile + "\",")
                + (recordType == null ? "" : "\"recordType\":\"" + recordType + "\",") + "\"recordPage\":\"" + page
                + "\"}");
    }

    private static Response resolve(TestBrowser admin, String profile, String recordType) {
        String query = (profile == null ? "" : "profileId=" + profile)
                + (profile != null && recordType != null ? "&" : "")
                + (recordType == null ? "" : "recordType=" + recordType);
        return admin.get(RESOLUTION + (query.isEmpty() ? "" : "?" + query));
    }

    private static String pageOf(Response resolved) {
        return JsonPath.<String>read(resolved.body(), "$.data.recordPage");
    }

    private static String ruleOf(Response resolved) {
        return JsonPath.<String>read(resolved.body(), "$.data.rule");
    }

    @Test
    void theFirstRuleOfTheFixedOrderThatAppliesWins() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());
        String profile = newProfile(admin);
        String other = newProfile(admin);

        Response none = resolve(admin, profile, "Renewal__c");
        assertThat(ruleOf(none)).isEqualTo("NONE");
        assertThat(none.body()).doesNotContain("\"recordPage\"");

        assertThat(assign(admin, null, null, "Standard__c").status()).isEqualTo(201);
        assertThat(ruleOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("DEFAULT");
        assertThat(pageOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("Standard__c");

        assertThat(assign(admin, profile, null, "ForProfile__c").status()).isEqualTo(201);
        assertThat(ruleOf(resolve(admin, profile, null))).isEqualTo("PROFILE");
        assertThat(pageOf(resolve(admin, profile, null))).isEqualTo("ForProfile__c");
        assertThat(ruleOf(resolve(admin, other, null))).as("another profile still gets the default")
                .isEqualTo("DEFAULT");

        assertThat(assign(admin, null, "Renewal__c", "ForType__c").status()).isEqualTo(201);
        assertThat(ruleOf(resolve(admin, profile, "Renewal__c"))).as("the record type beats the profile")
                .isEqualTo("RECORD_TYPE");
        assertThat(pageOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("ForType__c");
        assertThat(ruleOf(resolve(admin, profile, null))).as("no record type: the profile").isEqualTo("PROFILE");
        assertThat(ruleOf(resolve(admin, other, "Renewal__c"))).as("the type applies to every profile")
                .isEqualTo("RECORD_TYPE");

        assertThat(assign(admin, profile, "Renewal__c", "ForBoth__c").status()).isEqualTo(201);
        assertThat(ruleOf(resolve(admin, profile, "Renewal__c"))).as("the most specific rule")
                .isEqualTo("PROFILE_AND_RECORD_TYPE");
        assertThat(pageOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("ForBoth__c");
        assertThat(pageOf(resolve(admin, other, "Renewal__c"))).as("the others keep the record type's page")
                .isEqualTo("ForType__c");
    }

    @Test
    void anAssignmentReadsBackAndCanBeChangedAndRemoved() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());
        String profile = newProfile(admin);
        Response made = assign(admin, profile, "Renewal__c", "ForBoth__c");
        String key = JsonPath.read(made.body(), "$.data.key");

        Response read = admin.get(ASSIGNMENTS + "/" + key);
        Response changed = admin.request("PUT", ASSIGNMENTS + "/" + key,
                "{\"recordPage\":\"Standard__c\",\"version\":" + versionOf(read) + "}");

        assertThat(key).isEqualTo(profile + "~Renewal__c");
        assertThat(json(read, "$.data.recordPage")).isEqualTo("ForBoth__c");
        assertThat(json(read, "$.data.recordType")).isEqualTo("Renewal__c");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(pageOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("Standard__c");
        assertThat(JsonPath.<List<String>>read(admin.get(ASSIGNMENTS).body(), "$.data[*].key")).containsExactly(key);
        assertThat(admin.request("DELETE", ASSIGNMENTS + "/" + key, null).status()).isEqualTo(204);
        assertThat(admin.get(ASSIGNMENTS + "/" + key).status()).isEqualTo(404);
        assertThat(ruleOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("NONE");
    }

    @Test
    void theDefaultAndARecordTypeAloneHaveTheirOwnKeysAndAStaleVersionIsRefused() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());
        Response made = assign(admin, null, null, "Standard__c");
        Response typed = assign(admin, null, "Renewal__c", "ForType__c");
        long version = versionOf(made);
        admin.request("PUT", ASSIGNMENTS + "/default", "{\"recordPage\":\"ForType__c\",\"version\":" + version + "}");

        Response stale = admin.request("PUT", ASSIGNMENTS + "/default",
                "{\"recordPage\":\"ForBoth__c\",\"version\":" + version + "}");

        assertThat(json(made, "$.data.key")).isEqualTo("default");
        assertThat(json(typed, "$.data.key")).isEqualTo("type~Renewal__c");
        assertThat(admin.get(ASSIGNMENTS + "/type~Renewal__c").status()).isEqualTo(200);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(json(stale, "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(pageOf(resolve(admin, null, null))).isEqualTo("ForType__c");
    }

    @Test
    void anAssignmentIsMadeOnceAndItsNamesMustBeShaped() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());
        String profile = newProfile(admin);

        assign(admin, profile, null, "ForProfile__c");
        Response twice = assign(admin, profile, null, "Standard__c");
        Response shape = assign(admin, profile, "not a name", "Standard__c");
        Response blank = assign(admin, null, null, " ");

        assertThat(twice.status()).isEqualTo(400);
        assertThat(places(twice)).containsExactly("profileId");
        assertThat(shape.status()).isEqualTo(400);
        assertThat(blank.status()).isEqualTo(400);
    }

    @Test
    void anAssignmentNeedsItsRecordPageAndItsRecordTypeAndKeepsThemWhileItIsUsed() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());
        String profile = newProfile(admin);

        Response ghostPage = assign(admin, profile, null, "Ghost__c");
        Response ghostType = assign(admin, profile, "Ghost__c", "ForBoth__c");
        Response good = assign(admin, profile, "Renewal__c", "ForBoth__c");
        Response removePage = admin.request("DELETE", OBJECTS + "/Deal__c/record-pages/ForBoth__c", null);
        Response removeType = admin.request("DELETE", OBJECTS + "/Deal__c/record-types/Renewal__c", null);

        assertThat(ghostPage.status()).isEqualTo(409);
        assertThat(json(ghostPage, "$.error.message")).contains("Ghost__c").contains("does not exist");
        assertThat(ghostType.status()).isEqualTo(409);
        assertThat(good.status()).as(good.body()).isEqualTo(201);
        assertThat(removePage.status()).as("a page that an assignment uses stays").isEqualTo(409);
        assertThat(removeType.status()).as("a record type that an assignment uses stays").isEqualTo(409);
        assertThat(JsonPath.<List<String>>read(admin.get(ASSIGNMENTS).body(), "$.data[*].recordPage"))
                .containsExactly("ForBoth__c");
    }

    @Test
    void deletingAProfileIsNeverBlockedAndItsAssignmentIsIgnored() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());
        String profile = newProfile(admin);
        String other = newProfile(admin);
        assign(admin, null, null, "Standard__c");
        assign(admin, profile, null, "ForProfile__c");
        Response named = admin.get(ASSIGNMENTS + "/" + profile);

        Response deleted = admin.request("DELETE", "/api/v1/profiles/" + profile, null);
        Response afterwards = admin.get(ASSIGNMENTS + "/" + profile);

        assertThat(JsonPath.<String>read(named.body(), "$.data.profileName")).startsWith("profile-");
        assertThat(deleted.status()).as(deleted.body()).isIn(200, 204);
        assertThat(afterwards.status()).isEqualTo(200);
        assertThat(afterwards.body()).doesNotContain("profileName");
        assertThat(ruleOf(resolve(admin, other, null))).as("everyone else is unaffected").isEqualTo("DEFAULT");
        assertThat(JsonPath.<List<String>>read(admin.get(ASSIGNMENTS).body(), "$.data[*].key"))
                .containsExactly("default", profile);
    }

    @Test
    void everyChangeIsAReleaseThatCanBeRolledBack() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());
        assign(admin, null, null, "Standard__c");
        Response releases = admin.get("/api/v1/metadata/releases");
        assertThat(releases.status()).as(releases.body()).isEqualTo(200);

        Response check = admin.postJson("/api/v1/metadata/releases/latest/rollback-check", "{}");
        Response rolledBack = admin.postJson("/api/v1/metadata/releases/latest/rollback", "{}");

        assertThat(check.status()).as(check.body()).isEqualTo(200);
        assertThat(rolledBack.status()).as(rolledBack.body()).isIn(200, 201);
        assertThat(ruleOf(resolve(admin, null, null))).as("the undo removed the assignment").isEqualTo("NONE");
    }

    @Test
    void aKeyThatIsNotAKeyIsNotFoundAndAPlatformObjectHasNoAssignments() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());

        assertThat(admin.get(ASSIGNMENTS + "/not-a-key").status()).isEqualTo(404);
        assertThat(admin.get(ASSIGNMENTS + "/type~").status()).isEqualTo(404);
        assertThat(admin.get(OBJECTS + "/Nothing__c/record-page-assignments").status()).isEqualTo(404);
        Response platform = admin.postJson(OBJECTS + "/User/record-page-assignments",
                "{\"recordPage\":\"Standard__c\"}");
        assertThat(platform.status()).isEqualTo(409);
    }

    @Test
    void aMemberWithoutTheAbilitiesCannotReadOrChangeAssignments() {
        Organization mine = organization();
        TestBrowser admin = adminWithFourPagesAndARecordType(mine);
        assign(admin, null, null, "Standard__c");
        Holder plain = holderOf(mine, admin);
        TestBrowser member = as(mine, plain.member());

        assertThat(member.get(ASSIGNMENTS).status()).isEqualTo(403);
        assertThat(member.get(RESOLUTION).status()).isEqualTo(403);
        assertThat(member.postJson(ASSIGNMENTS, "{\"recordPage\":\"Standard__c\"}").status()).isEqualTo(403);
        assertThat(member.request("DELETE", ASSIGNMENTS + "/default", null).status()).isEqualTo(403);
    }

    @Test
    void anotherOrganizationNeverSeesTheAssignments() {
        TestBrowser admin = adminWithFourPagesAndARecordType(organization());
        assign(admin, null, null, "Standard__c");
        TestBrowser stranger = adminWithDealAndContact(organization());

        Response listed = stranger.get(ASSIGNMENTS);

        assertThat(listed.status()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(listed.body(), "$.data")).isEmpty();
        assertThat(ruleOf(resolve(stranger, null, null))).isEqualTo("NONE");
    }
}
