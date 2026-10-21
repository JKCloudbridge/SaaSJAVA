package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The organization's own list of values for a picklist the platform defines (FA2A, ADR-0182), through the real API: the
 * administrator's change of the list and the pairs, what the platform keeps as its own, that another organization never
 * sees it, that a value is only made inactive, that a rollback goes back to the platform's list, and the addition of
 * one
 * value from the form with its own ability, its refusals and its audit by counts.
 */
@PlatformIntegrationTest
class OrganizationValuesIT extends SolutionSupport {

    private static final String FIELDS = METADATA_OBJECTS + "/" + OBJECT + "/fields";
    private static final String CATEGORY = FIELDS + "/category";
    private static final String SUB = FIELDS + "/subCategory";

    private static Response object(TestBrowser browser) {
        return browser.get(METADATA_OBJECTS + "/" + OBJECT);
    }

    private static String field(Response object, String name, String property) {
        List<Object> found = JsonPath.read(object.body(), "$.data.fields[?(@.apiName=='" + name + "')]." + property);
        return found.isEmpty() ? null : String.valueOf(found.get(0));
    }

    private static List<String> values(TestBrowser browser, String name) {
        return JsonPath.read(object(browser).body(), "$.data.fields[?(@.apiName=='" + name
                + "')].settings.values[*].value");
    }

    private static List<String> allowedFor(TestBrowser browser, String category) {
        List<List<String>> found = JsonPath.read(object(browser).body(), "$.data.fields[?(@.apiName=='subCategory')]"
                + ".settings.dependency.allowed." + category);
        return found.isEmpty() ? List.of() : found.get(0);
    }

    private static long version(TestBrowser browser, String name) {
        return Long.parseLong(field(object(browser), name, "version"));
    }

    /** A field update that leaves every part the platform owns as it is and gives the list of values. */
    private static String update(TestBrowser browser, String name, String valuesJson, String dependencyJson,
            long version) {
        Response now = object(browser);
        String label = JsonPath.<List<String>>read(now.body(), "$.data.fields[?(@.apiName=='" + name + "')].label")
                .get(0);
        String description = JsonPath.<List<String>>read(now.body(), "$.data.fields[?(@.apiName=='" + name
                + "')].description").get(0).replace("\"", "\\\"");
        return "{\"label\":\"" + label + "\",\"description\":\"" + description + "\",\"settings\":{\"values\":"
                + valuesJson + (dependencyJson == null ? "" : ",\"dependency\":" + dependencyJson)
                + "},\"version\":" + version + "}";
    }

    private static String option(String value, boolean active) {
        return "{\"value\":\"" + value + "\",\"label\":\"" + value + "\",\"active\":" + active + "}";
    }

    private static Response change(TestBrowser browser, String path, String name, List<String> codes,
            String inactive, String dependencyJson) {
        List<String> options = new java.util.ArrayList<>();
        for (String code : codes) {
            options.add(option(code, !code.equals(inactive)));
        }
        Response now = object(browser);
        String label = JsonPath.<List<String>>read(now.body(), "$.data.fields[?(@.apiName=='" + name + "')].label")
                .get(0);
        String description = JsonPath.<List<String>>read(now.body(), "$.data.fields[?(@.apiName=='" + name
                + "')].description").get(0).replace("\"", "\\\"");
        return browser.request("PUT", path, "{\"label\":\"" + label + "\",\"description\":\"" + description + "\","
                + "\"settings\":{\"values\":[" + String.join(",", options) + "]"
                + (dependencyJson == null ? "" : ",\"dependency\":" + dependencyJson) + "},\"version\":"
                + version(browser, name) + "}");
    }

    private static Response add(TestBrowser browser, String name, String json) {
        return browser.postJson(FIELDS + "/" + name + "/values", json);
    }

    private static Response record(TestBrowser browser, String category, String sub) {
        return browser.postJson(RECORDS, "{\"fields\":{\"name\":\"t\",\"category\":\"" + category
                + "\",\"subCategory\":\"" + sub + "\"}}");
    }

    // ---- the platform ships a list; the organization owns it from then on ----

    @Test
    void theFieldCarriesThePlatformsStartingListAndSaysTheOrganizationOwnsIt() {
        TestBrowser admin = adminOf(organizationWithFeature());

        Response object = object(admin);

        assertThat(field(object, "category", "organizationValues")).isEqualTo("true");
        assertThat(field(object, "category", "codeValues")).isEqualTo("true");
        assertThat(field(object, "number", "platformWritten")).isEqualTo("true");
        assertThat(field(object, "category", "editable")).as("the field is the platform's").isEqualTo("false");
        assertThat(values(admin, "category")).hasSize(16).contains("WI", "IMS", "FEHS");
        assertThat(field(object, "category", "version")).isEqualTo("0");
        assertThat(allowedFor(admin, "WI")).contains("PRD");
    }

    @Test
    void theAdministratorAddsAValueAndItsPairsAndAnotherOrganizationNeverSeesThem() {
        Organization mine = organizationWithFeature();
        Organization other = organizationWithFeature();
        TestBrowser admin = adminOf(mine);
        TestBrowser otherAdmin = adminOf(other);
        List<String> subs = new java.util.ArrayList<>(values(admin, "subCategory"));
        subs.add("NEWAB");

        Response added = change(admin, SUB, "subCategory", subs, null, "{\"controllingField\":\"category\","
                + "\"allowed\":" + allowedWith(admin, "WI", "NEWAB") + "}");

        assertThat(added.status()).as(added.body()).isEqualTo(200);
        assertThat(values(admin, "subCategory")).contains("NEWAB");
        assertThat(allowedFor(admin, "WI")).contains("NEWAB", "PRD");
        assertThat(version(admin, "subCategory")).isEqualTo(1);
        assertThat(record(admin, "WI", "NEWAB").status()).as("the new pair is a pair").isEqualTo(201);
        assertThat(record(admin, "SOP", "NEWAB").status()).as("not for the categories it was not given to")
                .isEqualTo(400);
        assertThat(values(otherAdmin, "subCategory")).doesNotContain("NEWAB");
        assertThat(version(otherAdmin, "subCategory")).isZero();
        assertThat(record(otherAdmin, "WI", "NEWAB").status()).isEqualTo(400);
    }

    /** The whole matrix of the sub-category as JSON, with one more value allowed for one category. */
    private static String allowedWith(TestBrowser browser, String category, String value) {
        java.util.Map<String, List<String>> matrix = JsonPath.<List<java.util.Map<String, List<String>>>>read(
                object(browser).body(), "$.data.fields[?(@.apiName=='subCategory')].settings.dependency.allowed")
                .get(0);
        java.util.Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
        matrix.forEach((key, list) -> copy.put(key, new java.util.ArrayList<>(list)));
        copy.get(category).add(value);
        List<String> parts = new java.util.ArrayList<>();
        copy.forEach((key, list) -> parts.add("\"" + key + "\":[" + String.join(",",
                list.stream().map(item -> "\"" + item + "\"").toList()) + "]"));
        return "{" + String.join(",", parts) + "}";
    }

    @Test
    void aValueIsNeverRemovedOnlyMadeInactiveAndAnInactiveValueStopsBeingOffered() {
        TestBrowser admin = adminOf(organizationWithFeature());
        List<String> categories = values(admin, "category");

        List<String> without = new java.util.ArrayList<>(categories);
        without.remove("BP");
        Response removed = change(admin, CATEGORY, "category", without, null, null);
        Response off = change(admin, CATEGORY, "category", categories, "BP", null);

        assertThat(removed.status()).as(removed.body()).isEqualTo(400);
        assertThat(removed.body()).contains("cannot be removed once it exists");
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Boolean>>read(object(admin).body(), "$.data.fields[?(@.apiName=='category')]"
                + ".settings.values[?(@.value=='BP')].active")).containsExactly(false);
        assertThat(admin.postJson(RECORDS, "{\"fields\":{\"name\":\"t\",\"category\":\"BP\"}}").status())
                .as("a value that is off cannot be chosen").isEqualTo(400);
    }

    @Test
    void whatThePlatformOwnsOfTheFieldCannotBeChangedWithTheList() {
        TestBrowser admin = adminOf(organizationWithFeature());
        long version = version(admin, "category");

        Response relabel = admin.request("PUT", CATEGORY, "{\"label\":\"Mine\",\"description\":\"\",\"settings\":"
                + "{\"values\":[" + option("WI", true) + "]},\"version\":" + version + "}");
        Response require = admin.request("PUT", CATEGORY, "{\"label\":\"Category\",\"required\":true,"
                + "\"description\":\"\",\"settings\":{\"values\":[" + option("WI", true) + "]},\"version\":"
                + version + "}");
        Response removeField = admin.request("DELETE", CATEGORY, null);
        Response numberChange = admin.request("PUT", FIELDS + "/number", "{\"label\":\"Number\",\"description\":\"\","
                + "\"version\":0}");

        for (Response refused : List.of(relabel, require, removeField, numberChange)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(403);
        }
        assertThat(version(admin, "category")).as("nothing was stored").isZero();
    }

    @Test
    void aCodeThatIsNotAShortCodeIsRefusedAndAChangeBasedOnAnOldVersionIsToo() {
        TestBrowser admin = adminOf(organizationWithFeature());
        List<String> categories = new java.util.ArrayList<>(values(admin, "category"));
        categories.add("not a code");

        Response bad = change(admin, CATEGORY, "category", categories, null, null);
        List<String> good = new java.util.ArrayList<>(values(admin, "category"));
        good.add("NEWK");
        long stale = version(admin, "category");
        assertThat(change(admin, CATEGORY, "category", good, null, null).status()).isEqualTo(200);
        Response late = admin.request("PUT", CATEGORY, update(admin, "category", "[" + option("WI", true) + "]",
                null, stale));

        assertThat(bad.status()).as(bad.body()).isEqualTo(400);
        assertThat(bad.body()).contains("a code of 1 to 10").doesNotContain("not a code");
        assertThat(late.status()).as(late.body()).isIn(400, 409);
    }

    // ---- the lifecycle ----

    @Test
    void aRollbackUndoesASwitchOffGoesBackToThePlatformsListAndTheVersionOnlyGoesUp() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(change(admin, CATEGORY, "category", values(admin, "category"), "BP", null).status()).isEqualTo(200);
        assertThat(owner("select cast(config as text) like '%false%' from organization_field_values "
                + "where tenant_id = ?::uuid and field_api_name = 'category'", organization.id().value().toString()))
                .containsExactly("t");

        Response rolledBack = admin.postJson("/api/v1/metadata/releases/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Boolean>>read(object(admin).body(), "$.data.fields[?(@.apiName=='category')]"
                + ".settings.values[?(@.value=='BP')].active")).containsExactly(true);
        assertThat(version(admin, "category")).as("the version only goes up").isEqualTo(2);
        assertThat(owner("select cast(config as text) from organization_field_values where tenant_id = ?::uuid "
                + "and field_api_name = 'category'", organization.id().value().toString())).containsExactly("{}");
    }

    @Test
    void aRollbackThatWouldLoseAnAddedValueIsRefusedAsForEveryPicklist() {
        TestBrowser admin = adminOf(organizationWithFeature());
        List<String> categories = new java.util.ArrayList<>(values(admin, "category"));
        categories.add("NEWK");
        assertThat(change(admin, CATEGORY, "category", categories, null, null).status()).isEqualTo(200);

        Response rolledBack = admin.postJson("/api/v1/metadata/releases/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(409);
        assertThat(rolledBack.body()).contains("cannot be removed once it exists");
        assertThat(values(admin, "category")).contains("NEWK");
    }

    @Test
    void aChangeSetThatChangesTheListAppliesInAnyOrderWithTheRulesOfEveryChange() {
        TestBrowser admin = adminOf(organizationWithFeature());
        String set = createSet(admin, "own-values");
        List<String> categories = new java.util.ArrayList<>(values(admin, "category"));
        categories.add("NEWK");
        long version = version(admin, "category");
        Response added = admin.postJson(SETS + "/" + set + "/changes", "{\"kind\":\"UPDATE_FIELD\","
                + "\"objectApiName\":\"" + OBJECT + "\",\"itemApiName\":\"category\",\"updateField\":"
                + update(admin, "category", "[" + String.join(",", categories.stream().map(code -> option(code, true))
                        .toList()) + "]", null, version) + "}");

        Response check = validate(admin, set);

        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).as(check.body()).isTrue();
        assertThat(values(admin, "category")).as("a check changes nothing").hasSize(16);
        assertThat(publish(admin, set).status()).isEqualTo(200);
        assertThat(values(admin, "category")).hasSize(17);
    }

    @Test
    void theMetadataCacheMovesWithTheChangeOnEveryRead() {
        TestBrowser admin = adminOf(organizationWithFeature());
        assertThat(values(admin, "category")).hasSize(16);

        assertThat(add(admin, "category", "{\"value\":\"NEWK\"}").status()).isEqualTo(200);

        assertThat(values(admin, "category")).hasSize(17);
        assertThat(JsonPath.<List<String>>read(admin.get("/api/v1/objects/" + OBJECT + "/form").body(),
                "$.data.fields[?(@.apiName=='category')].values[*].value")).contains("NEWK");
    }

    // ---- adding a value from the form ----

    @Test
    void aMemberWithTheAbilityAddsAValueWithItsPairAndTheFormThenOffersIt() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);

        Response added = add(admin, "subCategory", "{\"value\":\"ab1\",\"label\":\"AB one\","
                + "\"controllingValues\":[\"WI\",\"SOP\"]}");
        Response again = add(admin, "subCategory", "{\"value\":\"AB1\",\"controllingValues\":[\"WI\"]}");
        Response more = add(admin, "subCategory", "{\"value\":\"AB1\",\"controllingValues\":[\"L\"]}");

        assertThat(added.status()).as(added.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(added.body(), "$.data.value")).as("a short code is kept in capitals")
                .isEqualTo("AB1");
        assertThat(JsonPath.<Boolean>read(added.body(), "$.data.added")).isTrue();
        assertThat(JsonPath.<List<String>>read(added.body(), "$.data.controllingValues"))
                .containsExactly("SOP", "WI");
        assertThat(JsonPath.<Boolean>read(again.body(), "$.data.added")).as("a repeat changes nothing").isFalse();
        assertThat(JsonPath.<Boolean>read(more.body(), "$.data.added")).as("a new pair for the same value").isTrue();
        assertThat(allowedFor(admin, "L")).contains("AB1");
        assertThat(version(admin, "subCategory")).as("two changes moved the version on").isGreaterThanOrEqualTo(2);
        assertThat(record(admin, "WI", "AB1").status()).isEqualTo(201);

        Response form = admin.get("/api/v1/objects/" + OBJECT + "/form");
        assertThat(JsonPath.<List<Boolean>>read(form.body(), "$.data.fields[?(@.apiName=='subCategory')]"
                + ".canAddValues")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(form.body(), "$.data.fields[?(@.apiName=='subCategory')]"
                + ".codeValues")).containsExactly(true);
    }

    @Test
    void whatTheAdditionRefusesIsRefusedInWordsThatNameNoValue() {
        TestBrowser admin = adminOf(organizationWithFeature());

        Response notACode = add(admin, "subCategory", "{\"value\":\"not a code\",\"controllingValues\":[\"WI\"]}");
        Response noPair = add(admin, "subCategory", "{\"value\":\"AB2\"}");
        Response unknownPair = add(admin, "subCategory", "{\"value\":\"AB2\",\"controllingValues\":[\"NOPE\"]}");
        Response unneeded = add(admin, "category", "{\"value\":\"NEWK\",\"controllingValues\":[\"WI\"]}");
        Response notOurs = add(admin, "name", "{\"value\":\"X\"}");
        Response nothing = add(admin, "ghost", "{\"value\":\"X\"}");

        assertThat(notACode.status()).as(notACode.body()).isEqualTo(400);
        assertThat(notACode.body()).doesNotContain("not a code");
        assertThat(noPair.status()).isEqualTo(400);
        assertThat(unknownPair.status()).isEqualTo(400);
        assertThat(unknownPair.body()).doesNotContain("NOPE");
        assertThat(unneeded.status()).isEqualTo(400);
        assertThat(notOurs.status()).as("a field whose list is the platform's").isEqualTo(404);
        assertThat(nothing.status()).isEqualTo(404);
        assertThat(values(admin, "subCategory")).doesNotContain("AB2", "NOT A CODE");
    }

    @Test
    void aValueThatIsSwitchedOffCannotBeAddedAgainByAMember() {
        TestBrowser admin = adminOf(organizationWithFeature());
        List<String> categories = values(admin, "category");
        assertThat(change(admin, CATEGORY, "category", categories, "BP", null).status()).isEqualTo(200);

        Response again = add(admin, "category", "{\"value\":\"BP\"}");

        assertThat(again.status()).as(again.body()).isEqualTo(400);
        assertThat(again.body()).contains("switched off");
    }

    @Test
    void theAdditionIsDecidedByTheAbilityTheRightToEditTheFieldAndTheOrganization() {
        Organization organization = organizationWithFeature();
        Organization other = organization();
        TestBrowser admin = adminOf(organization);
        applicationsOf(admin);
        Member plain = plainMember(organization);
        Member holder = memberWithAbility(organization, admin, true, "read", "create", "update");
        Member readOnly = memberWithAbility(organization, admin, true, "read");
        Member editsWithoutAbility = memberWithAbility(organization, admin, false, "read", "create", "update");

        Response withoutProfile = add(as(organization, plain), "subCategory",
                "{\"value\":\"AB3\",\"controllingValues\":[\"WI\"]}");
        Response allowed = add(as(organization, holder), "subCategory",
                "{\"value\":\"AB3\",\"controllingValues\":[\"WI\"]}");
        Response cannotEdit = add(as(organization, readOnly), "subCategory",
                "{\"value\":\"AB4\",\"controllingValues\":[\"WI\"]}");
        Response noAbility = add(as(organization, editsWithoutAbility), "subCategory",
                "{\"value\":\"AB5\",\"controllingValues\":[\"WI\"]}");
        Response otherOrganization = add(adminOf(other), "subCategory",
                "{\"value\":\"AB6\",\"controllingValues\":[\"WI\"]}");
        Response forged = as(organization, plain).request("POST", FIELDS + "/subCategory/values",
                "{\"value\":\"AB7\",\"controllingValues\":[\"WI\"]}", "X-Tenant-Id", other.id().value().toString(),
                "X-Forwarded-Host", other.host());

        assertThat(withoutProfile.status()).as("no ability").isEqualTo(403);
        assertThat(allowed.status()).as(allowed.body()).isEqualTo(200);
        assertThat(cannotEdit.status()).as("the ability without the right to edit the field").isEqualTo(403);
        assertThat(noAbility.status()).as("the right to edit without the ability").isEqualTo(403);
        assertThat(otherOrganization.status()).as("an organization without the solution has no such field")
                .isEqualTo(404);
        assertThat(forged.status()).as("a header changes nothing").isEqualTo(403);
        assertThat(values(admin, "subCategory")).contains("AB3").doesNotContain("AB4", "AB5", "AB6", "AB7");
    }

    @Test
    void theAdditionIsAuditedByCountsAndNeverByTheValue() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);

        add(admin, "subCategory", "{\"value\":\"SECRETAB\",\"label\":\"SECRETLABEL\",\"controllingValues\":[\"WI\"]}");

        List<String> audits = owner("select attributes::text from audit_record where event_type = "
                + "'metadata.fieldvalues.added'");
        assertThat(audits).isNotEmpty();
        assertThat(audits).allSatisfy(audit -> assertThat(audit).contains("pairs_added").doesNotContain("SECRET"));
        assertThat(owner("select count(*) from audit_record where attributes::text like '%SECRET%'"))
                .containsExactly("0");
    }

    @Test
    void anOrganizationWithoutTheFeatureHasNoSuchFieldToAddTo() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);

        Response refused = add(admin, "subCategory", "{\"value\":\"AB8\",\"controllingValues\":[\"WI\"]}");

        assertThat(refused.status()).isEqualTo(404);
    }

    // ---- helpers ----

    /** A member whose profile gives these actions on the object and, if asked, the ability to add values. */
    private Member memberWithAbility(Organization organization, TestBrowser admin, boolean ability,
            String... actions) {
        Member member = plainMember(organization);
        Response profile = admin.postJson("/api/v1/profiles", "{\"name\":\"profile-" + UUID.randomUUID()
                + "\",\"description\":\"\",\"licenceType\":\"user\",\"abilities\":["
                + (ability ? "\"field-values.add\"" : "") + "]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        String profileId = JsonPath.read(profile.body(), "$.data.id");
        assertThat(admin.request("PUT", MEMBERS + "/" + member.membership() + "/profile",
                "{\"profileId\":\"" + profileId + "\"}").status()).isIn(200, 204);
        List<String> quoted = new java.util.ArrayList<>();
        for (String action : actions) {
            quoted.add("\"" + action + "\"");
        }
        boolean edits = quoted.contains("\"update\"");
        Response matrix = admin.request("PUT", "/api/v1/profiles/" + profileId + "/data-access", "{\"objects\":["
                + "{\"key\":\"" + OBJECT + "\",\"actions\":[" + String.join(",", quoted) + "]}],\"fields\":["
                + "{\"key\":\"" + OBJECT + ".subCategory\",\"actions\":[\"" + (edits ? "edit" : "read") + "\"]},"
                + "{\"key\":\"" + OBJECT + ".category\",\"actions\":[\"" + (edits ? "edit" : "read") + "\"]}]}");
        assertThat(matrix.status()).as(matrix.body()).isEqualTo(200);
        return member;
    }

    private static List<String> owner(String sql, Object... arguments) {
        try (var connection = app.platform.testsupport.TestDatabase.ownerConnection();
                var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            List<String> rows = new java.util.ArrayList<>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SETS = "/api/v1/metadata/change-sets";

    private static String createSet(TestBrowser admin, String name) {
        Response created = admin.postJson(SETS, "{\"name\":\"" + name + "\",\"description\":\"\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }

    private static Response validate(TestBrowser admin, String set) {
        return admin.postJson(SETS + "/" + set + "/validate", "{}");
    }

    private static Response publish(TestBrowser admin, String set) {
        return admin.postJson(SETS + "/" + set + "/publish", "{}");
    }
}
