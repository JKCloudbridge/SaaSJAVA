package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The member's own description of an object (Sprint 19, ADR-0129): which fields exist for them, what they may type,
 * how the form is laid out, which record types they may use. The server decides all of it from the same sources the
 * write pipeline and the query engine use; these tests hold the description to that, through the real API.
 */
@PlatformIntegrationTest
class ObjectFormIT extends RecordSecuritySupport {

    private static final String FORM = ITEMS + "/form";
    private static final String LAYOUTS = METADATA_OBJECTS + "/Item__c/layouts";
    private static final String ASSIGNMENTS = METADATA_OBJECTS + "/Item__c/layout-assignments";
    private static final String TYPES = METADATA_OBJECTS + "/Item__c/record-types";

    private static List<Object> property(Response answer, String field, String property) {
        return JsonPath.read(answer.body(), "$.data.fields[?(@.apiName=='" + field + "')]." + property);
    }

    /** The values of a list property of one field (the path reads through the list, not into a list of lists). */
    private static List<Object> list(Response answer, String field, String property) {
        return property(answer, field, property + "[*]");
    }

    private static List<String> names(Response answer) {
        return JsonPath.read(answer.body(), "$.data.fields[*].apiName");
    }

    private static List<String> sections(Response answer) {
        return JsonPath.read(answer.body(), "$.data.layout.sections[*].label");
    }

    private static List<String> fieldsOf(Response answer, String section) {
        return JsonPath.read(answer.body(), "$.data.layout.sections[?(@.label=='" + section + "')].fields[*]");
    }

    private static Response described(TestBrowser browser) {
        Response answer = browser.get(FORM);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return answer;
    }

    private static void layoutAssigned(TestBrowser admin, String name, String definition) {
        Response created = admin.postJson(LAYOUTS, "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"description\":\"\",\"definition\":" + definition + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        Response assigned = admin.postJson(ASSIGNMENTS, "{\"layout\":\"" + name + "__c\"}");
        assertThat(assigned.status()).as(assigned.body()).isEqualTo(201);
    }

    private static void changeField(TestBrowser admin, String name, String extra) {
        String[] definition = ITEM_FIELDS.get(name);
        long version = fieldVersion(admin, "Item__c", name + "__c");
        Response changed = admin.request("PUT", METADATA_OBJECTS + "/Item__c/fields/" + name + "__c",
                "{\"label\":\"" + name + "\",\"description\":\"\"," + extra
                        + definition[1] + ",\"version\":" + version + "}");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
    }

    private static void recordType(TestBrowser admin, String json) {
        Response created = admin.postJson(TYPES, json);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    @Test
    void anAdministratorIsDescribedEveryFieldThatCanBeShownWithWhatCanBeTypedAndTheComparisonsTheEngineTakes() {
        Items items = items();

        Response answer = described(items.admin());

        assertThat(names(answer)).contains("name__c", "qty__c", "price__c", "calc__c", "link__c", "notes__c",
                "sequence", "ownerId", "createdAt", "updatedAt");
        // What a record's answer cannot carry is not offered as a column.
        assertThat(names(answer)).doesNotContain("id", "createdById", "updatedById", "description");
        assertThat(property(answer, "name__c", "editable")).containsExactly(true);
        assertThat(property(answer, "calc__c", "editable")).as("a formula is never typed").containsExactly(false);
        assertThat(property(answer, "calc__c", "calculated")).containsExactly(true);
        assertThat(property(answer, "ownerId", "editable")).as("a system field is never typed").containsExactly(false);
        assertThat(list(answer, "qty__c", "filterOperators")).contains("BETWEEN", "IN", "GTE");
        assertThat(list(answer, "name__c", "filterOperators")).contains("CONTAINS", "STARTS_WITH");
        assertThat(list(answer, "notes__c", "filterOperators")).as("long text cannot be filtered").isEmpty();
        assertThat(property(answer, "link__c", "sortable")).as("a link cannot be an order key").containsExactly(false);
        assertThat(property(answer, "qty__c", "sortable")).containsExactly(true);
        assertThat(property(answer, "link__c", "targetObject")).containsExactly("Target__c");
        assertThat(property(answer, "link__c", "targetOpenable")).containsExactly(true);
        assertThat(property(answer, "ownerId", "targetOpenable")).as("a user has no screen among the records")
                .containsExactly(false);
        assertThat(JsonPath.<String>read(answer.body(), "$.data.label")).isEqualTo("Item");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.pluralLabel")).isEqualTo("Items");
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.actions.create")).isTrue();
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.actions.update")).isTrue();
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.actions.delete")).isTrue();
        assertThat(JsonPath.<List<Object>>read(answer.body(), "$.data.recordTypes")).isEmpty();
    }

    @Test
    void anObjectWithNoLayoutIsLaidOutByThePlatformWithTheSystemFieldsLast() {
        Items items = items();

        Response answer = described(items.admin());

        assertThat(JsonPath.<String>read(answer.body(), "$.data.layout.source")).isEqualTo("GENERATED");
        assertThat(sections(answer)).containsExactly("Details", "System information");
        assertThat(fieldsOf(answer, "Details")).contains("name__c", "qty__c", "calc__c").doesNotContain("ownerId");
        assertThat(fieldsOf(answer, "System information")).containsExactly("sequence", "ownerId", "createdAt",
                "updatedAt");
    }

    @Test
    void aFormulaIsDescribedByItsResultTypeAndNeverByItsText() {
        Items items = items();

        Response answer = described(items.admin());

        assertThat(property(answer, "calc__c", "resultType")).containsExactly("NUMBER");
        assertThat(answer.body()).doesNotContain("qty__c * 2");
    }

    @Test
    void aRecordIsNamedByTheFirstSearchableTextFieldElseTheFirstRequiredElseTheFirstText() {
        Items items = items();
        TestBrowser admin = items.admin();

        Response plain = described(admin);
        assertThat(JsonPath.<String>read(plain.body(), "$.data.titleField")).isEqualTo("name__c");
        assertThat(JsonPath.<Boolean>read(plain.body(), "$.data.searchEnabled")).isFalse();

        changeField(admin, "code", "\"required\":true");
        assertThat(JsonPath.<String>read(described(admin).body(), "$.data.titleField"))
                .as("the first required text field, before the first text field").isEqualTo("code__c");

        changeField(admin, "mail", "\"searchable\":true");
        Response searchable = described(admin);
        assertThat(JsonPath.<String>read(searchable.body(), "$.data.titleField"))
                .as("what an administrator flagged as searchable, before everything else").isEqualTo("mail__c");
        assertThat(JsonPath.<Boolean>read(searchable.body(), "$.data.searchEnabled")).isTrue();
    }

    @Test
    void aMemberIsDescribedOnlyTheFieldsTheyMayReadAndMayTypeOnlyWhereTheirPermissionsAndTheObjectAllow() {
        Items items = items();
        Holder holder = holderWithMatrix(items.organization(), items.admin(),
                "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\",\"update\"]}],\"fields\":["
                        + "{\"key\":\"Item__c.name__c\",\"actions\":[\"edit\"]},"
                        + "{\"key\":\"Item__c.code__c\",\"actions\":[\"read\"]}]}");
        TestBrowser member = as(items.organization(), holder.member());

        Response answer = described(member);

        assertThat(names(answer)).as("a field they may not read is absent, not masked")
                .containsExactlyInAnyOrder("name__c", "code__c");
        assertThat(answer.body()).doesNotContain("qty__c").doesNotContain("secret").doesNotContain("calc__c");
        assertThat(property(answer, "name__c", "editable")).containsExactly(true);
        assertThat(property(answer, "code__c", "editable")).as("they may read it, not edit it").containsExactly(false);
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.actions.update")).isTrue();
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.actions.create")).isFalse();
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.actions.delete")).isFalse();
    }

    @Test
    void aMemberWhoCanOnlyReadTypesNothing() {
        Items items = items();
        Person reader = person(items.organization(), items.admin(), "read");

        Response answer = described(reader.browser());

        assertThat(property(answer, "name__c", "editable")).containsExactly(false);
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.actions.create")).isFalse();
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.actions.update")).isFalse();
    }

    @Test
    void anObjectTheMemberMayNotReadIsAnsweredExactlyLikeOneThatDoesNotExist() {
        Items items = items();
        Holder nothing = holderWithMatrix(items.organization(), items.admin(), null);
        TestBrowser member = as(items.organization(), nothing.member());

        Response forbidden = member.get(FORM);
        Response unknown = member.get("/api/v1/objects/Nope__c/form");
        Response platformOwned = items.admin().get("/api/v1/objects/User/form");

        assertThat(forbidden.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(forbidden.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
        assertThat(platformOwned.status()).as("a user is not an object of the record store").isEqualTo(404);
    }

    @Test
    void nobodyIsDescribedAnythingWithoutSigningInAndAnotherOrganizationDoesNotHaveTheObject() {
        Items items = items();
        Items other = items();
        TestBrowser nobody = new TestBrowser(port, items.organization().host());
        TestBrowser admin = other.admin();
        // The other organization made its own Item__c; a third one has none at all.
        app.platform.testsupport.TestOrganizations.Organization third = organization();
        TestBrowser outsider = adminOf(third);

        assertThat(nobody.get(FORM).status()).isEqualTo(401);
        assertThat(outsider.get(FORM).status()).as("an organization without the object").isEqualTo(404);
        assertThat(admin.get(FORM).status()).isEqualTo(200);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Items items = items();
        Items other = items();
        TestBrowser admin = items.admin();

        Response answer = admin.get(FORM, "X-Tenant-Id", other.organization().id().value().toString(),
                "X-Forwarded-Host", other.organization().host());

        assertThat(answer.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(answer.body(), "$.data.objectApiName")).isEqualTo("Item__c");
    }

    @Test
    void aLayoutDecidesTheBlocksAndItsBehavioursDecideWhatCanBeTyped() {
        Items items = items();
        TestBrowser admin = items.admin();
        layoutAssigned(admin, "Main", "{\"sections\":["
                + "{\"label\":\"Main\",\"columns\":2,\"fields\":[{\"field\":\"name__c\",\"behaviour\":\"REQUIRED\"},"
                + "{\"field\":\"code__c\",\"behaviour\":\"READ_ONLY\"},"
                + "{\"field\":\"qty__c\",\"behaviour\":\"HIDDEN\"}]},"
                + "{\"label\":\"Other\",\"fields\":[{\"field\":\"stage__c\"}]}]}");

        Response answer = described(admin);

        assertThat(JsonPath.<String>read(answer.body(), "$.data.layout.source")).isEqualTo("LAYOUT");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.layout.layout")).isEqualTo("Main__c");
        assertThat(sections(answer)).containsExactly("Main", "Other");
        assertThat(fieldsOf(answer, "Main")).as("a hidden field is left out").containsExactly("name__c", "code__c");
        assertThat(fieldsOf(answer, "Other")).containsExactly("stage__c");
        assertThat(property(answer, "name__c", "required")).as("the layout makes it required").containsExactly(true);
        assertThat(property(answer, "code__c", "editable")).as("read-only on this page").containsExactly(false);
        assertThat(property(answer, "qty__c", "editable")).as("hidden: not typed here").containsExactly(false);
        assertThat(JsonPath.<Integer>read(answer.body(), "$.data.layout.sections[0].columns")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(answer.body(), "$.data.layout.sections[1].columns")).isEqualTo(1);
    }

    @Test
    void aLayoutAlwaysShowsTheRequiredFieldsSoTheFormCanBeSaved() {
        Items items = items();
        TestBrowser admin = items.admin();
        changeField(admin, "code", "\"required\":true");
        Response without = admin.postJson(LAYOUTS, "{\"name\":\"Main\",\"label\":\"Main\",\"description\":\"\","
                + "\"definition\":{\"sections\":[{\"label\":\"Main\",\"fields\":[{\"field\":\"name__c\"}]}]}}");
        assertThat(without.status()).as("the metadata refuses a layout that leaves a required field out")
                .isEqualTo(409);
        layoutAssigned(admin, "Main", "{\"sections\":[{\"label\":\"Main\",\"fields\":[{\"field\":\"name__c\"},"
                + "{\"field\":\"code__c\"}]}]}");

        Response answer = described(admin);

        assertThat(sections(answer)).containsExactly("Main");
        assertThat(fieldsOf(answer, "Main")).containsExactly("name__c", "code__c");
        assertThat(property(answer, "code__c", "required")).containsExactly(true);
    }

    @Test
    void aLayoutInWhichTheMemberSeesNoFieldFallsBackToTheOnesThePlatformMakes() {
        Items items = items();
        layoutAssigned(items.admin(), "Main", "{\"sections\":[{\"label\":\"Secret\",\"fields\":["
                + "{\"field\":\"qty__c\"}]}]}");
        Holder holder = holderWithMatrix(items.organization(), items.admin(),
                "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\"]}],\"fields\":["
                        + "{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]}");

        Response answer = described(as(items.organization(), holder.member()));

        assertThat(JsonPath.<String>read(answer.body(), "$.data.layout.source")).isEqualTo("GENERATED");
        assertThat(fieldsOf(answer, "Details")).containsExactly("name__c");
    }

    @Test
    void aRecordTypeOffersItsFieldsAndItsPicklistValuesAndTheDefaultIsTheOneDescribed() {
        Items items = items();
        TestBrowser admin = items.admin();
        recordType(admin, "{\"name\":\"NewBusiness\",\"label\":\"New business\",\"defaultType\":true,"
                + "\"availableFields\":[\"name__c\",\"stage__c\"],"
                + "\"picklistSubsets\":[{\"field\":\"stage__c\",\"values\":[\"a\",\"b\"]}]}");
        recordType(admin, "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        recordType(admin, "{\"name\":\"Closed\",\"label\":\"Closed\",\"active\":false}");

        Response defaulted = described(admin);

        assertThat(JsonPath.<String>read(defaulted.body(), "$.data.recordType")).isEqualTo("NewBusiness__c");
        assertThat(names(defaulted)).as("only what the type offers, the system fields always")
                .contains("name__c", "stage__c", "ownerId").doesNotContain("qty__c", "code__c");
        assertThat(JsonPath.<List<String>>read(defaulted.body(),
                "$.data.fields[?(@.apiName=='stage__c')].values[*].value")).containsExactly("a", "b");
        assertThat(JsonPath.<List<String>>read(defaulted.body(), "$.data.recordTypes[*].apiName"))
                .as("an inactive type is not offered").containsExactly("NewBusiness__c", "Renewal__c");
        assertThat(JsonPath.<List<Boolean>>read(defaulted.body(),
                "$.data.recordTypes[?(@.apiName=='NewBusiness__c')].defaultType")).containsExactly(true);

        Response renewal = admin.get(FORM + "?recordType=Renewal__c");
        assertThat(JsonPath.<String>read(renewal.body(), "$.data.recordType")).isEqualTo("Renewal__c");
        assertThat(names(renewal)).contains("name__c", "qty__c", "code__c");
        assertThat(JsonPath.<List<String>>read(renewal.body(),
                "$.data.fields[?(@.apiName=='stage__c')].values[*].value")).containsExactly("a", "b", "c");
    }

    @Test
    void aRecordTypeThatDoesNotExistOrIsNotInUseIsRefusedLikeOnTheWrite() {
        Items items = items();
        TestBrowser admin = items.admin();
        recordType(admin, "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        recordType(admin, "{\"name\":\"Closed\",\"label\":\"Closed\",\"active\":false}");

        Response missing = admin.get(FORM + "?recordType=Nope__c");
        Response inactive = admin.get(FORM + "?recordType=Closed__c");

        assertThat(missing.status()).as(missing.body()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(missing.body(), "$.error.fields.recordType"))
                .containsExactly("This object has no such record type.");
        assertThat(inactive.status()).as(inactive.body()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(inactive.body(), "$.error.fields.recordType"))
                .containsExactly("This record type is not in use.");
    }

    @Test
    void aTypeAProfileMayNotUseAnswersLikeATypeThatDoesNotExistAndIsNotOffered() {
        Items items = items();
        TestBrowser admin = items.admin();
        recordType(admin, "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        recordType(admin, "{\"name\":\"Open\",\"label\":\"Open\",\"defaultType\":true}");
        Person sales = person(items.organization(), admin, "read", "create", "update");
        Person support = person(items.organization(), admin, "read", "create", "update");
        Response assigned = admin.postJson(TYPES + "/Renewal__c/profiles", "{\"profileId\":\"" + sales.profileId()
                + "\",\"defaultForProfile\":false}");
        assertThat(assigned.status()).as(assigned.body()).isEqualTo(201);

        Response salesView = described(sales.browser());
        Response supportView = described(support.browser());
        Response refused = support.browser().get(FORM + "?recordType=Renewal__c");

        assertThat(JsonPath.<List<String>>read(salesView.body(), "$.data.recordTypes[*].apiName"))
                .containsExactlyInAnyOrder("Renewal__c", "Open__c");
        assertThat(JsonPath.<List<String>>read(supportView.body(), "$.data.recordTypes[*].apiName"))
                .containsExactly("Open__c");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.recordType"))
                .containsExactly("This object has no such record type.");
    }

    @Test
    void aRecordIsDescribedByTheTypeItHoldsEvenWhenTheMemberHasLostIt() {
        Items items = items();
        TestBrowser admin = items.admin();
        recordType(admin, "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        recordType(admin, "{\"name\":\"Open\",\"label\":\"Open\",\"defaultType\":true}");
        Person support = person(items.organization(), admin, "read", "create", "update");
        Response created = admin.postJson(ITEM_RECORDS,
                "{\"fields\":{\"name__c\":\"a\"},\"recordType\":\"Renewal__c\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = idOf(created);
        Person sales = person(items.organization(), admin, "read", "create", "update");
        admin.postJson(TYPES + "/Renewal__c/profiles", "{\"profileId\":\"" + sales.profileId()
                + "\",\"defaultForProfile\":false}");

        Response answer = admin.get(FORM + "?recordId=" + id);
        // The administrator's profile lost it by the assignment above; the record still reads and is still described.
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(answer.body(), "$.data.recordType")).isEqualTo("Renewal__c");
        assertThat(JsonPath.<List<Boolean>>read(answer.body(),
                "$.data.recordTypes[?(@.apiName=='Renewal__c')].selectable"))
                .as("listed so the form can describe the record, but not offered").containsExactly(false);
        assertThat(support.browser().get(FORM + "?recordId=" + missing()).status()).isEqualTo(404);
    }

    @Test
    void theDescriptionIsPresentationOnlyAWriteIsStillJudgedByThePipeline() {
        Items items = items();
        Person reader = person(items.organization(), items.admin(), "read");

        Response write = reader.browser().postJson(ITEM_RECORDS, "{\"fields\":{\"name__c\":\"x\"}}");

        assertThat(write.status()).isEqualTo(403);
    }
}
