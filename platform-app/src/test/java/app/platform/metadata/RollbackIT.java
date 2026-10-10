package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * History and rollback (Sprint 11, ADR-0067) through the real application: the latest release can be undone as a new
 * release, a removal comes back as a definition, a rollback can itself be rolled back, and only the latest can be
 * undone.
 */
@PlatformIntegrationTest
class RollbackIT extends LifecycleSupport {

    private static final String ROLLBACK = RELEASES + "/latest/rollback";

    @Test
    void nothingPublishedMeansNothingToRollBack() {
        TestBrowser admin = adminOf(organization());

        Response refused = admin.postJson(ROLLBACK, "{}");
        Response check = admin.postJson(ROLLBACK + "-check", "{}");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(check.status()).isEqualTo(409);
    }

    @Test
    void rollingBackAnAddedFieldRemovesItAndLeavesAReleaseOfItsOwn() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createField(admin, "Team__c", text("code"));

        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(rolledBack.body(), "$.data.kind")).isEqualTo("ROLLBACK");
        assertThat(JsonPath.<Integer>read(rolledBack.body(), "$.data.undoesRelease")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(rolledBack.body(), "$.data.number")).isEqualTo(3);
        assertThat(fieldNames(admin, "Team__c")).doesNotContain("code__c");
        Response history = admin.get(RELEASES);
        assertThat(JsonPath.<List<Integer>>read(history.body(), "$.data[*].number")).containsExactly(3, 2, 1);
        assertThat(JsonPath.<List<Integer>>read(history.body(), "$.data[?(@.number==2)].rolledBackBy"))
                .containsExactly(3);
        assertThat(JsonPath.<List<Boolean>>read(history.body(), "$.data[*].latest")).containsExactly(true, false,
                false);
    }

    @Test
    void aRollbackCanBeRolledBackToRedoWhatItUndid() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createField(admin, "Team__c", requiredText("code"));
        admin.postJson(ROLLBACK, "{}");

        Response redone = admin.postJson(ROLLBACK, "{}");

        assertThat(redone.status()).as(redone.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(redone.body(), "$.data.undoesRelease")).isEqualTo(3);
        assertThat(fieldNames(admin, "Team__c")).contains("code__c");
        assertThat(JsonPath.<List<Boolean>>read(admin.get(OBJECTS + "/Team__c").body(),
                "$.data.fields[?(@.apiName=='code__c')].required")).containsExactly(true);
    }

    @Test
    void rollingBackARemovalBringsTheDefinitionBack() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createField(admin, "Team__c", picklist("stage", "Open", "Done"));
        admin.request("DELETE", OBJECTS + "/Team__c/fields/stage__c", null);

        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        Response object = admin.get(OBJECTS + "/Team__c");
        assertThat(JsonPath.<List<String>>read(object.body(), "$.data.fields[?(@.apiName=='stage__c')].type"))
                .containsExactly("PICKLIST");
        assertThat(JsonPath.<List<String>>read(object.body(),
                "$.data.fields[?(@.apiName=='stage__c')].settings.values[*].value")).containsExactly("Open", "Done");
    }

    @Test
    void rollingBackTheRemovalOfAnObjectBringsBackItsFieldsAndRecordTypes() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createField(admin, "Team__c", requiredText("code"));
        createField(admin, "Team__c", text("note"));
        createRecordType(admin, "Team__c", recordType("Core", "[\"code__c\"]", "[]"));
        admin.request("DELETE", OBJECTS + "/Team__c", null);
        assertThat(objectNames(admin)).doesNotContain("Team__c");

        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Team__c")).contains("code__c", "note__c");
        assertThat(recordTypeNames(admin, "Team__c")).containsExactly("Core__c");
    }

    @Test
    void rollingBackAChangeSetUndoesAllOfItAtOnce() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");
        addChange(admin, set, change("CREATE_OBJECT", "Project__c", null, "createObject",
                createObjectRequest("Project")));
        addChange(admin, set, change("CREATE_FIELD", "Project__c", null, "createField", requiredText("code")));
        addChange(admin, set, change("CREATE_RECORD_TYPE", "Project__c", null, "createRecordType",
                recordType("Internal", "[\"code__c\"]", "[]")));
        publish(admin, set);

        Response check = admin.postJson(ROLLBACK + "-check", "{}");
        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).isTrue();
        assertThat(JsonPath.<List<String>>read(check.body(), "$.data.items[*].action"))
                .containsExactly("REMOVED", "REMOVED", "REMOVED");
        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(objectNames(admin)).doesNotContain("Project__c");
    }

    @Test
    void aRollbackCheckKeepsNothing() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createField(admin, "Team__c", text("code"));

        Response check = admin.postJson(ROLLBACK + "-check", "{}");

        assertThat(check.status()).as(check.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).isTrue();
        assertThat(fieldNames(admin, "Team__c")).contains("code__c");
        assertThat(JsonPath.<List<Integer>>read(admin.get(RELEASES).body(), "$.data[*].number"))
                .containsExactly(2, 1);
    }

    @Test
    void rollingBackAnUpdateRestoresTheOldLabelsAndAnEarlierReleaseCannotBeRolledBackOnItsOwn() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        admin.request("PUT", OBJECTS + "/Team__c",
                "{\"label\":\"Squad\",\"pluralLabel\":\"Squads\",\"description\":\"\",\"version\":0}");

        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(admin.get(OBJECTS + "/Team__c").body(), "$.data.label")).isEqualTo("Team");
        assertThat(admin.postJson(RELEASES + "/1/rollback", "{}").status())
                .as("only the latest release is rolled back").isIn(404, 405);
    }

    @Test
    void aDefaultRecordTypeIsRestoredWhenTheReleaseThatMovedItIsRolledBack() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createRecordType(admin, "Team__c", "{\"name\":\"First\",\"label\":\"First\",\"defaultType\":true}");
        createRecordType(admin, "Team__c", "{\"name\":\"Second\",\"label\":\"Second\",\"defaultType\":true}");

        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(recordTypeNames(admin, "Team__c")).containsExactly("First__c");
        assertThat(JsonPath.<Boolean>read(admin.get(OBJECTS + "/Team__c/record-types/First__c").body(),
                "$.data.defaultType")).isTrue();
    }

    @Test
    void publishingRollingBackAndRefusalsAreAllInTheTrail() {
        TestBrowser admin = adminOf(organization());
        String set = createSet(admin, "Projects");
        addChange(admin, set, change("CREATE_OBJECT", "Project__c", null, "createObject",
                createObjectRequest("Project")));
        validate(admin, set);
        publish(admin, set);
        admin.postJson(ROLLBACK, "{}");
        String bad = createSet(admin, "Bad");
        addChange(admin, bad, change("CREATE_FIELD", "Ghost__c", null, "createField", text("a")));
        publish(admin, bad);
        admin.request("DELETE", SETS + "/" + bad, null);

        Response events = admin.get("/api/v1/audit-events?kind=metadata&limit=200");

        assertThat(JsonPath.<List<String>>read(events.body(), "$.data[*].type")).contains(
                "metadata.changeset.created", "metadata.changeset.changed", "metadata.changeset.checked",
                "metadata.release.published", "metadata.release.rolledback", "metadata.publish.refused",
                "metadata.changeset.discarded");
        assertThat(JsonPath.<List<String>>read(events.body(),
                "$.data[?(@.type=='metadata.publish.refused')].outcome")).containsExactly("DENIED");
    }
}
