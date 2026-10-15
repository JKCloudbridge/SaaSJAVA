package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * Uniqueness and record-level security (Sprint 17A, ADR-0125, ADR-0111): the check runs across the whole organization
 * whatever anyone can see, and the refusal is the same words at the same place whether or not the member can see the
 * record that holds the value. It never names, points at or describes that record.
 */
@PlatformIntegrationTest
class UniquenessAndVisibilityIT extends RecordSecuritySupport {

    private static final String IN_USE = "This value is already in use. This field must be unique.";

    /** A private object whose {@code code__c} is unique, with a record of {@code alice} that holds "TAKEN". */
    private record Case(Items items, Person alice, Person dave, String held) {
    }

    private Case uniqueCode() {
        Items items = items();
        Response made = setUnique(items.admin(), "code", true);
        assertThat(made.status()).as(made.body()).isEqualTo(200);
        settleUniqueness(items.organization(), items.admin());
        share(items.admin(), "PRIVATE", false);
        Person alice = worker(items.organization(), items.admin());
        Person dave = worker(items.organization(), items.admin());
        Response held = alice.browser().postJson(ITEM_RECORDS, "{\"fields\":{\"code__c\":\"TAKEN\"}}");
        assertThat(held.status()).as(held.body()).isEqualTo(201);
        return new Case(items, alice, dave, idOf(held));
    }

    private static Response codeCreate(TestBrowser browser, String code) {
        return browser.postJson(ITEM_RECORDS, "{\"fields\":{\"code__c\":\"" + code + "\"}}");
    }

    /** The answer without what differs from one call to the next. */
    private static String shape(Response refused) {
        return refused.body().replaceAll("\"requestId\":\"[^\"]*\"", "").replaceAll("\"traceId\":\"[^\"]*\"", "");
    }

    @Test
    void theCheckRunsOverRecordsTheMemberCannotSeeAndSaysOnlyThatTheValueIsInUse() {
        Case one = uniqueCode();
        assertThat(one.dave().browser().get(ITEM_RECORDS + "/" + one.held()).status())
                .as("dave cannot see the record that holds the value").isEqualTo(404);

        Response blind = codeCreate(one.dave().browser(), "TAKEN");
        Response sighted = codeCreate(one.items().admin(), "TAKEN");
        Response owner = codeCreate(one.alice().browser(), "TAKEN");

        for (Response refused : java.util.List.of(blind, sighted, owner)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(problemAt(refused, "code__c")).isEqualTo(IN_USE);
            assertThat(refused.body()).doesNotContain(one.held()).doesNotContain(one.alice().userId())
                    .doesNotContain("TAKEN");
        }
        assertThat(shape(blind)).as("the same answer whoever can see the record").isEqualTo(shape(sighted))
                .isEqualTo(shape(owner));
    }

    @Test
    void aChangeToATakenValueIsRefusedTheSameWay() {
        Case one = uniqueCode();
        String mine = idOf(codeCreate(one.dave().browser(), "mine"));

        Response clash = one.dave().browser().request("PATCH", ITEM_RECORDS + "/" + mine,
                "{\"version\":0,\"fields\":{\"code__c\":\"TAKEN\"}}");

        assertThat(clash.status()).as(clash.body()).isEqualTo(400);
        assertThat(problemAt(clash, "code__c")).isEqualTo(IN_USE);
        assertThat(clash.body()).doesNotContain(one.held());
        assertThat(fieldsOf(one.dave().browser().get(ITEM_RECORDS + "/" + mine)).get("code__c")).isEqualTo("mine");
    }

    @Test
    void aBulkCallAndAFreeValueBehaveAsOnASingleCall() {
        Case one = uniqueCode();

        Response bulk = one.dave().browser().postJson(ITEM_RECORDS + "/bulk", "{\"operation\":\"CREATE\","
                + "\"allOrNone\":false,\"records\":[{\"fields\":{\"code__c\":\"TAKEN\"}},"
                + "{\"fields\":{\"code__c\":\"free\"}}]}");

        assertThat(JsonPath.<String>read(bulk.body(), "$.data.results[0].status")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(bulk.body(), "$.data.results[0].code")).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<String>read(bulk.body(), "$.data.results[1].status")).isEqualTo("CREATED");
        assertThat(bulk.body()).doesNotContain(one.held());
    }

    @Test
    void aRestoreThatWouldDuplicateAValueSaysNoMoreThanTheWriteDoes() {
        Case one = uniqueCode();
        assertThat(one.alice().browser().request("DELETE", ITEM_RECORDS + "/" + one.held(), null).status())
                .isEqualTo(204);
        assertThat(codeCreate(one.dave().browser(), "TAKEN").status()).as("the bin does not hold the value")
                .isEqualTo(201);

        Response restore = one.alice().browser().postJson(ITEM_BIN + "/" + one.held() + "/restore", "{}");

        assertThat(restore.status()).as(restore.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(restore.body(), "$.error.message")).contains("cannot be brought back")
                .contains("code__c").doesNotContain("another record").doesNotContain("TAKEN");
        assertThat(restore.body()).doesNotContain(one.dave().userId());
    }
}
