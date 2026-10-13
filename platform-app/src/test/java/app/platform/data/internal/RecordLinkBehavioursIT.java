package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.PipelineSupport;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * What removing a record does to the records that point at it and belong to it (Sprint 16, ADR-0106): clear, refuse
 * and cascade, restore, reparenting, the junction, the bounds, the permission and the older records whose pointers are
 * still being written.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = "platform.data.links.max-affected=3")
class RecordLinkBehavioursIT extends PipelineSupport {

    private static final String PARENT = "/api/v1/objects/Parent__c/records";
    private static final String CHILD = "/api/v1/objects/Child__c/records";
    private static final String CHILD_BIN = "/api/v1/objects/Child__c/recycle-bin";
    private static final String PARENT_BIN = "/api/v1/objects/Parent__c/recycle-bin";
    private static final String CLEARER = "/api/v1/objects/Clearer__c/records";
    private static final String REFUSER = "/api/v1/objects/Refuser__c/records";
    private static final String JUNCTION = "/api/v1/objects/Link__c/records";

    @Autowired
    private IndexBackfill backfill;

    private static void field(TestBrowser admin, String object, String name, String type, String settings) {
        Response created = admin.postJson(METADATA_OBJECTS + "/" + object + "/fields", "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"type\":\"" + type + "\"" + settings + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    private static String settings(String onDelete) {
        return ",\"settings\":{\"targetObject\":\"Parent__c\"" + (onDelete == null ? "" : ",\"onDelete\":\""
                + onDelete + "\"") + "}";
    }

    private TestBrowser world(Organization organization) {
        TestBrowser admin = adminOf(organization);
        for (String name : List.of("Parent", "Child", "Clearer", "Refuser", "Other", "Link")) {
            makeObject(admin, name, name + "s");
        }
        field(admin, "Child__c", "parent", "MASTER_DETAIL", settings(null).replace("}", ",\"reparentable\":true}"));
        field(admin, "Clearer__c", "parent", "LOOKUP", settings("CLEAR"));
        field(admin, "Refuser__c", "parent", "LOOKUP", settings("REFUSE"));
        field(admin, "Link__c", "left", "MASTER_DETAIL", settings(null));
        field(admin, "Link__c", "right", "MASTER_DETAIL", ",\"settings\":{\"targetObject\":\"Other__c\"}");
        return admin;
    }

    private static String record(TestBrowser admin, String records, String fields) {
        Response created = admin.postJson(records, "{\"fields\":" + fields + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return idOf(created);
    }

    private static Response remove(TestBrowser admin, String records, String id) {
        return admin.request("DELETE", records + "/" + id, null);
    }

    @Test
    void aLookupThatClearsLosesItsPointerAndTheRecordKeepsEverythingElse() {
        TestBrowser admin = world(organization());
        String parent = record(admin, PARENT, "{}");
        String clearer = record(admin, CLEARER, "{\"parent__c\":\"" + parent + "\"}");

        Response removed = remove(admin, PARENT, parent);

        assertThat(removed.status()).as(removed.body()).isEqualTo(204);
        Response read = admin.get(CLEARER + "/" + clearer);
        assertThat(fieldsOf(read)).doesNotContainKey("parent__c");
        assertThat(versionOf(read)).as("a change by the platform").isEqualTo(1);
        assertThat(owner("select count(*) from record_link where source_record = ?::uuid", clearer).get(0))
                .isEqualTo("0");
    }

    @Test
    void aLookupThatRefusesBlocksTheRemovalAndTheAnswerNamesNothing() {
        TestBrowser admin = world(organization());
        String parent = record(admin, PARENT, "{}");
        String refuser = record(admin, REFUSER, "{\"parent__c\":\"" + parent + "\"}");

        Response refused = remove(admin, PARENT, parent);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("Other records refer to this record")
                .doesNotContain(refuser).doesNotContain("Refuser");
        assertThat(admin.get(PARENT + "/" + parent).status()).isEqualTo(200);
        remove(admin, REFUSER, refuser);
        assertThat(remove(admin, PARENT, parent).status()).as("nothing points at it any more").isEqualTo(204);
    }

    @Test
    void theDetailsGoWithTheirMasterAndComeBackWithItButNotTheOnesRemovedBefore() {
        TestBrowser admin = world(organization());
        String parent = record(admin, PARENT, "{}");
        String one = record(admin, CHILD, "{\"parent__c\":\"" + parent + "\"}");
        String two = record(admin, CHILD, "{\"parent__c\":\"" + parent + "\"}");
        String before = record(admin, CHILD, "{\"parent__c\":\"" + parent + "\"}");
        remove(admin, CHILD, before);

        assertThat(remove(admin, PARENT, parent).status()).isEqualTo(204);
        assertThat(admin.get(CHILD + "/" + one).status()).isEqualTo(404);
        assertThat(admin.get(CHILD_BIN).body()).contains(one).contains(two).contains(before);

        Response restored = admin.postJson(PARENT_BIN + "/" + parent + "/restore", "{}");

        assertThat(restored.status()).as(restored.body()).isEqualTo(200);
        assertThat(admin.get(CHILD + "/" + one).status()).isEqualTo(200);
        assertThat(admin.get(CHILD + "/" + two).status()).isEqualTo(200);
        assertThat(admin.get(CHILD + "/" + before).status()).as("removed on purpose, stays removed").isEqualTo(404);
    }

    @Test
    void aDetailWhoseMasterIsGoneCannotComeBackAloneAndAMovedDetailKeepsItsPointerRight() {
        TestBrowser admin = world(organization());
        String parent = record(admin, PARENT, "{}");
        String other = record(admin, PARENT, "{}");
        String child = record(admin, CHILD, "{\"parent__c\":\"" + parent + "\"}");
        patchIn(admin, CHILD, child, 0, "{\"parent__c\":\"" + other + "\"}");

        assertThat(remove(admin, PARENT, parent).status()).as("the child moved away").isEqualTo(204);
        assertThat(admin.get(CHILD + "/" + child).status()).isEqualTo(200);
        remove(admin, PARENT, other);
        assertThat(admin.get(CHILD + "/" + child).status()).isEqualTo(404);
        Response alone = admin.postJson(CHILD_BIN + "/" + child + "/restore", "{}");
        assertThat(alone.status()).isEqualTo(409);
        assertThat(alone.body()).contains("Restore the record it belongs to");
    }

    @Test
    void aLookupWhoseRecordIsGoneIsEmptiedWhenTheRecordComesBack() {
        TestBrowser admin = world(organization());
        String parent = record(admin, PARENT, "{}");
        String clearer = record(admin, CLEARER, "{\"parent__c\":\"" + parent + "\"}");
        remove(admin, CLEARER, clearer);
        remove(admin, PARENT, parent);

        Response back = admin.postJson("/api/v1/objects/Clearer__c/recycle-bin/" + clearer + "/restore", "{}");

        assertThat(back.status()).as(back.body()).isEqualTo(200);
        assertThat(fieldsOf(admin.get(CLEARER + "/" + clearer))).doesNotContainKey("parent__c");
    }

    @Test
    void aRemovalThatWouldTouchMoreThanTheBoundIsRefusedWithTheBound() {
        TestBrowser admin = world(organization());
        String parent = record(admin, PARENT, "{}");
        for (int i = 0; i < 4; i++) {
            record(admin, CHILD, "{\"parent__c\":\"" + parent + "\"}");
        }

        Response refused = remove(admin, PARENT, parent);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("more than 3");
        assertThat(admin.get(PARENT + "/" + parent).status()).isEqualTo(200);
    }

    @Test
    void removingDetailsThroughTheirMasterNeedsThePermissionToDeleteThem() {
        Organization organization = organization();
        TestBrowser admin = world(organization);
        String parent = record(admin, PARENT, "{}");
        record(admin, CHILD, "{\"parent__c\":\"" + parent + "\"}");
        Holder holder = holderWith(organization, admin, "Parent__c", "[]", "read", "delete");
        TestBrowser member = as(organization, holder.member());

        Response refused = remove(member, PARENT, parent);

        assertThat(refused.status()).isIn(403, 404);
        assertThat(admin.get(PARENT + "/" + parent).status()).isEqualTo(200);
    }

    @Test
    void aJunctionIsQueryableFromEitherParentAndRefusesTheSamePairTwice() throws Exception {
        TestBrowser admin = world(organization());
        String left = record(admin, PARENT, "{}");
        String right = record(admin, "/api/v1/objects/Other__c/records", "{}");
        String link = record(admin, JUNCTION, "{\"left__c\":\"" + left + "\",\"right__c\":\"" + right + "\"}");

        Response again = admin.postJson(JUNCTION, "{\"fields\":{\"left__c\":\"" + left + "\",\"right__c\":\""
                + right + "\"}}");
        Response fromLeft = admin.postJson(JUNCTION + "/query", "{\"filter\":{\"op\":\"EQ\",\"field\":\"left__c\","
                + "\"values\":[\"" + left + "\"]}}");
        Response fromRight = admin.postJson(JUNCTION + "/query", "{\"filter\":{\"op\":\"EQ\",\"field\":\"right__c\","
                + "\"values\":[\"" + right + "\"]}}");

        assertThat(again.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(fromLeft.body(), "$.data[*].record.id")).containsExactly(link);
        assertThat(JsonPath.<List<String>>read(fromRight.body(), "$.data[*].record.id")).containsExactly(link);
    }

    @Test
    void twoWritesOfTheSamePairAtOnceCannotBothPass() throws Exception {
        TestBrowser admin = world(organization());
        String left = record(admin, PARENT, "{}");
        String right = record(admin, "/api/v1/objects/Other__c/records", "{}");
        String body = "{\"fields\":{\"left__c\":\"" + left + "\",\"right__c\":\"" + right + "\"}}";

        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> admin.postJson(JUNCTION, body).status());
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> admin.postJson(JUNCTION, body).status());

        assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 400);
    }

    @Test
    void olderRecordsWhosePointersAreStillBeingWrittenBlockARemovalUntilTheyAreKnown() {
        Organization organization = organization();
        TestBrowser admin = world(organization);
        String parent = record(admin, PARENT, "{}");
        String clearer = record(admin, CLEARER, "{\"parent__c\":\"" + parent + "\"}");
        ownerRuns("delete from record_link where source_record = '" + clearer + "'");
        ownerRuns("delete from link_index where tenant_id = '" + organization.id().value() + "'");
        backfill.workOn(organization.id());
        ownerRuns("update link_index set state = 'FILLING', cursor_id = null where tenant_id = '"
                + organization.id().value() + "'");

        Response early = remove(admin, PARENT, parent);

        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body()).contains("still being prepared");
        backfill.workOn(organization.id());
        assertThat(remove(admin, PARENT, parent).status()).isEqualTo(204);
        assertThat(fieldsOf(admin.get(CLEARER + "/" + clearer))).as("found by the fill, then cleared")
                .doesNotContainKey("parent__c");
    }

    @Test
    void aPointerToARecordOfAnotherOrganizationIsNeverCounted() {
        TestBrowser mine = world(organization());
        TestBrowser theirs = world(organization());
        String parent = record(mine, PARENT, "{}");
        record(theirs, REFUSER, "{}");
        String foreign = record(theirs, PARENT, "{}");
        Response refused = theirs.postJson(REFUSER, "{\"fields\":{\"parent__c\":\"" + parent + "\"}}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(remove(mine, PARENT, parent).status()).isEqualTo(204);
        assertThat(remove(theirs, PARENT, foreign).status()).isEqualTo(204);
    }
}
