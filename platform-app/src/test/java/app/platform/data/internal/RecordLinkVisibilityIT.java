package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.PlatformIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Links respect what a member may see (Sprint 17A, ADR-0125, ADR-0097): a lookup or a master-detail can point only at a
 * live record the member can read, and every other case (no such record, a record in the recycle bin, a record of
 * another object, a record the member may not read, an object they may not use) gets the same answer. Reading a record
 * never shows more of a linked record than its identifier when the member cannot read the linked record. A master
 * takes its details with it only when the member may remove them too; the clean-up of a pointer is the platform's.
 */
@PlatformIntegrationTest
class RecordLinkVisibilityIT extends RecordSecuritySupport {

    private static final String MISSING = "The linked record does not exist.";
    private static final String SECRET = "label-nobody-may-see";

    /** An organization whose targets carry a label, with a member who may use them and one who may not. */
    private record Setup(Items items, Person alice, Person blind, String hidden, String own) {
    }

    private Setup setup() {
        Items items = items();
        TestBrowser admin = items.admin();
        Response label = admin.postJson(METADATA_OBJECTS + "/Target__c/fields",
                "{\"name\":\"label\",\"label\":\"label\",\"type\":\"TEXT\"}");
        assertThat(label.status()).as(label.body()).isEqualTo(201);
        Person alice = member(items, true);
        Person blind = member(items, false);
        shareTargets(admin, "PRIVATE");
        String hidden = idOf(admin.postJson(TARGETS, "{\"fields\":{\"label__c\":\"" + SECRET + "\"}}"));
        String own = idOf(alice.browser().postJson(TARGETS, "{\"fields\":{\"label__c\":\"mine\"}}"));
        return new Setup(items, alice, blind, hidden, own);
    }

    /** A member who works with items and, when asked, may read, create and change targets and read their label. */
    private Person member(Items items, boolean targets) {
        String fields = ITEM_FIELD_PERMISSIONS.substring(0, ITEM_FIELD_PERMISSIONS.length() - 1)
                + ",{\"key\":\"Target__c.label__c\",\"actions\":[\"edit\"]}]";
        String objects = "{\"key\":\"Item__c\",\"actions\":[\"read\",\"create\",\"update\",\"delete\"]}"
                + (targets ? ",{\"key\":\"Target__c\",\"actions\":[\"read\",\"create\",\"update\",\"delete\"]}" : "");
        Holder holder = holderWithMatrix(items.organization(), items.admin(),
                "{\"objects\":[" + objects + "],\"fields\":" + fields + "}");
        return new Person(holder.member(), as(items.organization(), holder.member()), holder.profileId());
    }

    private static void shareTargets(TestBrowser admin, String defaultAccess) {
        shareObject(admin, "Target__c", defaultAccess);
    }

    private static void shareObject(TestBrowser admin, String object, String defaultAccess) {
        Response current = admin.get(SHARING + "/objects/" + object);
        boolean configured = JsonPath.read(current.body(), "$.data.configured");
        String version = configured ? ",\"version\":" + JsonPath.<Number>read(current.body(), "$.data.version") : "";
        Response set = admin.request("PUT", SHARING + "/objects/" + object, "{\"defaultAccess\":\"" + defaultAccess
                + "\",\"grantViaHierarchy\":false" + version + "}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
    }

    private static Response itemLinkedTo(TestBrowser browser, String target) {
        return browser.postJson(ITEM_RECORDS, "{\"fields\":{\"name__c\":\"linked\",\"link__c\":\"" + target + "\"}}");
    }

    // ---- writing a link ----

    @Test
    void aMemberLinksOnlyToRecordsTheyCanRead() {
        Setup setup = setup();
        TestBrowser alice = setup.alice().browser();

        Response own = itemLinkedTo(alice, setup.own());
        Response hidden = itemLinkedTo(alice, setup.hidden());
        Response nothing = itemLinkedTo(alice, missing());

        assertThat(own.status()).as(own.body()).isEqualTo(201);
        assertThat(hidden.status()).as(hidden.body()).isEqualTo(400);
        assertThat(problemAt(hidden, "link__c")).isEqualTo(MISSING);
        assertThat(nothing.status()).isEqualTo(400);
        assertThat(problemAt(nothing, "link__c")).as("the answer for a record that does not exist")
                .isEqualTo(problemAt(hidden, "link__c"));
        assertThat(hidden.body()).doesNotContain(setup.hidden()).doesNotContain(SECRET);
    }

    @Test
    void movingAnExistingLinkToAHiddenRecordIsRefusedLikeAMissingOne() {
        Setup setup = setup();
        TestBrowser alice = setup.alice().browser();
        String item = idOf(itemLinkedTo(alice, setup.own()));

        Response moved = alice.request("PATCH", ITEM_RECORDS + "/" + item,
                "{\"version\":0,\"fields\":{\"link__c\":\"" + setup.hidden() + "\"}}");

        assertThat(moved.status()).as(moved.body()).isEqualTo(400);
        assertThat(problemAt(moved, "link__c")).isEqualTo(MISSING);
        assertThat(fieldsOf(alice.get(ITEM_RECORDS + "/" + item)).get("link__c")).isEqualTo(setup.own());
    }

    @Test
    void sharingTheTargetWithTheMemberOpensTheLinkAndTakingItBackClosesIt() {
        Setup setup = setup();
        TestBrowser alice = setup.alice().browser();
        String shares = TARGETS + "/" + setup.hidden() + "/shares";

        assertThat(itemLinkedTo(alice, setup.hidden()).status()).isEqualTo(400);
        items(setup).request("PUT", shares, "{\"granteeType\":\"USER\",\"granteeId\":\"" + setup.alice().userId()
                + "\",\"accessLevel\":\"READ\"}");
        assertThat(itemLinkedTo(alice, setup.hidden()).status()).as("shared to read").isEqualTo(201);
        items(setup).request("DELETE", shares + "/" + setup.alice().userId(), null);
        assertThat(itemLinkedTo(alice, setup.hidden()).status()).as("taken back").isEqualTo(400);
    }

    @Test
    void aMemberWhoMayNotUseTheTargetObjectGetsTheAnswerOfARecordThatIsNotThere() {
        Setup setup = setup();
        shareTargets(setup.items().admin(), "PUBLIC_READ_WRITE");

        Response real = itemLinkedTo(setup.blind().browser(), setup.hidden());
        Response nothing = itemLinkedTo(setup.blind().browser(), missing());

        assertThat(real.status()).as(real.body()).isEqualTo(400);
        assertThat(problemAt(real, "link__c")).isEqualTo(MISSING).isEqualTo(problemAt(nothing, "link__c"));
        assertThat(itemLinkedTo(setup.alice().browser(), setup.hidden()).status())
                .as("a member who may use the object links to the same record").isEqualTo(201);
    }

    @Test
    void aChangeThatLeavesALinkAloneDoesNotJudgeItAgain() {
        Setup setup = setup();
        shareObject(setup.items().admin(), "Item__c", "PUBLIC_READ_WRITE");
        String item = idOf(itemLinkedTo(setup.items().admin(), setup.hidden()));

        Response renamed = setup.alice().browser().request("PATCH", ITEM_RECORDS + "/" + item,
                "{\"version\":0,\"fields\":{\"name__c\":\"renamed\"}}");

        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(fieldsOf(renamed).get("link__c")).as("the link is untouched").isEqualTo(setup.hidden());
    }

    // ---- reading a link ----

    @Test
    void readingARecordShowsNothingOfALinkedRecordTheMemberCannotRead() {
        Setup setup = setup();
        TestBrowser admin = setup.items().admin();
        String item = idOf(itemLinkedTo(admin, setup.hidden()));
        String resolve = "{\"resolve\":[{\"field\":\"link__c\",\"fields\":[\"label__c\"]}]}";

        Response asked = setup.alice().browser().postJson(ITEM_QUERY, resolve);

        List<String> ids = JsonPath.read(asked.body(), "$.data[*].record.id");
        assertThat(ids).contains(item);
        int at = ids.indexOf(item);
        assertThat(JsonPath.<String>read(asked.body(), "$.data[" + at + "].linked['link__c'].id"))
                .isEqualTo(setup.hidden());
        assertThat(JsonPath.<Map<String, Object>>read(asked.body(), "$.data[" + at + "].linked['link__c'].fields"))
                .as("the identifier alone, no label, no preview").isEmpty();
        assertThat(asked.body()).doesNotContain(SECRET);

        shareTargets(admin, "PUBLIC_READ");
        Response opened = setup.alice().browser().postJson(ITEM_QUERY, resolve);
        assertThat(JsonPath.<String>read(opened.body(), "$.data[" + at + "].linked['link__c'].fields.label__c"))
                .as("the same read, once the member may read the target").isEqualTo(SECRET);
    }

    // ---- master and detail ----

    /** Parts are details of items; a part is private to the member who made it. */
    private record Parts(Items items, Person carol, Person alice, String item) {
    }

    private Parts parts() {
        Items items = items();
        TestBrowser admin = items.admin();
        makeObject(admin, "Part", "Parts");
        Response parent = admin.postJson(METADATA_OBJECTS + "/Part__c/fields", "{\"name\":\"parent\","
                + "\"label\":\"parent\",\"type\":\"MASTER_DETAIL\",\"settings\":{\"targetObject\":\"Item__c\"}}");
        assertThat(parent.status()).as(parent.body()).isEqualTo(201);
        String fields = ITEM_FIELD_PERMISSIONS.substring(0, ITEM_FIELD_PERMISSIONS.length() - 1)
                + ",{\"key\":\"Part__c.parent__c\",\"actions\":[\"edit\"]}]";
        String matrix = "{\"objects\":[{\"key\":\"Item__c\",\"actions\":[\"read\",\"create\",\"update\",\"delete\"]},"
                + "{\"key\":\"Part__c\",\"actions\":[\"read\",\"create\",\"update\",\"delete\"]}],\"fields\":" + fields
                + "}";
        Holder first = holderWithMatrix(items.organization(), admin, matrix);
        Holder second = holderWithMatrix(items.organization(), admin, matrix);
        Person alice = new Person(first.member(), as(items.organization(), first.member()), first.profileId());
        Person carol = new Person(second.member(), as(items.organization(), second.member()), second.profileId());
        shareObject(admin, "Part__c", "PRIVATE");
        return new Parts(items, carol, alice, newItem(alice.browser(), "alice-item"));
    }

    private static Response partOf(TestBrowser browser, String item) {
        return browser.postJson("/api/v1/objects/Part__c/records", "{\"fields\":{\"parent__c\":\"" + item + "\"}}");
    }

    @Test
    void aMasterIsRemovedTogetherWithItsDetailsOnlyByAMemberWhoMayRemoveThemToo() {
        Parts parts = parts();
        Response part = partOf(parts.carol().browser(), parts.item());
        assertThat(part.status()).as(part.body()).isEqualTo(201);
        String partId = idOf(part);

        Response refused = remove(parts.alice().browser(), parts.item());

        assertThat(refused.status()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).doesNotContain(partId);
        assertThat(parts.alice().browser().get(ITEM_RECORDS + "/" + parts.item()).status())
                .as("the master is still there").isEqualTo(200);
        assertThat(remove(parts.items().admin(), parts.item()).status()).as("modify-all removes both")
                .isEqualTo(204);
        assertThat(parts.carol().browser().get("/api/v1/objects/Part__c/records/" + partId).status())
                .as("the detail went to the bin with its master").isEqualTo(404);
    }

    @Test
    void aMasterIsBroughtBackTogetherWithItsDetailsOnlyByAMemberWhoMayRemoveThemToo() {
        Parts parts = parts();
        String partId = idOf(partOf(parts.carol().browser(), parts.item()));
        assertThat(remove(parts.items().admin(), parts.item()).status()).isEqualTo(204);

        Response refused = restore(parts.alice().browser(), parts.item());

        assertThat(refused.status()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).doesNotContain(partId);
        assertThat(restore(parts.items().admin(), parts.item()).status()).isEqualTo(200);
        assertThat(parts.carol().browser().get("/api/v1/objects/Part__c/records/" + partId).status())
                .as("brought back with its master").isEqualTo(200);
    }

    @Test
    void aMasterWhoseDetailsAreAlreadyGoneIsRemovedByItsOwnerAsBefore() {
        Parts parts = parts();
        String partId = idOf(partOf(parts.carol().browser(), parts.item()));
        parts.carol().browser().request("DELETE", "/api/v1/objects/Part__c/records/" + partId, null);

        assertThat(remove(parts.alice().browser(), parts.item()).status()).isEqualTo(204);
    }

    @Test
    void emptyingAPointerWhenATargetIsRemovedIsTheWorkOfThePlatformAndNeedsNoRightOverTheOtherRecord() {
        Setup setup = setup();
        TestBrowser admin = setup.items().admin();
        shareObject(admin, "Item__c", "PRIVATE");
        String item = idOf(itemLinkedTo(admin, setup.own()));
        assertThat(setup.alice().browser().get(ITEM_RECORDS + "/" + item).status())
                .as("the member cannot see the record that points at their target").isEqualTo(404);

        Response removed = setup.alice().browser().request("DELETE", TARGETS + "/" + setup.own(), null);

        assertThat(removed.status()).as(removed.body()).isEqualTo(204);
        assertThat(fieldsOf(admin.get(ITEM_RECORDS + "/" + item))).doesNotContainKey("link__c");
        assertThat(JsonPath.<String>read(admin.get(ITEM_RECORDS + "/" + item).body(), "$.data.ownerId"))
                .as("the owner is not touched").isEqualTo(setup.items().organization().admin().person().user().id()
                        .toString());
    }

    // ---- helpers ----

    private static TestBrowser items(Setup setup) {
        return setup.items().admin();
    }

    private static Response remove(TestBrowser browser, String record) {
        return browser.request("DELETE", ITEM_RECORDS + "/" + record, null);
    }

    private static Response restore(TestBrowser browser, String record) {
        return browser.postJson(ITEM_BIN + "/" + record + "/restore", "{}");
    }
}
