package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The actions in the definitions of workflows (ADR-0138): the catalogue the screens are built from, what is refused
 * when a workflow is saved (with the place), the fields an action names that cannot be removed from under it, the
 * three actions that wait for a later release, and the check that tries actions against the object without storing
 * anything.
 */
@PlatformIntegrationTest
class WorkflowActionDefinitionsIT extends WorkflowTestSupport {

    private static String set(String name, String... assignments) {
        return "{\"type\":\"UPDATE_RECORD\",\"name\":\"" + name + "\",\"settings\":{},\"assignments\":["
                + String.join(",", assignments) + "]}";
    }

    private static String literal(String field, String value) {
        return "{\"field\":\"" + field + "\",\"value\":\"" + value + "\"}";
    }

    private static String copy(String field, String from) {
        return "{\"field\":\"" + field + "\",\"fromField\":\"" + from + "\"}";
    }

    private static String mail(String name, String field) {
        return "{\"type\":\"SEND_EMAIL\",\"name\":\"" + name + "\",\"settings\":{\"recipientField\":\"" + field
                + "\",\"template\":\"RECORD_NOTICE\"}}";
    }

    private TestBrowser adminWithTask(Organization organization) {
        TestBrowser admin = adminWithOrder(organization);
        createObject(admin, "Task");
        for (String field : List.of("{\"name\":\"title\",\"label\":\"title\",\"type\":\"TEXT\",\"required\":true}",
                "{\"name\":\"count\",\"label\":\"count\",\"type\":\"NUMBER\"}",
                "{\"name\":\"order\",\"label\":\"order\",\"type\":\"LOOKUP\","
                        + "\"settings\":{\"targetObject\":\"Order__c\"}}")) {
            Response created = createField(admin, "Task__c", field);
            assertThat(created.status()).as(created.body()).isEqualTo(201);
        }
        return admin;
    }

    // ---- the catalogue ----

    @Test
    void theCatalogueGivesTheTypesTheKindsAndTheLimitsSoTheScreensHoldNoListOfTheirOwn() {
        TestBrowser admin = adminWithOrder(organization());

        Response catalogue = admin.get(WORKFLOWS + "/action-types");

        assertThat(catalogue.status()).as(catalogue.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.actionTypes[*].type")).containsExactly(
                "UPDATE_RECORD", "CREATE_RECORD", "SEND_EMAIL", "PUBLISH_EVENT");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.actionTypes[*].assignments"))
                .containsExactly("TRIGGERING_OBJECT", "NAMED_OBJECT", "NONE", "NONE");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.actionTypes[1].settings[*].refersTo"))
                .containsExactly("OBJECT", "ASSIGNED_FIELD");
        assertThat(JsonPath.<String>read(catalogue.body(), "$.data.actionTypes[1].assignedObject")).isEqualTo("object");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.actionTypes[2].settings[1].allowed"))
                .containsExactly("RECORD_NOTICE");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.triggerKinds[*].kind")).contains(
                "RECORD_CREATED", "FIELD_CHANGED", "EVERY_DAY", "DAYS_AFTER_DATE");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.triggerKinds[?(@.kind=='EVERY_WEEK')]"
                + ".properties[*]")).containsExactly("timeZone", "time", "dayOfWeek", "selection");
        assertThat(JsonPath.<Integer>read(catalogue.body(), "$.data.limits.maxActionsPerWorkflow")).isEqualTo(10);
        assertThat(JsonPath.<Integer>read(catalogue.body(), "$.data.limits.maxAttempts")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(catalogue.body(), "$.data.limits.maxChainDepth")).isEqualTo(5);
    }

    // ---- good definitions ----

    @Test
    void everyKindOfActionCanBeSaved() {
        TestBrowser admin = adminWithTask(organization());
        addUserField(admin);

        Response created = admin.postJson(ORDER_WORKFLOWS, workflowRequest("Everything", trigger("RECORD_UPDATED"),
                null, true,
                set("Mark", literal("status__c", "done"), copy("note__c", "status__c")),
                "{\"type\":\"CREATE_RECORD\",\"name\":\"Follow\",\"settings\":{\"object\":\"Task__c\","
                        + "\"linkField\":\"order__c\"},\"assignments\":[" + literal("title__c", "Follow up") + ","
                        + copy("count__c", "quantity__c") + "],\"maxAttempts\":3}",
                mail("Tell", "owner__c"), announce("Done", "order.done")));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(created.body(), "$.data.actions[*].type")).containsExactly(
                "UPDATE_RECORD", "CREATE_RECORD", "SEND_EMAIL", "PUBLISH_EVENT");
        assertThat(JsonPath.<Integer>read(created.body(), "$.data.actions[1].maxAttempts")).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(created.body(), "$.data.actions[0].assignments[*].field"))
                .containsExactly("status__c", "note__c");
    }

    private static void addUserField(TestBrowser admin) {
        Response created = createField(admin, "Order__c", "{\"name\":\"owner\",\"label\":\"owner\",\"type\":\"LOOKUP\","
                + "\"settings\":{\"targetObject\":\"User\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    // ---- what is refused when it is saved ----

    @Test
    void aShapeThatIsWrongIsRefusedWithThePlaceOfEveryProblem() {
        TestBrowser admin = adminWithTask(organization());

        Response refused = admin.postJson(ORDER_WORKFLOWS, workflowRequest("Wrong", trigger("RECORD_CREATED"), null,
                true,
                set("Empty"),
                set("Both", "{\"field\":\"status__c\",\"value\":\"x\",\"fromField\":\"note__c\"}"),
                set("Neither", "{\"field\":\"status__c\"}"),
                set("Twice", literal("status__c", "a"), literal("status__c", "b")),
                "{\"type\":\"PUBLISH_EVENT\",\"name\":\"Announce\",\"settings\":{\"event\":\"a.b\"},\"assignments\":["
                        + literal("status__c", "x") + "]}",
                set("Greedy", literal("status__c", "a")).replace("\"assignments\"", "\"maxAttempts\":9,"
                        + "\"assignments\"")));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        Map<String, List<String>> fields = JsonPath.read(refused.body(), "$.error.fields");
        assertThat(fields).containsKeys("actions[0].assignments", "actions[1].assignments[0]",
                "actions[2].assignments[0]", "actions[3].assignments[1].field", "actions[4].assignments",
                "actions[5].maxAttempts");
    }

    @Test
    void aLiteralOverTheHardLimitIsRefusedAtTheDoorWithItsPlace() {
        TestBrowser admin = adminWithTask(organization());

        Response refused = admin.postJson(ORDER_WORKFLOWS, workflowRequest("Long", trigger("RECORD_CREATED"), null,
                true, set("Long", literal("status__c", "x".repeat(1001)))));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("actions[0].assignments[0].value").doesNotContain("xxxxxxxxxx");
    }

    @Test
    void theLaterActionsAreRefusedAsUnknownTypesAndTheAnswerListsWhatThisReleaseHas() {
        TestBrowser admin = adminWithTask(organization());

        for (String type : List.of("START_APPROVAL", "NOTIFY", "CALL_INTEGRATION")) {
            Response refused = admin.postJson(ORDER_WORKFLOWS, workflowRequest("Later", trigger("RECORD_CREATED"), null,
                    true, "{\"type\":\"" + type + "\",\"name\":\"X\",\"settings\":{}}"));

            assertThat(refused.status()).as(type).isEqualTo(400);
            assertThat(refused.body()).contains("actions[0].type").contains("UPDATE_RECORD").contains("PUBLISH_EVENT");
        }
    }

    @Test
    void aWorkflowCannotHaveMoreActionsThanTheLimit() {
        TestBrowser admin = adminWithOrder(organization());
        String[] eleven = java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(i -> announce("A" + i, "a.b"))
                .toArray(String[]::new);

        Response refused = admin.postJson(ORDER_WORKFLOWS, workflowRequest("Many", trigger("RECORD_CREATED"), null,
                true, eleven));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("at most 10 actions");
    }

    // ---- what is refused once the objects are known ----

    @Test
    void theFieldsAndTheTypesAreCheckedAgainstTheObjectsAndTheRefusalNamesThePlace() {
        TestBrowser admin = adminWithTask(organization());
        addUserField(admin);

        Response refused = admin.postJson(ORDER_WORKFLOWS, workflowRequest("Bad", trigger("RECORD_CREATED"), null,
                true,
                set("Ghost", literal("ghost__c", "x")),
                set("Typed", literal("quantity__c", "ten"), literal("closeDate__c", "31/01/2030")),
                set("Copy", copy("quantity__c", "closeDate__c")),
                "{\"type\":\"CREATE_RECORD\",\"name\":\"Make\",\"settings\":{\"object\":\"Task__c\"},"
                        + "\"assignments\":[" + literal("count__c", "1") + "]}",
                mail("Tell", "status__c")));

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("Order__c.ghost__c").contains("assignments[0].value")
                .contains("assignments[1].value").contains("assignments[0].fromField")
                .contains("required field title__c").contains("settings.recipientField");
    }

    @Test
    void aFieldThatAnActionNamesCannotBeRemovedLiveOrInAChangeSetAndTheRefusalNamesTheWorkflow() {
        TestBrowser admin = adminWithTask(organization());
        workflowExists(admin, "Mark", trigger("RECORD_CREATED"), null, set("Mark", literal("note__c", "done"),
                copy("status__c", "note__c")));

        Response live = admin.request("DELETE", "/api/v1/metadata/objects/Order__c/fields/note__c", null);
        Response copied = admin.request("DELETE", "/api/v1/metadata/objects/Order__c/fields/status__c", null);
        String set = createSet(admin, "removal");
        addChange(admin, set,
                "{\"kind\":\"DELETE_FIELD\",\"objectApiName\":\"Order__c\",\"itemApiName\":\"note__c\"}");
        Response publication = publish(admin, set);

        assertThat(live.status()).as(live.body()).isEqualTo(409);
        assertThat(live.body()).contains("Mark__c");
        assertThat(copied.status()).isEqualTo(409);
        assertThat(copied.body()).contains("Mark__c");
        assertThat(publication.status()).as(publication.body()).isEqualTo(409);
        assertThat(publication.body()).contains("Mark__c");
    }

    @Test
    void theObjectACreateNamesCannotBeRemovedFromUnderTheWorkflow() {
        TestBrowser admin = adminWithTask(organization());
        workflowExists(admin, "Follow", trigger("RECORD_CREATED"), null,
                "{\"type\":\"CREATE_RECORD\",\"name\":\"Make\",\"settings\":{\"object\":\"Task__c\"},"
                        + "\"assignments\":[" + literal("title__c", "x") + "]}");

        Response removed = admin.request("DELETE", "/api/v1/metadata/objects/Task__c", null);

        assertThat(removed.status()).as(removed.body()).isEqualTo(409);
        assertThat(removed.body()).contains("Follow__c");
    }

    // ---- trying the actions ----

    @Test
    void theCheckTriesTheActionsAgainstTheObjectAndStoresNothing() {
        TestBrowser admin = adminWithTask(organization());

        Response checked = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":\"quantity__c > 1\","
                + "\"values\":{\"quantity__c\":\"5\"},\"actions\":[" + set("Good", literal("status__c", "ok")) + ","
                + set("Bad", literal("quantity__c", "SECRET-VALUE"), literal("ghost__c", "x")) + ","
                + "{\"type\":\"CREATE_RECORD\",\"name\":\"Make\",\"settings\":{\"object\":\"Task__c\"},"
                + "\"assignments\":[]}]}");

        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(checked.body(), "$.data.outcome")).isEqualTo("RUNS");
        Map<String, List<String>> problems = JsonPath.read(checked.body(), "$.data.actionProblems");
        assertThat(problems).doesNotContainKey("actions[0]").containsKeys("actions[1].assignments[0].value",
                "actions[1].assignments[1].field", "actions[2].assignments");
        assertThat(checked.body()).doesNotContain("SECRET-VALUE");
        assertThat(workflowNames(admin)).isEmpty();
    }

    @Test
    void theCheckWithoutActionsAnswersAsBeforeAndRefusesNothingItWasNotAskedAbout() {
        TestBrowser admin = adminWithOrder(organization());

        Response checked = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":\"quantity__c > 1\","
                + "\"values\":{\"quantity__c\":\"0\"}}");

        assertThat(JsonPath.<String>read(checked.body(), "$.data.outcome")).isEqualTo("SKIPPED");
        assertThat(JsonPath.<Map<String, Object>>read(checked.body(), "$.data.actionProblems")).isEmpty();
    }

    // ---- an old definition still reads ----

    @Test
    void aWorkflowStoredBeforeAssignmentsExistedStillReadsWithNone() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        workflowExists(admin, "Old", trigger("RECORD_CREATED"), null, announce("Tell", "a.b"));
        // A stored version is immutable for everyone; the owner with triggers off plays "an older release wrote it".
        try (java.sql.Connection owner = app.platform.testsupport.TestDatabase.ownerConnection();
                java.sql.Statement statement = owner.createStatement()) {
            statement.execute("set session_replication_role = replica");
            statement.executeUpdate("update workflow_version set actions = '[{\"type\":\"PUBLISH_EVENT\","
                    + "\"name\":\"Tell\",\"settings\":{\"event\":\"a.b\"}}]'::jsonb where tenant_id = '"
                    + organization.id().value() + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("The statement failed: " + e.getSQLState(), e);
        }

        Response read = admin.get(ORDER_WORKFLOWS + "/Old__c");

        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(read.body(), "$.data.actions[0].assignments")).isEmpty();
    }
}
