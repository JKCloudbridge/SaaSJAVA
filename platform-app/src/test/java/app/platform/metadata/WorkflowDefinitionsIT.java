package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Workflow definitions through the real API (Sprint 22, ADR-0136): a workflow round-trips, a new version of the
 * definition is made only when the trigger, the condition or the actions change, every refusal names the place, a
 * field or an object a workflow names cannot be removed and the refusal names the workflow, the plan feature and the
 * abilities are asked, and the lifecycle (change sets, releases, rollback) treats workflows like every other
 * definition.
 */
@PlatformIntegrationTest
class WorkflowDefinitionsIT extends WorkflowTestSupport {

    private static final String BIG = "quantity__c > 100";

    private static boolean active(Response response) {
        return JsonPath.<Boolean>read(response.body(), "$.data.active");
    }

    private static int definitionVersion(Response response) {
        return JsonPath.<Integer>read(response.body(), "$.data.definitionVersion");
    }

    // ---- round trip ----

    @Test
    void aWorkflowRoundTripsThroughTheApi() {
        TestBrowser admin = adminWithOrder();

        Response created = createWorkflow(admin, "ReviewBigOrders", trigger("RECORD_CREATED"), BIG,
                announce("Ask", "order.review_requested"));
        Response one = admin.get(ORDER_WORKFLOWS + "/ReviewBigOrders__c");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.header("Cache-Control")).hasValue("no-store");
        assertThat(JsonPath.<String>read(one.body(), "$.data.apiName")).isEqualTo("ReviewBigOrders__c");
        assertThat(JsonPath.<String>read(one.body(), "$.data.objectApiName")).isEqualTo("Order__c");
        assertThat(JsonPath.<String>read(one.body(), "$.data.condition")).isEqualTo(BIG);
        assertThat(JsonPath.<String>read(one.body(), "$.data.trigger.kind")).isEqualTo("RECORD_CREATED");
        assertThat(JsonPath.<String>read(one.body(), "$.data.actions[0].type")).isEqualTo("PUBLISH_EVENT");
        assertThat(JsonPath.<String>read(one.body(), "$.data.actions[0].settings.event"))
                .isEqualTo("order.review_requested");
        assertThat(active(one)).isTrue();
        assertThat(definitionVersion(one)).isEqualTo(1);
        assertThat(workflowNames(admin)).containsExactly("ReviewBigOrders__c");
        assertThat(JsonPath.<List<String>>read(admin.get(WORKFLOWS).body(), "$.data[*].apiName"))
                .containsExactly("ReviewBigOrders__c");
    }

    // ---- versions ----

    @Test
    void aNewVersionIsMadeOnlyWhenTheTriggerTheConditionOrTheActionsChange() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        workflowExists(admin, "Watch", trigger("RECORD_CREATED"), BIG, announce("Ask", "first.event"));
        String path = ORDER_WORKFLOWS + "/Watch__c";

        Response condition = admin.request("PUT", path, workflowUpdate("Watch", trigger("RECORD_CREATED"),
                "quantity__c > 200", true, workflowVersion(admin, "Watch__c"), announce("Ask", "first.event")));
        Response label = admin.request("PUT", path, workflowUpdate("A new label", trigger("RECORD_CREATED"),
                "quantity__c > 200", true, workflowVersion(admin, "Watch__c"), announce("Ask", "first.event")));
        Response off = admin.postJson(path + "/activation", "{\"active\":false,\"version\":"
                + workflowVersion(admin, "Watch__c") + "}");
        Response actions = admin.request("PUT", path, workflowUpdate("A new label", trigger("RECORD_CREATED"),
                "quantity__c > 200", false, workflowVersion(admin, "Watch__c"), announce("Ask", "second.event"),
                announce("Also", "third.event")));
        Response same = admin.request("PUT", path, workflowUpdate("A new label", trigger("RECORD_CREATED"),
                "quantity__c > 200", false, workflowVersion(admin, "Watch__c"), announce("Ask", "second.event"),
                announce("Also", "third.event")));

        assertThat(condition.status()).as(condition.body()).isEqualTo(200);
        assertThat(definitionVersion(condition)).as("a new condition").isEqualTo(2);
        assertThat(definitionVersion(label)).as("a new label").isEqualTo(2);
        assertThat(definitionVersion(off)).as("switching off").isEqualTo(2);
        assertThat(active(off)).isFalse();
        assertThat(definitionVersion(actions)).as("new actions").isEqualTo(3);
        assertThat(definitionVersion(same)).as("nothing changed").isEqualTo(3);
        assertThat(count(organization, "select count(*) from workflow_version")).isEqualTo(3);
        assertThat(JsonPath.<Number>read(same.body(), "$.data.version").longValue())
                .as("a request that changes nothing changes nothing").isEqualTo(
                        JsonPath.<Number>read(actions.body(), "$.data.version").longValue());
    }

    @Test
    void aChangeBuiltOnAnOlderVersionOfTheWorkflowIsRefused() {
        TestBrowser admin = adminWithOrder();
        workflowExists(admin, "Watch", trigger("RECORD_CREATED"), null, announce("Ask", "first.event"));
        long stale = workflowVersion(admin, "Watch__c");
        admin.postJson(ORDER_WORKFLOWS + "/Watch__c/activation", "{\"active\":false,\"version\":" + stale + "}");

        Response late = admin.request("PUT", ORDER_WORKFLOWS + "/Watch__c", workflowUpdate("Late",
                trigger("RECORD_CREATED"), null, true, stale, announce("Ask", "first.event")));
        Response lateSwitch = admin.postJson(ORDER_WORKFLOWS + "/Watch__c/activation", "{\"active\":true,"
                + "\"version\":" + stale + "}");

        assertThat(late.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(late.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(lateSwitch.status()).isEqualTo(409);
    }

    // ---- refusals name the place ----

    @Test
    void aRequestThatBreaksTheRulesIsRefusedWithEveryPlaceAndNothingIsSaved() {
        TestBrowser admin = adminWithOrder();

        Response external = createWorkflow(admin, "Hook", trigger("WEBHOOK"), null, announce("Ask", "a.b"));
        Response zone = createWorkflow(admin, "Zone", "{\"kind\":\"EVERY_DAY\",\"timeZone\":\"Mars/Base\"}", null,
                announce("Ask", "a.b"));
        Response type = createWorkflow(admin, "Odd", trigger("RECORD_CREATED"), null,
                "{\"type\":\"RUN_SCRIPT\",\"name\":\"Script\",\"settings\":{}}");
        Response event = createWorkflow(admin, "NoEvent", trigger("RECORD_CREATED"), null,
                "{\"type\":\"PUBLISH_EVENT\",\"name\":\"Ask\",\"settings\":{}}");
        Response grammar = createWorkflow(admin, "Broken", trigger("RECORD_CREATED"), "quantity__c >\\n  and",
                announce("Ask", "a.b"));
        Response noFields = createWorkflow(admin, "Empty", trigger("FIELD_CHANGED"), null, announce("Ask", "a.b"));

        assertThat(external.status()).as(external.body()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(external.body(), "$.error.fields['trigger.kind']")).singleElement()
                .asString().contains("external systems are not available yet");
        assertThat(JsonPath.<Object>read(zone.body(), "$.error.fields['trigger.timeZone']")).isNotNull();
        assertThat(JsonPath.<Object>read(type.body(), "$.error.fields['actions[0].type']")).isNotNull();
        assertThat(JsonPath.<Object>read(event.body(), "$.error.fields['actions[0].settings.event']")).isNotNull();
        assertThat(JsonPath.<String>read(grammar.body(), "$.error.fields.condition[0]"))
                .startsWith("Line 2, column 3: AND joins two conditions");
        assertThat(JsonPath.<Object>read(noFields.body(), "$.error.fields['trigger.fields']")).isNotNull();
        assertThat(workflowNames(admin)).isEmpty();
    }

    @Test
    void aConditionThatDoesNotGiveTrueOrFalseIsRefusedAndNamesTheWorkflow() {
        TestBrowser admin = adminWithOrder();

        Response refused = createWorkflow(admin, "NotAnAnswer", trigger("RECORD_CREATED"), "quantity__c + 1",
                announce("Ask", "a.b"));

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("Workflow NotAnAnswer__c of Order__c has a condition that is not "
                + "valid").contains("true or false");
        assertThat(workflowNames(admin)).isEmpty();
    }

    @Test
    void aWorkflowThatNamesAFieldTheObjectDoesNotHaveIsRefusedAndNamesTheWorkflowAndTheField() {
        TestBrowser admin = adminWithOrder();

        Response condition = createWorkflow(admin, "Ghost", trigger("RECORD_CREATED"), "ghost__c > 1",
                announce("Ask", "a.b"));
        Response watched = createWorkflow(admin, "Watched", fieldsChanged("gone__c"), null, announce("Ask", "a.b"));

        assertThat(condition.status()).as(condition.body()).isEqualTo(409);
        assertThat(firstProblem(condition)).contains("workflow Ghost__c of Order__c uses field Order__c.ghost__c, "
                + "which does not exist");
        assertThat(watched.status()).isEqualTo(409);
        assertThat(firstProblem(watched)).contains("workflow Watched__c of Order__c starts when it changes field "
                + "Order__c.gone__c");
    }

    @Test
    void theDaysAfterTriggerNeedsADateFieldAndASelectionMustFitTheQueryModel() {
        TestBrowser admin = adminWithOrder();

        Response text = createWorkflow(admin, "OnText", "{\"kind\":\"DAYS_AFTER_DATE\",\"field\":\"status__c\","
                + "\"days\":3}", null, announce("Ask", "a.b"));
        Response date = createWorkflow(admin, "OnDate", "{\"kind\":\"DAYS_AFTER_DATE\",\"field\":\"closeDate__c\","
                + "\"days\":3,\"timeZone\":\"Europe/Berlin\",\"time\":\"06:30\"}", null, announce("Ask", "a.b"));
        Response badComparison = createWorkflow(admin, "BadSelection", "{\"kind\":\"EVERY_DAY\",\"selection\":"
                + "{\"op\":\"CONTAINS\",\"field\":\"quantity__c\",\"values\":[\"x\"]}}", null, announce("Ask", "a.b"));
        Response goodSelection = createWorkflow(admin, "GoodSelection", "{\"kind\":\"EVERY_DAY\",\"selection\":"
                + "{\"op\":\"EQ\",\"field\":\"status__c\",\"values\":[\"Open\"]}}", null, announce("Ask", "a.b"));

        assertThat(text.status()).as(text.body()).isEqualTo(409);
        assertThat(firstProblem(text)).contains("counts days from Order__c.status__c, which is not a date");
        assertThat(date.status()).as(date.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(date.body(), "$.data.trigger.timeZone")).isEqualTo("Europe/Berlin");
        assertThat(badComparison.status()).as(badComparison.body()).isEqualTo(409);
        assertThat(firstProblem(badComparison)).contains("has a selection that is not valid");
        assertThat(goodSelection.status()).as(goodSelection.body()).isEqualTo(201);
    }

    // ---- what a workflow names cannot be removed ----

    @Test
    void aFieldAWorkflowNamesCannotBeRemovedAndTheRefusalNamesTheWorkflow() {
        TestBrowser admin = adminWithOrder();
        workflowExists(admin, "Watch", fieldsChanged("status__c"), "quantity__c > 1", announce("Ask", "a.b"));

        Response removeField = admin.request("DELETE", OBJECTS + "/Order__c/fields/status__c", null);
        Response removeConditionField = admin.request("DELETE", OBJECTS + "/Order__c/fields/quantity__c", null);
        Response removeObject = admin.request("DELETE", OBJECTS + "/Order__c", null);

        assertThat(removeField.status()).as(removeField.body()).isEqualTo(409);
        assertThat(firstProblem(removeField)).contains("workflow Watch__c of Order__c starts when it changes field "
                + "Order__c.status__c");
        assertThat(removeConditionField.status()).isEqualTo(409);
        assertThat(firstProblem(removeConditionField)).contains("workflow Watch__c of Order__c uses field "
                + "Order__c.quantity__c");
        assertThat(removeObject.status()).isEqualTo(409);
        assertThat(fieldNames(admin, "Order__c")).contains("status__c", "quantity__c");
    }

    @Test
    void onceTheWorkflowIsRemovedTheFieldCanBeToo() {
        TestBrowser admin = adminWithOrder();
        workflowExists(admin, "Watch", fieldsChanged("status__c"), null, announce("Ask", "a.b"));

        Response removedWorkflow = admin.request("DELETE", ORDER_WORKFLOWS + "/Watch__c", null);
        Response removedField = admin.request("DELETE", OBJECTS + "/Order__c/fields/status__c", null);

        assertThat(removedWorkflow.status()).isEqualTo(204);
        assertThat(removedField.status()).as(removedField.body()).isIn(200, 204);
        assertThat(admin.get(ORDER_WORKFLOWS + "/Watch__c").status()).isEqualTo(404);
    }

    // ---- the lifecycle ----

    @Test
    void aChangeSetThatPublishesAWorkflowIsRolledBackAndTheUndoRemovesIt() {
        TestBrowser admin = adminWithOrder();
        String set = createSet(admin, "Automation");
        Response added = addChange(admin, set, change("CREATE_WORKFLOW", "Order__c", null, "createWorkflow",
                workflowRequest("Watch", trigger("RECORD_CREATED"), BIG, true, announce("Ask", "a.b"))));
        Response checked = validate(admin, set);
        assertThat(workflowNames(admin)).as("a draft changes nothing").isEmpty();

        Response published = publish(admin, set);
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(workflowNames(admin)).containsExactly("Watch__c");
        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(workflowNames(admin)).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(RELEASES).body(), "$.data[0].items[*].kind"))
                .contains("WORKFLOW");
    }

    @Test
    void aRemovedWorkflowIsMadeAgainByTheUndoOfItsReleaseAsANewWorkflow() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        workflowExists(admin, "Watch", trigger("RECORD_CREATED"), BIG, announce("Ask", "a.b"));
        admin.postJson(ORDER_WORKFLOWS + "/Watch__c/activation", "{\"active\":false,\"version\":"
                + workflowVersion(admin, "Watch__c") + "}");
        admin.request("PUT", ORDER_WORKFLOWS + "/Watch__c", workflowUpdate("Watch", trigger("RECORD_CREATED"),
                "quantity__c > 7", false, workflowVersion(admin, "Watch__c"), announce("Ask", "a.b")));

        admin.request("DELETE", ORDER_WORKFLOWS + "/Watch__c", null);
        assertThat(workflowNames(admin)).isEmpty();
        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");
        Response back = admin.get(ORDER_WORKFLOWS + "/Watch__c");

        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(back.body(), "$.data.condition")).isEqualTo("quantity__c > 7");
        assertThat(active(back)).isFalse();
        assertThat(definitionVersion(back)).as("a new workflow starts at version 1").isEqualTo(1);
    }

    @Test
    void theUndoOfAChangePutsTheOlderDefinitionBackAsAnewVersion() {
        TestBrowser admin = adminWithOrder();
        workflowExists(admin, "Watch", trigger("RECORD_CREATED"), BIG, announce("Ask", "first.event"));
        admin.request("PUT", ORDER_WORKFLOWS + "/Watch__c", workflowUpdate("Watch", trigger("RECORD_CREATED"),
                "quantity__c > 7", true, workflowVersion(admin, "Watch__c"), announce("Ask", "second.event")));

        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");
        Response back = admin.get(ORDER_WORKFLOWS + "/Watch__c");

        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(back.body(), "$.data.condition")).isEqualTo(BIG);
        assertThat(JsonPath.<String>read(back.body(), "$.data.actions[0].settings.event")).isEqualTo("first.event");
        assertThat(definitionVersion(back)).as("versions only grow").isEqualTo(3);
    }

    @Test
    void aRecordPageAssignmentCanBePutInAChangeSetNow() {
        TestBrowser admin = adminWithOrder();
        String set = createSet(admin, "Pages");

        Response added = addChange(admin, set, change("CREATE_RECORD_PAGE_ASSIGNMENT", "Order__c", null,
                "createRecordPageAssignment", "{\"recordPage\":\"Odd__c\"}"));

        assertThat(added.status()).as("the check constraint of V052 did not know this kind: " + added.body())
                .isEqualTo(201);
    }

    // ---- the plan feature ----

    @Test
    void aWorkflowCanBeSwitchedOnOnlyWhenThePlanHasTheFeature() {
        Organization organization = organizationWithoutTheFeature();
        TestBrowser admin = adminWithOrder(organization);

        Response active = createWorkflow(admin, "Watch", trigger("RECORD_CREATED"), null, announce("Ask", "a.b"));
        Response inactive = admin.postJson(ORDER_WORKFLOWS, workflowRequest("Quiet", trigger("RECORD_CREATED"), null,
                false, announce("Ask", "a.b")));
        Response switchOn = admin.postJson(ORDER_WORKFLOWS + "/Quiet__c/activation", "{\"active\":true,\"version\":"
                + workflowVersion(admin, "Quiet__c") + "}");

        assertThat(active.status()).as(active.body()).isEqualTo(409);
        assertThat(firstProblem(active)).contains("not part of this organization's plan");
        assertThat(inactive.status()).as(inactive.body()).isEqualTo(201);
        assertThat(switchOn.status()).isEqualTo(409);
        assertThat(workflowNames(admin)).containsExactly("Quiet__c");
    }

    @Test
    void switchingOffAndRemovingAreNeverRefusedForThePlan() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        workflowExists(admin, "Watch", trigger("RECORD_CREATED"), null, announce("Ask", "a.b"));
        loseTheFeature(organization);

        Response off = admin.postJson(ORDER_WORKFLOWS + "/Watch__c/activation", "{\"active\":false,\"version\":"
                + workflowVersion(admin, "Watch__c") + "}");
        Response removed = admin.request("DELETE", ORDER_WORKFLOWS + "/Watch__c", null);

        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(removed.status()).isEqualTo(204);
    }

    // ---- abilities ----

    @Test
    void aViewerReadsAndAManagerWithoutPublishCannotMakeAnythingLive() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        workflowExists(admin, "Watch", trigger("RECORD_CREATED"), null, announce("Ask", "a.b"));
        TestBrowser viewer = as(organization, memberWith(organization, admin, "workflows.view"));
        TestBrowser manager = as(organization, memberWith(organization, admin, "workflows.view", "workflows.manage"));

        assertThat(viewer.get(WORKFLOWS).status()).isEqualTo(200);
        assertThat(viewer.get(ORDER_WORKFLOWS + "/Watch__c").status()).isEqualTo(200);
        assertThat(viewer.postJson(ORDER_WORKFLOWS, workflowRequest("Mine", trigger("RECORD_CREATED"), null, true,
                announce("Ask", "a.b"))).status()).isEqualTo(403);
        assertThat(viewer.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":null,\"values\":{}}").status())
                .isEqualTo(403);
        assertThat(manager.postJson(ORDER_WORKFLOWS, workflowRequest("Mine", trigger("RECORD_CREATED"), null, true,
                announce("Ask", "a.b"))).status()).as("a change at once is a publication").isEqualTo(403);
        assertThat(manager.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":\"quantity__c > 1\","
                + "\"values\":{\"quantity__c\":\"5\"}}").status()).isEqualTo(200);
    }

    @Test
    void whoeverAppliesAWorkflowChangeFromAChangeSetNeedsTheAbilityToManageWorkflows() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        String set = createSet(admin, "Automation");
        addChange(admin, set, change("CREATE_WORKFLOW", "Order__c", null, "createWorkflow",
                workflowRequest("Watch", trigger("RECORD_CREATED"), null, true, announce("Ask", "a.b"))));
        TestBrowser publisher = as(organization, memberWith(organization, admin, "metadata.view",
                "metadata.publish"));
        TestBrowser both = as(organization, memberWith(organization, admin, "metadata.view", "metadata.publish",
                "workflows.manage"));

        Response refused = publish(publisher, set);
        assertThat(workflowNames(admin)).as("nothing was published").isEmpty();
        Response published = publish(both, set);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(workflowNames(admin)).containsExactly("Watch__c");
    }

    // ---- trying a condition ----

    @Test
    void aConditionCanBeTriedOnSampleValuesWithoutStoringAnything() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);

        Response runs = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":\"quantity__c > 10\","
                + "\"values\":{\"quantity__c\":\"20\"}}");
        Response skipped = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":\"quantity__c > 10\","
                + "\"values\":{\"quantity__c\":\"5\"}}");
        Response error = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":\"10 / quantity__c > 1\","
                + "\"values\":{\"quantity__c\":\"0\"}}");
        Response none = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":null,\"values\":{}}");
        Response invalid = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":\"quantity__c >\","
                + "\"values\":{}}");
        Response notAField = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":\"quantity__c > 1\","
                + "\"values\":{\"ghost__c\":\"1\"}}");

        assertThat(JsonPath.<String>read(runs.body(), "$.data.outcome")).isEqualTo("RUNS");
        assertThat(JsonPath.<String>read(skipped.body(), "$.data.outcome")).isEqualTo("SKIPPED");
        assertThat(JsonPath.<String>read(error.body(), "$.data.outcome")).isEqualTo("ERROR");
        assertThat(JsonPath.<Object>read(error.body(), "$.data.problem.message")).isNotNull();
        assertThat(JsonPath.<String>read(none.body(), "$.data.outcome")).isEqualTo("RUNS");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(JsonPath.<Object>read(invalid.body(), "$.error.fields.condition")).isNotNull();
        assertThat(notAField.status()).isEqualTo(400);
        assertThat(count(organization, "select count(*) from workflow")).isZero();
    }
}
