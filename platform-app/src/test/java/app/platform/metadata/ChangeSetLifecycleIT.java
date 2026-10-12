package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Change sets (Sprint 11, ADR-0066) through the real application: a draft is invisible, checking keeps nothing,
 * publishing is all or nothing, the exit criterion of the sprint (an invalid dependency blocks publication with a
 * precise error), and what happens to a set that someone else's change has overtaken.
 */
@PlatformIntegrationTest
class ChangeSetLifecycleIT extends LifecycleSupport {

    private static final String CATALOGUE = "/api/v1/data-catalogue";

    private static String newProject() {
        return change("CREATE_OBJECT", "Project__c", null, "createObject", createObjectRequest("Project"));
    }

    private static String projectField(String requestJson) {
        return change("CREATE_FIELD", "Project__c", null, "createField", requestJson);
    }

    private TestBrowser adminWithDeal() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Deal");
        createField(admin, "Deal__c", requiredText("code"));
        createField(admin, "Deal__c", text("department"));
        createRecordType(admin, "Deal__c", recordType("NewBusiness", "[\"code__c\",\"department__c\"]", "[]"));
        return admin;
    }

    // ---- a draft is invisible ----

    @Test
    void aDraftIsInvisibleToEverythingThatReadsTheMetadata() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");

        Response added = addChange(admin, set, newProject());

        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(added.body(), "$.data.changeCount")).isEqualTo(1);
        assertThat(objectNames(admin)).doesNotContain("Project__c");
        assertThat(admin.get(OBJECTS + "/Project__c").status()).isEqualTo(404);
        assertThat(admin.get(CATALOGUE).body()).doesNotContain("Project__c");
        assertThat(JsonPath.<String>read(admin.get(SETS + "/" + set).body(), "$.data.status")).isEqualTo("DRAFT");
    }

    @Test
    void checkingASetReportsWhatItWouldDoAndKeepsNothing() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");
        addChange(admin, set, newProject());
        addChange(admin, set, projectField(text("code")));

        Response report = validate(admin, set);

        assertThat(report.status()).as(report.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isTrue();
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.items[*].action"))
                .containsExactly("ADDED", "ADDED");
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.items[*].kind"))
                .containsExactly("OBJECT", "FIELD");
        assertThat(objectNames(admin)).as("rolled back").doesNotContain("Project__c");
        assertThat(JsonPath.<List<Object>>read(admin.get(RELEASES).body(), "$.data")).isEmpty();
    }

    @Test
    void aPreviewShowsTheObjectsAsTheyWouldBe() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");
        addChange(admin, set, newProject());
        addChange(admin, set, projectField(text("code")));

        Response preview = admin.postJson(SETS + "/" + set + "/preview", "{}");

        assertThat(preview.status()).as(preview.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(preview.body(), "$.data.objects[*].apiName"))
                .containsExactly("Project__c");
        assertThat(JsonPath.<List<String>>read(preview.body(), "$.data.objects[0].fields[*].apiName"))
                .contains("code__c");
        assertThat(objectNames(admin)).doesNotContain("Project__c");
    }

    // ---- publishing ----

    @Test
    void publishingPutsTheObjectItsFieldsAndItsRecordTypeLiveTogether() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");
        addChange(admin, set, newProject());
        addChange(admin, set, projectField(requiredText("code")));
        addChange(admin, set, projectField(picklist("stage", "Open", "Done")));
        addChange(admin, set, change("CREATE_RECORD_TYPE", "Project__c", null, "createRecordType",
                recordType("Internal", "[\"code__c\",\"stage__c\"]",
                        "[{\"field\":\"stage__c\",\"values\":[\"Open\"]}]")));

        Response published = publish(admin, set);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(published.body(), "$.data.status")).isEqualTo("PUBLISHED");
        assertThat(JsonPath.<Integer>read(published.body(), "$.data.releaseNumber")).isEqualTo(1);
        assertThat(objectNames(admin)).contains("Project__c");
        assertThat(fieldNames(admin, "Project__c")).contains("code__c", "stage__c");
        assertThat(recordTypeNames(admin, "Project__c")).containsExactly("Internal__c");
        Response history = admin.get(RELEASES);
        assertThat(JsonPath.<List<String>>read(history.body(), "$.data[*].kind")).containsExactly("CHANGE_SET");
        assertThat(JsonPath.<List<String>>read(history.body(), "$.data[*].changeSetName")).containsExactly("Projects");
        assertThat(JsonPath.<List<String>>read(history.body(), "$.data[0].items[*].kind"))
                .containsExactly("OBJECT", "FIELD", "FIELD", "RECORD_TYPE");
    }

    @Test
    void aPublishedSetCanNoLongerBeChangedOrPublishedAgain() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");
        addChange(admin, set, newProject());
        publish(admin, set);

        Response add = addChange(admin, set, projectField(text("code")));
        Response again = publish(admin, set);
        Response discard = admin.request("DELETE", SETS + "/" + set, null);

        assertThat(add.status()).isEqualTo(409);
        assertThat(again.status()).isEqualTo(409);
        assertThat(discard.status()).isEqualTo(409);
    }

    @Test
    void aSetWithABadChangeIsPublishedNotAtAllEvenThoughItsOtherChangesAreGood() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Half");
        addChange(admin, set, newProject());
        addChange(admin, set, change("CREATE_FIELD", "Ghost__c", null, "createField", text("code")));

        Response report = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isFalse();
        assertThat(JsonPath.<List<Integer>>read(report.body(), "$.data.problems[*].position")).containsExactly(2);
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.problems[*].kind")).containsExactly("RULE");
        assertThat(published.status()).isEqualTo(409);
        assertThat(JsonPath.<List<String>>read(published.body(), "$.error.fields.problems")).hasSize(1);
        assertThat(objectNames(admin)).as("the good change was not kept either").doesNotContain("Project__c");
        assertThat(JsonPath.<String>read(admin.get(SETS + "/" + set).body(), "$.data.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<List<Object>>read(admin.get(RELEASES).body(), "$.data")).isEmpty();
    }

    @Test
    void everyProblemOfASetIsToldAtOnce() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Many");
        addChange(admin, set, change("CREATE_FIELD", "Ghost__c", null, "createField", text("a")));
        addChange(admin, set, change("DELETE_FIELD", "Account", "name", null, null));
        addChange(admin, set, change("UPDATE_OBJECT", "Account", null, "updateObject",
                "{\"label\":\"x\",\"pluralLabel\":\"xs\",\"description\":\"\",\"version\":0}"));

        Response report = validate(admin, set);

        assertThat(JsonPath.<List<Integer>>read(report.body(), "$.data.problems[*].position"))
                .containsExactly(1, 2, 3);
    }

    @Test
    void aChangeSetCannotChangeWhatThePlatformDefines() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Protected");
        addChange(admin, set, change("DELETE_OBJECT", "Account", null, null, null));

        Response report = validate(admin, set);

        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isFalse();
        assertThat(problems(report)).containsExactly(
                "This is defined by the platform and cannot be changed. You can add your own fields to a standard "
                        + "object where the platform allows it.");
    }

    // ---- the exit criterion ----

    @Test
    void anInvalidDependencyBlocksPublicationAndTheErrorNamesTheDependent() {
        TestBrowser admin = adminWithDeal();
        String set = createSet(admin, "Drop department");
        addChange(admin, set, change("DELETE_FIELD", "Deal__c", "department__c", null, null));

        Response report = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isFalse();
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.problems[*].kind"))
                .containsExactly("DEPENDENCY");
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.problems[*].dependent"))
                .containsExactly("record type NewBusiness__c of Deal__c");
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.problems[*].itemApiName"))
                .containsExactly("department__c");
        assertThat(problems(report).get(0)).contains("offers field Deal__c.department__c");
        assertThat(published.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(published.body(), "$.error.message"))
                .contains("record type NewBusiness__c of Deal__c offers field Deal__c.department__c");
        assertThat(fieldNames(admin, "Deal__c")).contains("department__c");
    }

    @Test
    void aSetThatFixesTheDependencyInTheSameSetCanBePublished() {
        TestBrowser admin = adminWithDeal();
        String set = createSet(admin, "Drop department");
        addChange(admin, set, change("DELETE_FIELD", "Deal__c", "department__c", null, null));
        addChange(admin, set, change("UPDATE_RECORD_TYPE", "Deal__c", "NewBusiness__c", "updateRecordType",
                "{\"label\":\"NewBusiness\",\"description\":\"\",\"availableFields\":[\"code__c\"],"
                        + "\"picklistSubsets\":[],\"version\":0}"));

        Response published = publish(admin, set);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Deal__c")).doesNotContain("department__c");
        assertThat(JsonPath.<List<String>>read(admin.get(OBJECTS + "/Deal__c/record-types/NewBusiness__c").body(),
                "$.data.availableFields")).containsExactly("code__c");
    }

    @Test
    void removingAnObjectAndTheFieldThatPointsAtItInOneSetWorks() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createObject(admin, "Member");
        createField(admin, "Member__c", reference("team", "LOOKUP", "Team__c", ""));
        String set = createSet(admin, "Drop team");
        addChange(admin, set, change("DELETE_FIELD", "Member__c", "team__c", null, null));
        addChange(admin, set, change("DELETE_OBJECT", "Team__c", null, null, null));

        Response published = publish(admin, set);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(objectNames(admin)).doesNotContain("Team__c").contains("Member__c");
    }

    // ---- others' changes ----

    @Test
    void aSetOvertakenByAnotherChangeIsToldTheConflict() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        String set = createSet(admin, "Rename");
        addChange(admin, set, change("UPDATE_OBJECT", "Team__c", null, "updateObject",
                "{\"label\":\"Squad\",\"pluralLabel\":\"Squads\",\"description\":\"\",\"version\":0}"));
        admin.request("PUT", OBJECTS + "/Team__c",
                "{\"label\":\"Crew\",\"pluralLabel\":\"Crews\",\"description\":\"\",\"version\":0}");

        Response report = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.problems[*].kind")).containsExactly("CONFLICT");
        assertThat(published.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(admin.get(OBJECTS + "/Team__c").body(), "$.data.label")).isEqualTo("Crew");
    }

    // ---- housekeeping ----

    @Test
    void aSetCanBeDiscardedAndNothingInItWasEverLive() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");
        addChange(admin, set, newProject());

        Response discarded = admin.request("DELETE", SETS + "/" + set, null);
        Response add = addChange(admin, set, projectField(text("code")));

        assertThat(discarded.status()).isEqualTo(204);
        assertThat(JsonPath.<String>read(admin.get(SETS + "/" + set).body(), "$.data.status"))
                .isEqualTo("DISCARDED");
        assertThat(add.status()).isEqualTo(409);
        assertThat(publish(admin, set).status()).isEqualTo(409);
        assertThat(objectNames(admin)).doesNotContain("Project__c");
    }

    @Test
    void aChangeCanBeTakenOutOfADraft() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");
        addChange(admin, set, newProject());
        Response two = addChange(admin, set, projectField(text("code")));
        String first = JsonPath.read(two.body(), "$.data.changes[0].id");

        Response removed = admin.request("DELETE", SETS + "/" + set + "/changes/" + first, null);

        assertThat(removed.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(removed.body(), "$.data.changes[*].kind"))
                .containsExactly("CREATE_FIELD");
        assertThat(admin.request("DELETE", SETS + "/" + set + "/changes/" + first, null).status()).isEqualTo(404);
    }

    @Test
    void anEmptySetCannotBePublishedAndTwoOpenSetsCannotShareAName() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Same");

        Response empty = publish(admin, set);
        Response duplicate = admin.postJson(SETS, "{\"name\":\"same\",\"description\":\"\"}");

        assertThat(empty.status()).isEqualTo(409);
        assertThat(duplicate.status()).isEqualTo(400);
    }

    @Test
    void aChangeThatDoesNotSayWhatItNeedsIsRefusedWhenItIsAdded() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");

        Response noRequest = addChange(admin, set, change("CREATE_FIELD", "Project__c", null, null, null));
        Response unknownKind = addChange(admin, set, change("TELEPORT", "Project__c", null, null, null));
        Response noItem = addChange(admin, set, change("DELETE_FIELD", "Project__c", null, null, null));
        Response wrongName = addChange(admin, set, change("CREATE_OBJECT", "Other__c", null, "createObject",
                createObjectRequest("Project")));

        assertThat(List.of(noRequest.status(), unknownKind.status(), noItem.status(), wrongName.status()))
                .containsOnly(400);
    }

    @Test
    void theSetsAreListedOpenOnesFirst() {
        TestBrowser admin = adminOf(organization());
        String done = createSet(admin, "Done");
        addChange(admin, done, newProject());
        publish(admin, done);
        createSet(admin, "Open");

        Response list = admin.get(SETS);

        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[*].name")).containsExactly("Open", "Done");
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[*].status")).containsExactly("DRAFT", "PUBLISHED");
    }

    // ---- a quick change is a release of one change ----

    @Test
    void aChangeMadeAtOnceIsAReleaseOfOneChangeAndAnUnchangedOneIsNot() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        String same = "{\"label\":\"Team\",\"pluralLabel\":\"Teams\",\"description\":\"\",\"version\":0}";

        admin.request("PUT", OBJECTS + "/Team__c", same);
        Response history = admin.get(RELEASES);

        assertThat(JsonPath.<List<String>>read(history.body(), "$.data[*].kind")).containsExactly("QUICK");
        assertThat(JsonPath.<List<Boolean>>read(history.body(), "$.data[*].latest")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(history.body(), "$.data[0].items[*].action")).containsExactly("ADDED");
    }
}
