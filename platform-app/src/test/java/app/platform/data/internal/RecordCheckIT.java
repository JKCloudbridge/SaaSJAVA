package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * "What would the server say to this save?" (Sprint 19, ADR-0129, ADR-0079): the real write pipeline run and undone.
 * These tests hold it to the three promises: it says what a save would say (same places, same words, because it is the
 * same code), it stores and uses up nothing, and it is judged for the caller exactly like the save.
 */
@PlatformIntegrationTest
class RecordCheckIT extends RecordSecuritySupport {

    private static final String VALIDATE = ITEM_RECORDS + "/validate";

    private static String check(String recordId, String fieldsJson) {
        return "{" + (recordId == null ? "" : "\"recordId\":\"" + recordId + "\",") + "\"fields\":" + fieldsJson + "}";
    }

    private static Response validate(TestBrowser browser, String recordId, String fieldsJson) {
        return browser.postJson(VALIDATE, check(recordId, fieldsJson));
    }

    private static Response save(TestBrowser browser, String fieldsJson) {
        return browser.postJson(ITEM_RECORDS, "{\"fields\":" + fieldsJson + "}");
    }

    private static long count(String sql, String tenant) {
        return Long.parseLong(owner(sql, tenant).get(0));
    }

    @Test
    void aSaveThatWouldWorkIsAnsweredWithTheResultsOfItsFormulasAndStoresNothing() {
        Items items = items();

        Response answer = validate(items.admin(), null, "{\"name__c\":\"a\",\"qty__c\":21}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.calculated.calc__c").intValue()).isEqualTo(42);
        assertThat(listed(items.admin())).as("no record was made").isEmpty();
    }

    @Test
    void aValidationUsesUpNoAutoNumberAndLeavesNoAuditRecordEventOrRecordBehind() {
        Items items = items();
        String tenant = items.organization().id().value().toString();

        for (int index = 0; index < 3; index++) {
            assertThat(validate(items.admin(), null, "{\"name__c\":\"a\"}").status()).isEqualTo(200);
        }
        Response real = save(items.admin(), "{\"name__c\":\"a\"}");

        assertThat(real.status()).as(real.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(real.body(), "$.data.fields.sequence"))
                .as("the first real record has the first number: validating used none").isEqualTo("1");
        assertThat(count("select count(*) from audit_record where event_type like 'data.record.%' "
                + "and context_tenant_id = ?::uuid", tenant)).as("one audit record, for the real save").isEqualTo(1);
        assertThat(count("select count(*) from outbox_event where event_type like 'record.%' "
                + "and tenant_id = ?::uuid", tenant)).as("one event, for the real save").isEqualTo(1);
        assertThat(count("select count(*) from object_record where tenant_id = ?::uuid "
                + "and object_api_name = 'Item__c'", tenant)).isEqualTo(1);
    }

    @Test
    void aSaveThatWouldBeRefusedIsRefusedWithTheSamePlacesAndWordsAsTheRealSave() {
        Items items = items();
        List<String> bodies = List.of("{\"qty__c\":\"not a number\"}", "{\"nothing__c\":\"x\"}",
                "{\"qty__c\":1.5}", "{\"stage__c\":\"z\"}", "{\"calc__c\":5}", "{\"link__c\":\"" + missing() + "\"}");

        for (String fields : bodies) {
            Response checked = validate(items.admin(), null, fields);
            Response saved = save(items.admin(), fields);

            assertThat(saved.status()).as(fields + ": " + saved.body()).isEqualTo(400);
            assertThat(checked.status()).as(fields + ": " + checked.body()).isEqualTo(400);
            assertThat(JsonPath.<Object>read(checked.body(), "$.error.fields").toString())
                    .as("the same problems at the same places for " + fields)
                    .isEqualTo(JsonPath.<Object>read(saved.body(), "$.error.fields").toString());
            assertThat(JsonPath.<String>read(checked.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(listed(items.admin())).isEmpty();
    }

    @Test
    void aUniqueValueIsPointedOutInTheWordsOfTheSaveAndNeverSaysWhoseRecordHoldsIt() {
        Items items = items();
        assertThat(setUnique(items.admin(), "code", true).status()).isEqualTo(200);
        settleUniqueness(items.organization(), items.admin());
        put(items.admin(), "{\"name__c\":\"first\",\"code__c\":\"dup\"}");

        Response checked = validate(items.admin(), null, "{\"name__c\":\"second\",\"code__c\":\"dup\"}");
        Response saved = save(items.admin(), "{\"name__c\":\"second\",\"code__c\":\"dup\"}");

        assertThat(checked.status()).as(checked.body()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(checked.body(), "$.error.fields.code__c"))
                .containsExactly("This value is already in use. This field must be unique.");
        assertThat(JsonPath.<Object>read(checked.body(), "$.error.fields").toString())
                .isEqualTo(JsonPath.<Object>read(saved.body(), "$.error.fields").toString());
        assertThat(checked.body()).doesNotContain("first");
    }

    @Test
    void aChangeToAnExistingRecordIsJudgedWithTheRecordAndGivesTheNewFormulaResult() {
        Items items = items();
        String id = put(items.admin(), "{\"name__c\":\"a\",\"qty__c\":1}");

        Response answer = validate(items.admin(), id, "{\"qty__c\":5}");
        Response refused = validate(items.admin(), id, "{\"qty__c\":\"x\"}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.calculated.calc__c").intValue()).isEqualTo(10);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<Number>read(items.admin().get(ITEM_RECORDS + "/" + id).body(), "$.data.fields.qty__c")
                .intValue()).as("nothing was changed").isEqualTo(1);
        assertThat(JsonPath.<Number>read(items.admin().get(ITEM_RECORDS + "/" + id).body(), "$.data.version")
                .intValue()).isZero();
    }

    @Test
    void aFormulaWhoseResultStaysTheSameIsLeftOutOfTheAnswer() {
        Items items = items();
        String id = put(items.admin(), "{\"name__c\":\"a\",\"qty__c\":2}");

        Response answer = validate(items.admin(), id, "{\"name__c\":\"b\"}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(answer.body(), "$.data.calculated").toString()).isEqualTo("{}");
    }

    @Test
    void aRecordTheCallerCannotReachIsAnsweredAsOneThatDoesNotExistAndAReadOnlyCallerIsForbidden() {
        Items items = items();
        share(items.admin(), "PRIVATE", false);
        String hidden = put(items.admin(), "{\"name__c\":\"mine\"}");
        Person worker = worker(items.organization(), items.admin());
        Person maker = person(items.organization(), items.admin(), "read", "create", "update");
        String own = put(maker.browser(), "{\"name__c\":\"theirs\"}");

        Response notReached = validate(worker.browser(), hidden, "{\"name__c\":\"x\"}");
        Response unknown = validate(worker.browser(), missing(), "{\"name__c\":\"x\"}");
        Response readOnly = validate(person(items.organization(), items.admin(), "read").browser(), null,
                "{\"name__c\":\"x\"}");

        assertThat(notReached.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(notReached.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
        assertThat(readOnly.status()).as("they may read the object but not create in it").isEqualTo(403);
        assertThat(validate(maker.browser(), own, "{\"name__c\":\"y\"}").status()).as("their own record")
                .isEqualTo(200);
    }

    @Test
    void aRecordInTheRecycleBinCannotBeChangedAndSoCannotBeChecked() {
        Items items = items();
        String id = put(items.admin(), "{\"name__c\":\"a\"}");
        assertThat(items.admin().request("DELETE", ITEM_RECORDS + "/" + id, null).status()).isEqualTo(204);

        Response answer = validate(items.admin(), id, "{\"name__c\":\"b\"}");

        assertThat(answer.status()).as("a record in the recycle bin is read-only, and reads as gone").isIn(404, 409);
    }

    @Test
    void aFormulaResultIsLeftOutForAMemberWhoMayNotReadTheFormulaField() {
        Items items = items();
        Holder holder = holderWithMatrix(items.organization(), items.admin(),
                "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\",\"create\"]}],\"fields\":["
                        + "{\"key\":\"Item__c.name__c\",\"actions\":[\"edit\"]},"
                        + "{\"key\":\"Item__c.qty__c\",\"actions\":[\"edit\"]}]}");
        TestBrowser member = as(items.organization(), holder.member());

        Response answer = validate(member, null, "{\"name__c\":\"a\",\"qty__c\":4}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(answer.body()).doesNotContain("calc__c");
    }

    @Test
    void aFieldTheCallerMayNotEditIsAnsweredAsInTheRealSave() {
        Items items = items();
        Holder holder = holderWithMatrix(items.organization(), items.admin(),
                "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\",\"create\"]}],\"fields\":["
                        + "{\"key\":\"Item__c.name__c\",\"actions\":[\"edit\"]},"
                        + "{\"key\":\"Item__c.code__c\",\"actions\":[\"read\"]}]}");
        TestBrowser member = as(items.organization(), holder.member());

        Response checked = validate(member, null, "{\"name__c\":\"a\",\"code__c\":\"x\",\"qty__c\":1}");
        Response saved = save(member, "{\"name__c\":\"a\",\"code__c\":\"x\",\"qty__c\":1}");

        assertThat(checked.status()).isEqualTo(400);
        assertThat(JsonPath.<Object>read(checked.body(), "$.error.fields").toString())
                .isEqualTo(JsonPath.<Object>read(saved.body(), "$.error.fields").toString());
    }

    @Test
    void nobodyMayAskWithoutSigningInAndAnObjectOutOfReachIsNotFound() {
        Items items = items();
        TestBrowser nobody = new TestBrowser(port, items.organization().host());
        Holder nothing = holderWithMatrix(items.organization(), items.admin(), null);

        assertThat(nobody.postJson(VALIDATE, check(null, "{}")).status()).isEqualTo(401);
        assertThat(as(items.organization(), nothing.member()).postJson(VALIDATE, check(null, "{}")).status())
                .isEqualTo(404);
        assertThat(items.admin().postJson("/api/v1/objects/Nope__c/records/validate", check(null, "{}")).status())
                .isEqualTo(404);
    }

    @Test
    void whatWasTypedIsNeverEchoedInAnAnswer() {
        Items items = items();

        Response answer = validate(items.admin(), null, "{\"qty__c\":\"a very private text\"}");

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).doesNotContain("private");
    }
}
