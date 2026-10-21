package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.PipelineSupport;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Dependent picklists end to end (FA2A, ADR-0177) through the real API on a real database: the stage of the pipeline,
 * bulk and import, a record that holds an old pair, making a field dependent over records, the dependency graph in any
 * order, a record type that narrows the values, the description a screen draws from, and a log that never holds a
 * value.
 */
@PlatformIntegrationTest
class DependentPicklistsIT extends PipelineSupport {

    private static final String DOCS = "/api/v1/objects/Doc__c";
    private static final String DOC_RECORDS = DOCS + "/records";
    private static final String MISMATCH = "This value does not go with the chosen value of kind.";

    @Autowired
    private ImportRunner importRunner;

    private static String values(String... codes) {
        List<String> parts = new ArrayList<>();
        for (String code : codes) {
            parts.add("{\"value\":\"" + code + "\",\"label\":\"" + code + "\",\"active\":true}");
        }
        return "\"values\":[" + String.join(",", parts) + "]";
    }

    private static final String RULE = "\"dependency\":{\"controllingField\":\"kind__c\",\"allowed\":"
            + "{\"WI\":[\"PRD\",\"QLT\"],\"IMS\":[],\"CSF\":[\"CUSA\"]}}";

    private static void field(TestBrowser admin, String json) {
        Response created = admin.postJson(METADATA_OBJECTS + "/Doc__c/fields", json);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    /** An organization with {@code Doc__c}: a controlling picklist {@code kind__c}, a dependent {@code area__c}. */
    private TestBrowser docs(Organization organization, boolean dependent) {
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Doc", "Docs");
        field(admin, "{\"name\":\"kind\",\"label\":\"kind\",\"type\":\"PICKLIST\",\"settings\":{"
                + values("WI", "IMS", "CSF") + "}}");
        field(admin, "{\"name\":\"area\",\"label\":\"area\",\"type\":\"PICKLIST\",\"settings\":{"
                + values("PRD", "QLT", "CUSA") + (dependent ? "," + RULE : "") + "}}");
        field(admin, "{\"name\":\"title\",\"label\":\"title\",\"type\":\"TEXT\"}");
        return admin;
    }

    private static Response make(TestBrowser browser, String fields) {
        return createIn(browser, DOC_RECORDS, fields);
    }

    private static String pair(String kind, String area) {
        return "{\"kind__c\":\"" + kind + "\",\"area__c\":\"" + area + "\"}";
    }

    private static Response changeField(TestBrowser admin, String name, String body) {
        return admin.request("PUT", METADATA_OBJECTS + "/Doc__c/fields/" + name, body);
    }

    // ---- the stage ----

    @Test
    void aPairOfTheRuleIsSavedAndAPairOutsideItIsRefusedAtTheDependentFieldWithoutNamingAValue() {
        TestBrowser admin = docs(organization(), true);

        Response good = make(admin, pair("WI", "PRD"));
        Response bad = make(admin, pair("WI", "CUSA"));
        Response allowsNothing = make(admin, pair("IMS", "PRD"));
        Response noKind = make(admin, "{\"area__c\":\"PRD\"}");

        assertThat(good.status()).as(good.body()).isEqualTo(201);
        for (Response refused : List.of(bad, allowsNothing, noKind)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(placesOf(refused)).containsExactly("area__c");
            assertThat(problemAt(refused, "area__c")).isEqualTo(MISMATCH);
        }
        assertThat(make(admin, "{\"kind__c\":\"IMS\"}").status()).as("no dependent value is fine").isEqualTo(201);
    }

    @Test
    void aRecordThatHoldsAnOldPairIsNeverBlockedByAnUnrelatedEditButTheOldPairCannotBeWrittenNew() {
        TestBrowser admin = docs(organization(), true);
        String id = idOf(make(admin, pair("WI", "QLT")));
        long version = fieldVersion(admin, "Doc__c", "area__c");
        Response narrowed = changeField(admin, "area__c", "{\"label\":\"area\",\"description\":\"\",\"settings\":{"
                + values("PRD", "QLT", "CUSA") + ",\"dependency\":{\"controllingField\":\"kind__c\",\"allowed\":"
                + "{\"WI\":[\"PRD\"],\"IMS\":[],\"CSF\":[\"CUSA\"]}}},\"version\":" + version + "}");
        assertThat(narrowed.status()).as(narrowed.body()).isEqualTo(200);

        Response unrelated = patchIn(admin, DOC_RECORDS, id, currentVersion(admin, DOC_RECORDS, id),
                "{\"title__c\":\"edited\"}");
        Response again = patchIn(admin, DOC_RECORDS, id, currentVersion(admin, DOC_RECORDS, id), pair("WI", "QLT"));
        Response newPair = make(admin, pair("WI", "QLT"));
        Response touched = patchIn(admin, DOC_RECORDS, id, currentVersion(admin, DOC_RECORDS, id),
                "{\"kind__c\":\"CSF\"}");

        assertThat(unrelated.status()).as(unrelated.body()).isEqualTo(200);
        assertThat(again.status()).as("sending the same pair back changes nothing").isEqualTo(200);
        assertThat(newPair.status()).isEqualTo(400);
        assertThat(touched.status()).as("changing the controlling value judges the value held").isEqualTo(400);
        assertThat(problemAt(touched, "area__c")).isEqualTo(MISMATCH);
    }

    @Test
    void aRecordTypeNarrowsTheValuesAndTheRuleStillHoldsTogetherWithIt() {
        TestBrowser admin = docs(organization(), true);
        Response type = admin.postJson(METADATA_OBJECTS + "/Doc__c/record-types", "{\"name\":\"Narrow\","
                + "\"label\":\"Narrow\",\"defaultType\":true,\"availableFields\":[\"kind__c\",\"area__c\","
                + "\"title__c\"],\"picklistSubsets\":[{\"field\":\"area__c\",\"values\":[\"PRD\",\"CUSA\"]}]}");
        assertThat(type.status()).as(type.body()).isEqualTo(201);

        Response inBoth = make(admin, pair("WI", "PRD"));
        Response typeRefuses = make(admin, pair("WI", "QLT"));
        Response ruleRefuses = make(admin, pair("WI", "CUSA"));

        assertThat(inBoth.status()).as(inBoth.body()).isEqualTo(201);
        assertThat(typeRefuses.status()).isEqualTo(400);
        assertThat(problemAt(typeRefuses, "area__c")).isEqualTo("This value is not allowed for the record type.");
        assertThat(ruleRefuses.status()).isEqualTo(400);
        assertThat(problemAt(ruleRefuses, "area__c")).isEqualTo(MISMATCH);
    }

    // ---- bulk and import ----

    @Test
    void aBulkOfTwoHundredRecordsReportsEachBadPairAtItsPositionAndSavesTheRest() {
        TestBrowser admin = docs(organization(), true);
        List<String> records = new ArrayList<>();
        int bad = 0;
        for (int i = 0; i < 200; i++) {
            boolean good = i % 3 != 0;
            bad += good ? 0 : 1;
            records.add("{\"fields\":" + (good ? pair("WI", "QLT") : pair("IMS", "QLT")) + "}");
        }

        Response answer = admin.postJson(DOC_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"allOrNone\":false,"
                + "\"records\":[" + String.join(",", records) + "]}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.failed").intValue()).isEqualTo(bad);
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.succeeded").intValue()).isEqualTo(200 - bad);
        List<String> statuses = JsonPath.read(answer.body(), "$.data.results[*].status");
        for (int i = 0; i < 200; i++) {
            assertThat(statuses.get(i)).isEqualTo(i % 3 != 0 ? "CREATED" : "FAILED");
        }
    }

    @Test
    void anImportReportsTheRowsWithABadPairAndSavesTheOthers() {
        Organization organization = organization();
        TestBrowser admin = docs(organization, true);
        String imports = DOCS + "/imports";
        Response started = admin.postJson(imports, "{\"operation\":\"CREATE\",\"dryRun\":false}");
        assertThat(started.status()).as(started.body()).isEqualTo(201);
        String id = idOf(started);
        BytesResponse sent = admin.bytes("PUT", imports + "/" + id + "/file",
                "kind__c,area__c\nWI,PRD\nWI,CUSA\nCSF,CUSA\nIMS,PRD\n".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "text/csv");
        assertThat(sent.status()).as(sent.text()).isEqualTo(202);

        importRunner.workOn(organization.id());
        Response done = admin.get(imports + "/" + id);

        assertThat(JsonPath.<String>read(done.body(), "$.data.state")).as(done.body()).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Number>read(done.body(), "$.data.createdRows").intValue()).isEqualTo(2);
        assertThat(JsonPath.<Number>read(done.body(), "$.data.failedRows").intValue()).isEqualTo(2);
        BytesResponse report = admin.bytes("GET", imports + "/" + id + "/errors", null);
        List<String> rows = report.text().lines().skip(1).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).startsWith("2,area__c: " + MISMATCH);
        assertThat(rows.get(1)).startsWith("4,area__c: " + MISMATCH);
    }

    // ---- making a field dependent over records, and the lifecycle ----

    @Test
    void makingAFieldDependentOverRecordsIsAllowedAndTheCheckCountsThePairsNoLongerAllowed() {
        TestBrowser admin = docs(organization(), false);
        make(admin, pair("WI", "CUSA"));
        make(admin, pair("WI", "PRD"));
        make(admin, pair("CSF", "PRD"));
        String set = createSet(admin, "make-dependent");
        long version = fieldVersion(admin, "Doc__c", "area__c");
        Response added = admin.postJson(SETS + "/" + set + "/changes", "{\"kind\":\"UPDATE_FIELD\","
                + "\"objectApiName\":\"Doc__c\",\"itemApiName\":\"area__c\",\"updateField\":{\"label\":\"area\","
                + "\"description\":\"\",\"settings\":{" + values("PRD", "QLT", "CUSA") + "," + RULE
                + "},\"version\":" + version + "}}");
        assertThat(added.status()).as(added.body()).isEqualTo(201);

        Response check = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).as(check.body()).isTrue();
        List<String> notes = JsonPath.read(check.body(), "$.data.notes");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).startsWith("2 records hold a pair in Doc__c.area__c that is no longer allowed")
                .doesNotContain("CUSA").doesNotContain("WI");
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(make(admin, pair("WI", "CUSA")).status()).as("new records follow the rule").isEqualTo(400);

        Response rolledBack = admin.postJson("/api/v1/metadata/releases/latest/rollback", "{}");
        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(make(admin, pair("WI", "CUSA")).status()).as("the rule is gone with the rollback")
                .isEqualTo(201);
    }

    @Test
    void theControllingFieldCannotBeRemovedWhileAFieldDependsOnItInAnyOrderOfTheChanges() {
        TestBrowser admin = docs(organization(), true);

        String alone = createSet(admin, "controlling-only");
        addRemoval(admin, alone, "Doc__c", "kind__c");
        Response aloneCheck = validate(admin, alone);
        String forward = createSet(admin, "both-forward");
        addRemoval(admin, forward, "Doc__c", "area__c");
        addRemoval(admin, forward, "Doc__c", "kind__c");
        String backward = createSet(admin, "both-backward");
        addRemoval(admin, backward, "Doc__c", "kind__c");
        addRemoval(admin, backward, "Doc__c", "area__c");

        assertThat(JsonPath.<Boolean>read(aloneCheck.body(), "$.data.valid")).isFalse();
        assertThat(JsonPath.<String>read(aloneCheck.body(), "$.data.problems[0].message"))
                .contains("field Doc__c.area__c depends on field Doc__c.kind__c");
        assertThat(JsonPath.<Boolean>read(validate(admin, forward).body(), "$.data.valid")).isTrue();
        assertThat(JsonPath.<Boolean>read(validate(admin, backward).body(), "$.data.valid")).isTrue();
    }

    @Test
    void aDependencyOnAFieldThatDependsOnAnotherIsRefusedAndNothingIsSaved() {
        TestBrowser admin = docs(organization(), true);

        Response chain = admin.postJson(METADATA_OBJECTS + "/Doc__c/fields", "{\"name\":\"sub\",\"label\":\"sub\","
                + "\"type\":\"PICKLIST\",\"settings\":{" + values("S") + ",\"dependency\":{\"controllingField\":"
                + "\"area__c\",\"allowed\":{\"PRD\":[\"S\"]}}}}");
        Response unknownValue = admin.postJson(METADATA_OBJECTS + "/Doc__c/fields", "{\"name\":\"other\","
                + "\"label\":\"other\",\"type\":\"PICKLIST\",\"settings\":{" + values("S")
                + ",\"dependency\":{\"controllingField\":\"kind__c\",\"allowed\":{\"NOPE\":[\"S\"]}}}}");

        assertThat(chain.status()).as(chain.body()).isEqualTo(409);
        assertThat(chain.body()).contains("depends on another field itself");
        assertThat(unknownValue.status()).as(unknownValue.body()).isEqualTo(409);
        assertThat(unknownValue.body()).doesNotContain("NOPE");
        assertThat(fieldNames(admin, "Doc__c")).doesNotContain("sub__c", "other__c");
    }

    @Test
    void aMultiSelectPicklistCannotDependOnAnotherField() {
        TestBrowser admin = docs(organization(), true);

        Response refused = admin.postJson(METADATA_OBJECTS + "/Doc__c/fields", "{\"name\":\"many\","
                + "\"label\":\"many\",\"type\":\"MULTI_PICKLIST\",\"settings\":{" + values("S") + ","
                + "\"dependency\":{\"controllingField\":\"kind__c\",\"allowed\":{\"WI\":[\"S\"]}}}}");

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(placesOf(refused)).contains("settings.dependency");
    }

    // ---- the description a screen draws from ----

    @Test
    void theFormTellsTheScreenTheRuleOnlyToAMemberWhoMayReadTheControllingField() {
        Organization organization = organization();
        TestBrowser admin = docs(organization, true);
        TestBrowser both = as(organization, holderWith(organization, admin, "Doc__c",
                "[{\"key\":\"Doc__c.kind__c\",\"actions\":[\"read\"]},{\"key\":\"Doc__c.area__c\","
                        + "\"actions\":[\"edit\"]}]", "read", "create", "update").member());
        TestBrowser onlyArea = as(organization, holderWith(organization, admin, "Doc__c",
                "[{\"key\":\"Doc__c.area__c\",\"actions\":[\"edit\"]}]", "read", "create", "update").member());
        String path = "$.data.fields[?(@.apiName=='area__c')]";

        Response seen = both.get(DOCS + "/form");
        Response blind = onlyArea.get(DOCS + "/form");

        assertThat(JsonPath.<List<String>>read(seen.body(), path + ".dependency.controllingField"))
                .as(seen.body()).containsExactly("kind__c");
        assertThat(JsonPath.<List<Object>>read(seen.body(), path + ".dependency.allowed.WI[*]")).hasSize(2);
        assertThat(JsonPath.<List<Object>>read(blind.body(), path + ".dependency")).isEmpty();
        assertThat(blind.body()).doesNotContain("kind__c");
        // The server still enforces what the screen was not told.
        Response refused = make(onlyArea, "{\"area__c\":\"PRD\"}");
        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(problemAt(refused, "area__c")).isEqualTo("This value does not go with the chosen value of "
                + "another field.");
    }

    @Test
    void aValueOfThePairsNeverReachesTheAuditTrailOfASavedOrRefusedWrite() {
        TestBrowser admin = docs(organization(), true);

        assertThat(make(admin, pair("WI", "CUSA")).status()).isEqualTo(400);
        assertThat(make(admin, pair("WI", "PRD")).status()).isEqualTo(201);

        assertThat(owner("select count(*) from audit_record where attributes::text like '%CUSA%' "
                + "or attributes::text like '%PRD%'")).containsExactly("0");
    }
}
