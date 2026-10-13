package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Search over the words of the searchable fields of an object (ADR-0124): every word must be there, accents and
 * case do not matter, the last word is a beginning, the words follow every write, and the result is limited to the
 * records and the fields the caller may reach.
 */
@PlatformIntegrationTest
class SearchIT extends RecordSecuritySupport {

    private static final String SEARCH = ITEM_RECORDS + "/search";

    private static Response flagSearchable(TestBrowser admin, String name, boolean searchable) {
        String[] definition = ITEM_FIELDS.get(name);
        long version = fieldVersion(admin, "Item__c", name + "__c");
        return admin.request("PUT", METADATA_OBJECTS + "/Item__c/fields/" + name + "__c", "{\"label\":\"" + name
                + "\",\"description\":\"\",\"searchable\":" + searchable
                + (definition[1].isEmpty() ? "" : definition[1]) + ",\"version\":" + version + "}");
    }

    /** An organization whose name and notes are searchable and whose words are complete. */
    private Items searchable() {
        Items items = items();
        assertThat(flagSearchable(items.admin(), "name", true).status()).isEqualTo(200);
        assertThat(flagSearchable(items.admin(), "notes", true).status()).isEqualTo(200);
        settleSearch(items.organization(), items.admin());
        return items;
    }

    /** Runs the background work until every searchable field reports its words complete. */
    private void settleSearch(Organization organization, TestBrowser admin) {
        for (int round = 0; round < 40; round++) {
            backfill.workOn(organization.id());
            List<String> states = JsonPath.read(admin.get(ITEM_STATUS).body(), "$.data.search[*].state");
            if (states.stream().allMatch("COMPLETE"::equals)) {
                return;
            }
        }
        throw new AssertionError("search did not settle: " + admin.get(ITEM_STATUS).body());
    }

    private static Response search(TestBrowser browser, String text) {
        return browser.postJson(SEARCH, "{\"text\":\"" + text + "\"}");
    }

    private static List<String> found(TestBrowser browser, String text) {
        Response answer = search(browser, text);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return ids(answer);
    }

    private static String record(TestBrowser browser, String name, String notes) {
        return put(browser, "{\"name__c\":\"" + name + "\",\"notes__c\":\"" + notes + "\"}");
    }

    // ---- what matches ----

    @Test
    void aRecordIsFoundByEveryWordAndNotByAWordItDoesNotHave() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        String wanted = record(person.browser(), "Anna Müller", "renewal due in spring");
        record(person.browser(), "Anna Berg", "new customer");
        record(person.browser(), "Peter Müller", "old customer");

        assertThat(found(person.browser(), "anna muller")).containsExactly(wanted);
        assertThat(found(person.browser(), "renewal spring")).containsExactly(wanted);
        assertThat(found(person.browser(), "anna")).hasSize(2);
        assertThat(found(person.browser(), "anna customer renewal")).isEmpty();
    }

    @Test
    void caseAndAccentsDoNotMatterAndTheLastWordIsABeginning() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        String wanted = record(person.browser(), "ÉLÉONORE Dupont", "");

        assertThat(found(person.browser(), "eleonore")).containsExactly(wanted);
        assertThat(found(person.browser(), "DUPONT")).containsExactly(wanted);
        assertThat(found(person.browser(), "eleo")).containsExactly(wanted);
        assertThat(found(person.browser(), "eleonore dup")).containsExactly(wanted);
        assertThat(found(person.browser(), "ele dupont")).as("only the last word is a beginning").isEmpty();
    }

    @Test
    void theBestMatchComesFirstAndAMatchInTheFirstSearchableFieldCountsMost() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        String inNotes = record(person.browser(), "plain", "harvest");
        String inName = record(person.browser(), "harvest", "plain");

        assertThat(found(person.browser(), "harvest")).containsExactly(inName, inNotes);
    }

    @Test
    void searchPagesWithACursorThatIsGoodOnlyForTheSameText() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        List<String> made = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            made.add(record(person.browser(), "common " + index, ""));
        }

        Response first = person.browser().postJson(SEARCH, "{\"text\":\"common\",\"limit\":2}");
        Response second = person.browser().postJson(SEARCH, "{\"text\":\"common\",\"limit\":2,\"cursor\":\""
                + next(first) + "\"}");
        Response other = person.browser().postJson(SEARCH, "{\"text\":\"different\",\"limit\":2,\"cursor\":\""
                + next(first) + "\"}");

        assertThat(hasMore(first)).isTrue();
        assertThat(ids(first)).hasSize(2);
        assertThat(ids(second)).hasSize(2).doesNotContainAnyElementsOf(ids(first));
        assertThat(other.status()).isEqualTo(400);
        assertThat(made).containsAll(ids(first));
    }

    @Test
    void textWithoutAWordAndAnObjectWithoutSearchableFieldsAreRefusedInPlainWords() {
        Items items = searchable();
        Items none = items();
        Person person = worker(items.organization(), items.admin());
        Person other = worker(none.organization(), none.admin());

        Response symbols = search(person.browser(), "!!! ??");
        Response notSearchable = search(other.browser(), "anything");

        assertThat(symbols.status()).isEqualTo(400);
        assertThat(notSearchable.status()).isEqualTo(400);
        assertThat(notSearchable.body()).doesNotContain("anything");
    }

    // ---- the words follow every write ----

    @Test
    void aChangeOfTheValueRemovesTheOldWordsAndAddsTheNewOnesAtOnce() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        String id = record(person.browser(), "oldname", "");

        Response changed = person.browser().request("PATCH", ITEM_RECORDS + "/" + id,
                "{\"version\":0,\"fields\":{\"name__c\":\"newname\"}}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(found(person.browser(), "oldname")).isEmpty();
        assertThat(found(person.browser(), "newname")).containsExactly(id);
    }

    @Test
    void aRecordInTheRecycleBinIsNotFoundAndIsFoundAgainWhenRestored() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        String id = record(person.browser(), "recyclable", "");
        person.browser().request("DELETE", ITEM_RECORDS + "/" + id, null);

        assertThat(found(person.browser(), "recyclable")).isEmpty();
        items.admin().postJson(ITEM_BIN + "/" + id + "/restore", "{}");
        assertThat(found(person.browser(), "recyclable")).containsExactly(id);
    }

    @Test
    void aRecordRemovedForGoodTakesItsWordsWithIt() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        String id = record(person.browser(), "vanishing", "");
        person.browser().request("DELETE", ITEM_RECORDS + "/" + id, null);
        person.browser().request("DELETE", ITEM_BIN + "/" + id, null);

        assertThat(owner("select count(*) from search_term where record_id = ?::uuid", id)).containsExactly("0");
    }

    @Test
    void recordsThatExistedBeforeTheFieldWasMadeSearchableAreFoundOnceTheFillIsComplete() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        String id = record(person.browser(), "earlier", "");

        assertThat(flagSearchable(items.admin(), "name", true).status()).isEqualTo(200);
        settleSearch(items.organization(), items.admin());

        assertThat(found(person.browser(), "earlier")).containsExactly(id);
    }

    @Test
    void aFieldThatIsNoLongerSearchableLosesItsWordsAndItsMatches() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        String id = record(person.browser(), "", "findable notes");
        assertThat(found(person.browser(), "findable")).containsExactly(id);

        assertThat(flagSearchable(items.admin(), "notes", false).status()).isEqualTo(200);
        settleSearch(items.organization(), items.admin());

        assertThat(search(person.browser(), "findable").status()).isEqualTo(200);
        assertThat(found(person.browser(), "findable")).isEmpty();
        assertThat(owner("select count(*) from search_term where field_api_name = 'notes__c' "
                + "and record_id = ?::uuid", id)).containsExactly("0");
    }

    @Test
    void onlyTextTypesCanBeSearchable() {
        Items items = items();

        Response refused = flagSearchable(items.admin(), "qty", true);

        assertThat(refused.status()).isEqualTo(400);
    }

    @Test
    void theIndexOfTheWordsIsLookedUpByOrganizationObjectAndWord() {
        assertThat(owner("select count(*) from pg_indexes where tablename = 'search_term' and indexdef like "
                + "'%(tenant_id, object_api_name, term, record_id)%'")).containsExactly("1");
    }

    // ---- who may find what ----

    @Test
    void aMemberFindsOnlyTheRecordsTheyMayReach() {
        Items items = searchable();
        share(items.admin(), "PRIVATE", false);
        Person owner = worker(items.organization(), items.admin());
        Person outsider = worker(items.organization(), items.admin());
        String id = record(owner.browser(), "confidential", "");

        assertThat(found(owner.browser(), "confidential")).containsExactly(id);
        assertThat(found(outsider.browser(), "confidential")).isEmpty();
    }

    @Test
    void aFieldTheMemberMayNotReadIsNeverMatched() {
        Items items = searchable();
        Person owner = worker(items.organization(), items.admin());
        String id = record(owner.browser(), "visible", "secretword");
        String fields = "[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]";
        TestBrowser limited = as(items.organization(),
                holderWith(items.organization(), items.admin(), "Item__c", fields, "read").member());

        assertThat(found(owner.browser(), "secretword")).containsExactly(id);
        assertThat(found(limited, "secretword")).isEmpty();
        assertThat(found(limited, "visible")).containsExactly(id);
    }

    @Test
    void aMemberWhoCannotReadAnyFieldWithWordsIsToldThereIsNothingToSearch() {
        Items items = searchable();
        TestBrowser blind = as(items.organization(),
                holderWith(items.organization(), items.admin(), "Item__c", "[]", "read").member());

        assertThat(search(blind, "anything").status()).isEqualTo(400);
    }

    @Test
    void aMemberWhoMayNotReadTheObjectIsToldItDoesNotExistAndAnonymousIsRefused() {
        Items items = searchable();
        Person nobody = person(items.organization(), items.admin());
        TestBrowser anonymous = new TestBrowser(port, items.organization().host());

        assertThat(search(nobody.browser(), "x").status()).isEqualTo(404);
        assertThat(search(anonymous, "x").status()).isEqualTo(401);
    }

    @Test
    void anotherOrganizationNeverFindsTheWordsOfThisOne() {
        Items mine = searchable();
        Items theirs = searchable();
        Person person = worker(mine.organization(), mine.admin());
        Person stranger = worker(theirs.organization(), theirs.admin());
        record(person.browser(), "sharedword", "");

        assertThat(found(stranger.browser(), "sharedword")).isEmpty();
        assertThat(found(person.browser(), "sharedword")).hasSize(1);
    }

    @Test
    void theWordsOfARecordNeverReachALogOrAnAuditRecord() {
        Items items = searchable();
        Person person = worker(items.organization(), items.admin());
        String id = record(person.browser(), "uniquelyprivateword", "");
        search(person.browser(), "uniquelyprivateword");

        assertThat(auditsOf("data.record.created", id)).allSatisfy(attributes ->
                assertThat(attributes).doesNotContain("uniquelyprivateword"));
        assertThat(owner("select count(*) from audit_record where attributes::text like '%uniquelyprivateword%'"))
                .containsExactly("0");
    }
}
