package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The database rule that lets a change of the typed copies alone keep the version (Sprint 16, ADR-0102), and the seam
 * for record-level security that the list, the single read and the query all ask (ADR-0100).
 */
@PlatformIntegrationTest
class SlotGuardAndSeamIT extends QuerySupport {

    @Autowired
    private RecordVisibility visibility;

    @Test
    void anUpdateOfTheCopiesAloneKeepsTheVersionAndTheTimeAnyOtherNeedsTheNextVersion() {
        Items items = items();
        String id = put(items.admin(), "{\"name__c\":\"a\"}");
        String before = owner("select version || ' ' || updated_at from object_record where id = ?::uuid", id).get(0);

        ownerRuns("update object_record set s_text_1 = 'copy' where id = '" + id + "'");

        assertThat(owner("select version || ' ' || updated_at from object_record where id = ?::uuid", id).get(0))
                .isEqualTo(before);
        assertThatThrownBy(() -> ownerRuns("update object_record set payload = '{\"x\":1}' where id = '" + id + "'"))
                .hasMessageContaining("must increase version");
        assertThatThrownBy(() -> ownerRuns("update object_record set owner_id = gen_random_uuid(), "
                + "s_text_1 = 'copy' where id = '" + id + "'")).hasMessageContaining("must increase version");
        ownerRuns("update object_record set payload = '{\"x\":1}', version = version + 1 where id = '" + id + "'");
    }

    @Test
    void theHelperThatSaysOnlyTheCopiesChangedNamesEveryColumnOfTheRecord() {
        String source = owner("select pg_get_functiondef('platform_record_only_slots_changed(object_record,"
                + "object_record)'::regprocedure)").get(0);
        List<String> columns = owner("select column_name from information_schema.columns "
                + "where table_name = 'object_record' and column_name !~ '^s_(text|num|date|ts|ref|bool)_'");

        for (String column : columns) {
            assertThat(source).as("a column added to the record table must be added to the helper").contains(column);
        }
    }

    @Test
    void theListTheSingleReadAndTheQueryAllAskTheSeamForRecordSecurity() {
        Items items = items();
        String id = put(items.admin(), "{\"name__c\":\"a\"}");
        long start = visibility.calls();

        items.admin().get(ITEM_RECORDS);
        assertThat(visibility.calls()).as("the list").isEqualTo(start + 1);
        items.admin().get(ITEM_RECORDS + "/" + id);
        assertThat(visibility.calls()).as("the single read").isEqualTo(start + 2);
        query(items.admin(), "{}");
        assertThat(visibility.calls()).as("the query").isEqualTo(start + 3);
        items.admin().get("/api/v1/objects/Nothing__c/records");
        assertThat(visibility.calls()).as("a call refused at the gate asks nobody").isEqualTo(start + 3);
    }

    @Test
    void theOrderOfTheStagesIsHeldToTheDocumentedList() {
        assertThat(QueryAnalyzer.STAGES).containsExactly("shape", "fields", "operators", "record security", "plan",
                "sql", "execute");
    }

    @Test
    void aMemberWithoutAnAbilityCannotSeeTheIndexStatus() {
        Items items = items();
        TestBrowser member = as(items.organization(), memberWithActions(items.organization(), items.admin(), "read"));

        assertThat(member.get(ITEM_STATUS).status()).isEqualTo(403);
    }
}
