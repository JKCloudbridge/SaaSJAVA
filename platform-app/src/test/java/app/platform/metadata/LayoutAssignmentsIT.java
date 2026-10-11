package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which layout an object uses for a profile and a record type (Sprint 12, ADR-0071): the fixed order of the rules
 * (profile with record type, record type, profile, default), assignments that name something that does not exist,
 * and the profile that is deleted (its assignments are ignored, deleting is never blocked).
 */
@PlatformIntegrationTest
class LayoutAssignmentsIT extends DefinitionsSupport {

    private static final String ASSIGNMENTS = OBJECTS + "/Deal__c/layout-assignments";
    private static final String RESOLUTION = OBJECTS + "/Deal__c/layout-resolution";

    private TestBrowser adminWithThreeLayoutsAndARecordType() {
        TestBrowser admin = adminWithDealAndContact();
        layoutExists(admin, "Deal__c", "Standard", DEAL_LAYOUT);
        layoutExists(admin, "Deal__c", "ForProfile", DEAL_LAYOUT_TWO);
        layoutExists(admin, "Deal__c", "ForType", DEAL_LAYOUT);
        layoutExists(admin, "Deal__c", "ForBoth", DEAL_LAYOUT_TWO);
        Response type = createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        assertThat(type.status()).as(type.body()).isEqualTo(201);
        return admin;
    }

    private static Response assign(TestBrowser admin, String profile, String recordType, String layout) {
        return admin.postJson(ASSIGNMENTS, "{" + (profile == null ? "" : "\"profileId\":\"" + profile + "\",")
                + (recordType == null ? "" : "\"recordType\":\"" + recordType + "\",") + "\"layout\":\"" + layout
                + "\"}");
    }

    private static Response resolve(TestBrowser admin, String profile, String recordType) {
        String query = (profile == null ? "" : "profileId=" + profile)
                + (profile != null && recordType != null ? "&" : "")
                + (recordType == null ? "" : "recordType=" + recordType);
        return admin.get(RESOLUTION + (query.isEmpty() ? "" : "?" + query));
    }

    private static String layoutOf(Response resolved) {
        return JsonPath.<String>read(resolved.body(), "$.data.layout");
    }

    private static String ruleOf(Response resolved) {
        return JsonPath.<String>read(resolved.body(), "$.data.rule");
    }

    @Test
    void theFirstRuleOfTheFixedOrderThatAppliesWins() {
        TestBrowser admin = adminWithThreeLayoutsAndARecordType();
        String profile = newProfile(admin);
        String other = newProfile(admin);

        Response none = resolve(admin, profile, "Renewal__c");
        assertThat(ruleOf(none)).isEqualTo("NONE");
        assertThat(none.body()).doesNotContain("\"layout\"");

        assertThat(assign(admin, null, null, "Standard__c").status()).isEqualTo(201);
        assertThat(ruleOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("DEFAULT");
        assertThat(layoutOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("Standard__c");

        assertThat(assign(admin, profile, null, "ForProfile__c").status()).isEqualTo(201);
        assertThat(ruleOf(resolve(admin, profile, null))).isEqualTo("PROFILE");
        assertThat(layoutOf(resolve(admin, profile, null))).isEqualTo("ForProfile__c");
        assertThat(ruleOf(resolve(admin, other, null))).as("another profile still gets the default")
                .isEqualTo("DEFAULT");

        Response type = admin.get(OBJECTS + "/Deal__c/record-types/Renewal__c");
        Response typed = admin.request("PUT", OBJECTS + "/Deal__c/record-types/Renewal__c",
                "{\"label\":\"Renewal\",\"description\":\"\",\"layout\":\"ForType__c\",\"version\":" + versionOf(type)
                        + "}");
        assertThat(typed.status()).as(typed.body()).isEqualTo(200);
        assertThat(ruleOf(resolve(admin, profile, "Renewal__c"))).as("the record type beats the profile")
                .isEqualTo("RECORD_TYPE");
        assertThat(layoutOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("ForType__c");
        assertThat(ruleOf(resolve(admin, profile, null))).as("no record type: the profile").isEqualTo("PROFILE");

        assertThat(assign(admin, profile, "Renewal__c", "ForBoth__c").status()).isEqualTo(201);
        assertThat(ruleOf(resolve(admin, profile, "Renewal__c"))).as("the most specific rule").isEqualTo(
                "PROFILE_AND_RECORD_TYPE");
        assertThat(layoutOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("ForBoth__c");
        assertThat(layoutOf(resolve(admin, other, "Renewal__c"))).as("the others keep the record type's layout")
                .isEqualTo("ForType__c");
    }

    @Test
    void anAssignmentReadsBackAndCanBeChangedAndRemoved() {
        TestBrowser admin = adminWithThreeLayoutsAndARecordType();
        String profile = newProfile(admin);
        Response made = assign(admin, profile, "Renewal__c", "ForBoth__c");
        String key = JsonPath.read(made.body(), "$.data.key");

        Response read = admin.get(ASSIGNMENTS + "/" + key);
        Response changed = admin.request("PUT", ASSIGNMENTS + "/" + key,
                "{\"layout\":\"Standard__c\",\"version\":" + versionOf(read) + "}");

        assertThat(key).isEqualTo(profile + "~Renewal__c");
        assertThat(json(read, "$.data.layout")).isEqualTo("ForBoth__c");
        assertThat(json(read, "$.data.recordType")).isEqualTo("Renewal__c");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(layoutOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("Standard__c");
        assertThat(JsonPath.<List<String>>read(admin.get(ASSIGNMENTS).body(), "$.data[*].key")).containsExactly(key);
        assertThat(admin.request("DELETE", ASSIGNMENTS + "/" + key, null).status()).isEqualTo(204);
        assertThat(admin.get(ASSIGNMENTS + "/" + key).status()).isEqualTo(404);
        assertThat(ruleOf(resolve(admin, profile, "Renewal__c"))).isEqualTo("NONE");
    }

    @Test
    void theDefaultHasItsOwnKeyAndAStaleVersionIsRefused() {
        TestBrowser admin = adminWithThreeLayoutsAndARecordType();
        Response made = assign(admin, null, null, "Standard__c");
        long version = versionOf(made);
        admin.request("PUT", ASSIGNMENTS + "/default", "{\"layout\":\"ForType__c\",\"version\":" + version + "}");

        Response stale = admin.request("PUT", ASSIGNMENTS + "/default",
                "{\"layout\":\"ForBoth__c\",\"version\":" + version + "}");

        assertThat(json(made, "$.data.key")).isEqualTo("default");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(json(stale, "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(layoutOf(resolve(admin, null, null))).isEqualTo("ForType__c");
    }

    @Test
    void aRecordTypeNeedsAProfileAndAnAssignmentIsMadeOnce() {
        TestBrowser admin = adminWithThreeLayoutsAndARecordType();
        String profile = newProfile(admin);

        Response noProfile = assign(admin, null, "Renewal__c", "ForBoth__c");
        assign(admin, profile, null, "ForProfile__c");
        Response twice = assign(admin, profile, null, "Standard__c");
        Response shape = assign(admin, profile, "not a name", "Standard__c");

        assertThat(noProfile.status()).isEqualTo(400);
        assertThat(places(noProfile)).containsExactly("recordType");
        assertThat(twice.status()).isEqualTo(400);
        assertThat(places(twice)).containsExactly("profileId");
        assertThat(shape.status()).isEqualTo(400);
    }

    @Test
    void anAssignmentNeedsItsLayoutAndItsRecordTypeAndKeepsThemWhileItIsUsed() {
        TestBrowser admin = adminWithThreeLayoutsAndARecordType();
        String profile = newProfile(admin);

        Response ghostLayout = assign(admin, profile, null, "Ghost__c");
        Response ghostType = assign(admin, profile, "Ghost__c", "ForBoth__c");
        Response good = assign(admin, profile, "Renewal__c", "ForBoth__c");
        Response removeLayout = admin.request("DELETE", OBJECTS + "/Deal__c/layouts/ForBoth__c", null);
        Response removeType = admin.request("DELETE", OBJECTS + "/Deal__c/record-types/Renewal__c", null);

        assertThat(ghostLayout.status()).isEqualTo(409);
        assertThat(json(ghostLayout, "$.error.message")).contains("page layout Ghost__c of Deal__c, which does not "
                + "exist");
        assertThat(ghostType.status()).isEqualTo(409);
        assertThat(good.status()).as(good.body()).isEqualTo(201);
        assertThat(removeLayout.status()).isEqualTo(409);
        assertThat(json(removeLayout, "$.error.message")).contains("the layout assignment " + profile
                + "~Renewal__c of Deal__c uses page layout ForBoth__c");
        assertThat(removeType.status()).isEqualTo(409);
        assertThat(json(removeType, "$.error.message")).contains("applies to record type Renewal__c of Deal__c");
        assertThat(JsonPath.<List<String>>read(admin.get(ASSIGNMENTS).body(), "$.data[*].layout"))
                .containsExactly("ForBoth__c");
    }

    @Test
    void deletingAProfileIsNeverBlockedAndItsAssignmentIsIgnored() {
        TestBrowser admin = adminWithThreeLayoutsAndARecordType();
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
    void aKeyThatIsNotAKeyIsNotFoundAndAPlatformObjectHasNoAssignments() {
        TestBrowser admin = adminWithThreeLayoutsAndARecordType();

        assertThat(admin.get(ASSIGNMENTS + "/not-a-key").status()).isEqualTo(404);
        assertThat(admin.get(OBJECTS + "/Nothing__c/layout-assignments").status()).isEqualTo(404);
        Response platform = admin.postJson(OBJECTS + "/User/layout-assignments", "{\"layout\":\"Standard__c\"}");
        assertThat(platform.status()).isEqualTo(409);
    }
}
