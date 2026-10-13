package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Links between records on write (Sprint 15, ADR-0097): a lookup or master-detail value must be the identifier of a
 * live record of the target object in the same organization, and every way of being wrong gets one answer, so nobody
 * learns what exists elsewhere.
 */
@PlatformIntegrationTest
class RecordLinksIT extends PipelineSupport {

    private static final String MISSING = "The linked record does not exist.";

    private static Response withLink(TestBrowser admin, String parent, String link) {
        return createIn(admin, MATRIX_RECORDS, "{\"parent__c\":\"" + parent + "\",\"link__c\":\"" + link + "\"}");
    }

    @Test
    void aLinkToALiveRecordOfTheTargetObjectIsAccepted() {
        Matrix matrix = matrix();

        Response created = withLink(matrix.admin(), matrix.targetId(), matrix.targetId());

        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    @Test
    void everyWayOfLinkingToNothingGetsTheSameAnswer() {
        Matrix matrix = matrix();
        TestBrowser admin = matrix.admin();
        String ofAnotherObject = idOf(createIn(admin, MATRIX_RECORDS, "{\"parent__c\":\"" + matrix.targetId() + "\"}"));
        String deleted = idOf(createIn(admin, TARGET_RECORDS, "{}"));
        admin.request("DELETE", TARGET_RECORDS + "/" + deleted, null);
        Matrix theirs = matrix();

        Response unknown = withLink(admin, matrix.targetId(), UUID.randomUUID().toString());
        Response otherObject = withLink(admin, matrix.targetId(), ofAnotherObject);
        Response inTheBin = withLink(admin, matrix.targetId(), deleted);
        Response otherOrganization = withLink(admin, matrix.targetId(), theirs.targetId());
        Response badParent = withLink(admin, UUID.randomUUID().toString(), matrix.targetId());

        for (Response refused : new Response[] {unknown, otherObject, inTheBin, otherOrganization}) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(placesOf(refused)).containsExactly("link__c");
            assertThat(problemAt(refused, "link__c")).isEqualTo(MISSING);
        }
        assertThat(problemAt(badParent, "parent__c")).isEqualTo(MISSING);
    }

    @Test
    void theOtherOrganizationsRecordIsNotChangedNorRevealedByTheAttempt() {
        Matrix mine = matrix();
        Matrix theirs = matrix();

        Response refused = withLink(mine.admin(), mine.targetId(), theirs.targetId());

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).doesNotContain(theirs.targetId());
        assertThat(theirs.admin().get(TARGET_RECORDS + "/" + theirs.targetId()).status()).isEqualTo(200);
    }

    @Test
    void aMasterDetailIsRequiredAndCannotBeClearedOrMovedUnlessTheFieldAllowsIt() {
        Matrix matrix = matrix();
        TestBrowser admin = matrix.admin();
        String other = idOf(createIn(admin, TARGET_RECORDS, "{}"));
        String id = idOf(createIn(admin, MATRIX_RECORDS, "{\"parent__c\":\"" + matrix.targetId() + "\"}"));

        Response none = createIn(admin, MATRIX_RECORDS, "{}");
        Response cleared = admin.request("PATCH", MATRIX_RECORDS + "/" + id,
                "{\"version\":0,\"clear\":[\"parent__c\"]}");
        Response moved = patchIn(admin, MATRIX_RECORDS, id, 0, "{\"parent__c\":\"" + other + "\"}");
        Response same = patchIn(admin, MATRIX_RECORDS, id, 0, "{\"parent__c\":\"" + matrix.targetId() + "\"}");

        assertThat(problemAt(none, "parent__c")).isEqualTo("A value is required.");
        assertThat(problemAt(cleared, "parent__c")).isEqualTo("A value is required.");
        assertThat(problemAt(moved, "parent__c")).isEqualTo("The master of this record cannot be changed.");
        assertThat(same.status()).as("naming the master it has is not moving it").isEqualTo(200);
    }

    @Test
    void removingTheTargetOfALookupClearsItSoTheRecordStillSavesAndRelinkingToItIsRefused() {
        Matrix matrix = matrix();
        TestBrowser admin = matrix.admin();
        String linked = idOf(createIn(admin, TARGET_RECORDS, "{}"));
        String id = idOf(withLink(admin, matrix.targetId(), linked));
        admin.request("DELETE", TARGET_RECORDS + "/" + linked, null);

        // Removing the target cleared the pointer (a lookup clears by default, ADR-0106): a change of the record by
        // the platform, so the record is at version 1.
        Response saved = patchIn(admin, MATRIX_RECORDS, id, 1, "{\"txt__c\":\"stillsave\"}");
        Response relinked = patchIn(admin, MATRIX_RECORDS, id, 2, "{\"link__c\":\"" + linked + "\"}");

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(relinked.status()).as("the pointer was cleared, so naming the removed record is a change")
                .isEqualTo(400);
    }

    @Test
    void aLinkToAUserWhoIsNotAMemberIsRefusedLikeAnyMissingRecordAndAnEmptyFieldWorks() {
        Matrix matrix = matrix();
        TestBrowser admin = matrix.admin();
        Response field = admin.postJson(METADATA_OBJECTS + "/Target__c/fields",
                "{\"name\":\"owner\",\"label\":\"owner\",\"type\":\"LOOKUP\","
                        + "\"settings\":{\"targetObject\":\"User\"}}");
        assertThat(field.status()).as(field.body()).isEqualTo(201);

        Response refused = createIn(admin, TARGET_RECORDS, "{\"owner__c\":\"" + UUID.randomUUID() + "\"}");
        Response empty = createIn(admin, TARGET_RECORDS, "{}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(problemAt(refused, "owner__c")).isEqualTo(MISSING);
        assertThat(empty.status()).isEqualTo(201);
    }
}
