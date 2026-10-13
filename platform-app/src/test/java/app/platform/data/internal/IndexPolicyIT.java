package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The index policy (Sprint 16, ADR-0101, ADR-0102): a flag gets a slot, the copy of existing records is filled in
 * without touching their versions, a record written meanwhile carries its own copy, an un-flagged field is cleared
 * before its slot is free, and a restore computes the copy again.
 */
@PlatformIntegrationTest
class IndexPolicyIT extends QuerySupport {

    private static String states(TestBrowser admin) {
        return JsonPath.read(admin.get(ITEM_STATUS).body(), "$.data.fields[*].state").toString();
    }

    @Test
    void aFlaggedFieldIsFilledForExistingRecordsWithoutChangingTheirVersions() {
        Items items = items();
        TestBrowser admin = items.admin();
        String first = put(admin, "{\"name__c\":\"alpha\"}");
        String second = put(admin, "{\"name__c\":\"beta\"}");
        put(admin, "{}");

        assertThat(flag(admin, "name", true).status()).isEqualTo(200);
        assertThat(states(admin)).contains("WAITING");
        settle(items.organization(), admin);

        assertThat(states(admin)).contains("FILLED");
        assertThat(slotOf(first, "s_text_1")).isEqualTo("alpha");
        assertThat(slotOf(second, "s_text_1")).isEqualTo("beta");
        assertThat(versionOf(admin.get(ITEM_RECORDS + "/" + first))).as("the fill is not a change").isEqualTo(0);
    }

    @Test
    void aRecordWrittenWhileTheSlotIsBeingFilledCarriesItsOwnCopy() {
        Items items = items();
        TestBrowser admin = items.admin();
        put(admin, "{\"name__c\":\"old\"}");
        flag(admin, "name", true);
        backfill.workOn(items.organization().id());

        String id = put(admin, "{\"name__c\":\"new\"}");
        Response changed = admin.request("PATCH", ITEM_RECORDS + "/" + id, "{\"version\":0,\"fields\":"
                + "{\"name__c\":\"newer\"}}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(slotOf(id, "s_text_1")).isEqualTo("newer");
    }

    @Test
    void aFieldWithNoValueOrAClearedValueHasNoCopy() {
        Items items = items();
        TestBrowser admin = items.admin();
        flagAll(admin, "qty");
        settle(items.organization(), admin);
        String id = put(admin, "{\"qty__c\":5}");
        assertThat(slotOf(id, "s_num_1")).isEqualTo("5");

        admin.request("PATCH", ITEM_RECORDS + "/" + id, "{\"version\":0,\"clear\":[\"qty__c\"]}");

        assertThat(slotOf(id, "s_num_1")).isNull();
    }

    @Test
    void unflaggingEmptiesTheCopyAndFreesTheSlotForAnotherField() {
        Items items = items();
        TestBrowser admin = items.admin();
        String id = put(admin, "{\"name__c\":\"alpha\",\"code__c\":\"c1\"}");
        flagAll(admin, "name");
        settle(items.organization(), admin);
        assertThat(slotOf(id, "s_text_1")).isEqualTo("alpha");

        flag(admin, "name", false);
        flagAll(admin, "code");
        backfill.workOn(items.organization().id());
        settle(items.organization(), admin);

        // A slot is reused only once it is empty, so the new field may sit in the same slot or in the next one.
        assertThat(java.util.Arrays.asList(slotOf(id, "s_text_1"), slotOf(id, "s_text_2")))
                .as("never reused with the old values in it").contains("c1").doesNotContain("alpha");
        assertThat(states(admin)).doesNotContain("CLEARING");
    }

    @Test
    void aRestoredRecordGetsItsCopyAgain() {
        Items items = items();
        TestBrowser admin = items.admin();
        String id = put(admin, "{\"name__c\":\"alpha\"}");
        admin.request("DELETE", ITEM_RECORDS + "/" + id, null);
        flagAll(admin, "name");
        settle(items.organization(), admin);
        assertThat(slotOf(id, "s_text_1")).as("a record in the bin is not filled").isNull();

        Response restored = admin.postJson(ITEM_BIN + "/" + id + "/restore", "{}");

        assertThat(restored.status()).as(restored.body()).isEqualTo(200);
        assertThat(slotOf(id, "s_text_1")).isEqualTo("alpha");
    }

    @Test
    void theStatusSaysHowFarEachFieldIsAndNeedsTheAbilityToViewMetadata() {
        Items items = items();
        flagAll(items.admin(), "name");
        settle(items.organization(), items.admin());
        TestBrowser nobody = as(items.organization(), memberWithActions(items.organization(), items.admin()));

        List<String> fields = JsonPath.read(items.admin().get(ITEM_STATUS).body(), "$.data.fields[*].field");

        assertThat(fields).containsExactly("name__c");
        assertThat(nobody.get(ITEM_STATUS).status()).isEqualTo(403);
    }
}
