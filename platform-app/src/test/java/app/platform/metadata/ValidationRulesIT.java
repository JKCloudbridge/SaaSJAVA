package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation rules through the real API (Sprint 13, ADR-0078): a rule round-trips, every refusal names the place, a
 * rule keeps the fields it uses from being removed, and the lifecycle (change sets, releases, rollback) treats rules
 * like every other definition.
 */
@PlatformIntegrationTest
class ValidationRulesIT extends ExpressionTestSupport {

    private static final String CLOSE_NEEDS_DATE = "status__c = 'Closed' AND ISBLANK(closeDate__c)";

    @Test
    void aRuleRoundTripsThroughTheApi() {
        TestBrowser admin = adminWithOrder();

        Response created = admin.postJson(RULES, ruleRequest("CloseNeedsDate", CLOSE_NEEDS_DATE,
                "Give a close date.", "closeDate__c", true));
        Response one = admin.get(RULES + "/CloseNeedsDate__c");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(json(created, "$.data.apiName")).isEqualTo("CloseNeedsDate__c");
        assertThat(json(one, "$.data.objectApiName")).isEqualTo("Order__c");
        assertThat(json(one, "$.data.expression")).isEqualTo(CLOSE_NEEDS_DATE);
        assertThat(json(one, "$.data.errorMessage")).isEqualTo("Give a close date.");
        assertThat(json(one, "$.data.errorField")).isEqualTo("closeDate__c");
        assertThat(JsonPath.<Boolean>read(one.body(), "$.data.active")).isTrue();
        assertThat(versionOf(one)).isZero();
        assertThat(ruleNames(admin)).containsExactly("CloseNeedsDate__c");
        assertThat(created.header("Cache-Control")).hasValue("no-store");
    }

    @Test
    void aRuleCanBeChangedAndItsUndoRestoresItExactly() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "CloseNeedsDate", CLOSE_NEEDS_DATE, "Give a close date.");
        Response updated = admin.request("PUT", RULES + "/CloseNeedsDate__c",
                ruleUpdate("Renamed", "quantity__c < 0", "No negative quantities.", false, 0));

        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(versionOf(updated)).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(updated.body(), "$.data.active")).isFalse();
        assertThat(json(admin.get(RULES + "/CloseNeedsDate__c"), "$.data.expression")).isEqualTo("quantity__c < 0");

        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");
        Response back = admin.get(RULES + "/CloseNeedsDate__c");

        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(json(back, "$.data.expression")).isEqualTo(CLOSE_NEEDS_DATE);
        assertThat(json(back, "$.data.errorMessage")).isEqualTo("Give a close date.");
        assertThat(json(back, "$.data.label")).isEqualTo("CloseNeedsDate");
        assertThat(JsonPath.<Boolean>read(back.body(), "$.data.active")).isTrue();
    }

    @Test
    void aRemovedRuleIsMadeAgainByTheUndoOfItsRelease() {
        TestBrowser admin = adminWithOrder();
        admin.postJson(RULES, ruleRequest("CloseNeedsDate", CLOSE_NEEDS_DATE, "Give a close date.",
                "closeDate__c", true));

        Response removed = admin.request("DELETE", RULES + "/CloseNeedsDate__c", null);
        assertThat(removed.status()).isEqualTo(204);
        assertThat(ruleNames(admin)).isEmpty();
        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");
        Response back = admin.get(RULES + "/CloseNeedsDate__c");

        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(json(back, "$.data.expression")).isEqualTo(CLOSE_NEEDS_DATE);
        assertThat(json(back, "$.data.errorField")).isEqualTo("closeDate__c");
    }

    @Test
    void theUndoOfACreationRemovesTheRule() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "NoNegative", "quantity__c < 0", "No negative quantities.");

        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(ruleNames(admin)).isEmpty();
    }

    // ---- refusals name the place ----

    @Test
    void aConditionThatBreaksTheGrammarIsRefusedWithThePlaceAndNothingIsSaved() {
        TestBrowser admin = adminWithOrder();

        Response refused = createRule(admin, "Broken", "quantity__c >\\n  and", "Message");

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.fields.expression[0]"))
                .startsWith("Line 2, column 3: AND joins two conditions");
        assertThat(ruleNames(admin)).isEmpty();
    }

    @Test
    void aConditionThatDoesNotGiveTrueOrFalseIsRefusedAndNamesTheRule() {
        TestBrowser admin = adminWithOrder();

        Response refused = createRule(admin, "NotAnAnswer", "quantity__c + 1", "Message");

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("Validation rule NotAnAnswer__c of Order__c is not valid")
                .contains("true or false");
        assertThat(ruleNames(admin)).isEmpty();
    }

    @Test
    void aConditionThatNamesAFieldTheObjectDoesNotHaveIsRefusedAndNamesTheRuleAndTheField() {
        TestBrowser admin = adminWithOrder();

        Response refused = createRule(admin, "Ghost", "ghost__c > 1", "Message");

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("validation rule Ghost__c of Order__c uses field Order__c.ghost__c, "
                + "which does not exist");
        assertThat(ruleNames(admin)).isEmpty();
    }

    @Test
    void aConditionOfTheWrongTypeIsRefusedWithThePlaceOfTheOperator() {
        TestBrowser admin = adminWithOrder();

        Response refused = createRule(admin, "Mixed", "quantity__c = 'ten'", "Message");

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("Line 1, column 13").contains("Cannot compare a number with text");
    }

    @Test
    void theClockIsAllowedInARule() {
        TestBrowser admin = adminWithOrder();

        Response created = createRule(admin, "NotInThePast", "closeDate__c < TODAY()", "Pick a date in the future.");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    @Test
    void theMessageAndTheFieldItIsShownOnAreChecked() {
        TestBrowser admin = adminWithOrder();

        Response empty = admin.postJson(RULES, ruleRequest("A", "quantity__c < 0", " ", null, true));
        Response badField = admin.postJson(RULES, ruleRequest("B", "quantity__c < 0", "Message", "not a field", true));
        Response missingField = admin.postJson(RULES, ruleRequest("C", "quantity__c < 0", "Message", "ghost__c",
                true));
        Response tooLong = admin.postJson(RULES, ruleRequest("D", "quantity__c < 0", "x".repeat(256), null, true));
        Response good = admin.postJson(RULES, ruleRequest("E", "quantity__c < 0", "Message", "quantity__c", true));

        assertThat(empty.status()).isEqualTo(400);
        assertThat(places(empty)).contains("errorMessage");
        assertThat(badField.status()).isEqualTo(400);
        assertThat(places(badField)).contains("errorField");
        assertThat(missingField.status()).as(missingField.body()).isEqualTo(409);
        assertThat(firstProblem(missingField)).contains("shows its message on field Order__c.ghost__c");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(good.status()).as(good.body()).isEqualTo(201);
        assertThat(ruleNames(admin)).containsExactly("E__c");
    }

    @Test
    void theNameOfARuleIsBuiltLikeTheNameOfEveryOtherDefinition() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "Once", "quantity__c < 0", "Message");

        Response again = createRule(admin, "Once", "quantity__c < 1", "Message");
        Response suffix = createRule(admin, "Twice__c", "quantity__c < 1", "Message");
        Response lower = createRule(admin, "lower", "quantity__c < 1", "Message");

        assertThat(again.status()).isEqualTo(400);
        assertThat(places(again)).contains("name");
        assertThat(suffix.status()).isEqualTo(400);
        assertThat(lower.status()).isEqualTo(400);
    }

    @Test
    void aRuleChangedOnTopOfANewerVersionIsRefused() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "Once", "quantity__c < 0", "Message");
        admin.request("PUT", RULES + "/Once__c", ruleUpdate("Once", "quantity__c < 1", "Message", true, 0));

        Response stale = admin.request("PUT", RULES + "/Once__c", ruleUpdate("Once", "quantity__c < 2", "Message",
                true, 0));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(stale.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void anObjectWhoseRecordsBelongToThePlatformCannotHaveRules() {
        TestBrowser admin = adminWithOrder();

        Response refused = admin.postJson(OBJECTS + "/User/validation-rules", ruleRequest("Any", "TRUE", "Message"));

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("belong to the platform");
    }

    @Test
    void anUnknownObjectOrRuleIsNotFound() {
        TestBrowser admin = adminWithOrder();

        assertThat(admin.get(OBJECTS + "/Ghost__c/validation-rules").status()).isEqualTo(404);
        assertThat(admin.get(RULES + "/Ghost__c").status()).isEqualTo(404);
        assertThat(admin.request("DELETE", RULES + "/Ghost__c", null).status()).isEqualTo(404);
        assertThat(admin.request("PUT", RULES + "/Ghost__c", ruleUpdate("G", "TRUE", "M", true, 0)).status())
                .isEqualTo(404);
    }

    @Test
    void anObjectMayHaveFiftyRulesAndNotMore() {
        TestBrowser admin = adminWithOrder();
        for (int i = 0; i < 50; i++) {
            ruleExists(admin, "Rule" + i, "quantity__c = " + i, "Message");
        }

        Response over = createRule(admin, "OneTooMany", "quantity__c = 99", "Message");

        assertThat(over.status()).as(over.body()).isEqualTo(409);
        assertThat(firstProblem(over)).contains("most validation rules").contains("50");
        assertThat(ruleNames(admin)).hasSize(50);
    }

    // ---- what a rule keeps in place ----

    @Test
    void aFieldARuleUsesCannotBeRemovedAndTheErrorNamesTheRule() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "NoNegative", "quantity__c < 0", "Message");
        admin.postJson(RULES, ruleRequest("ShowsOnStatus", "quantity__c < -5", "Message", "status__c", true));

        Response usedInCondition = admin.request("DELETE", ORDER + "/fields/quantity__c", null);
        Response usedForTheMessage = admin.request("DELETE", ORDER + "/fields/status__c", null);

        assertThat(usedInCondition.status()).as(usedInCondition.body()).isEqualTo(409);
        assertThat(firstProblem(usedInCondition)).contains("validation rule NoNegative__c of Order__c uses field "
                + "Order__c.quantity__c, which does not exist after this change");
        assertThat(usedForTheMessage.status()).isEqualTo(409);
        assertThat(firstProblem(usedForTheMessage)).contains("validation rule ShowsOnStatus__c of Order__c shows its "
                + "message on field Order__c.status__c");
        assertThat(fieldNames(admin, "Order__c")).contains("quantity__c", "status__c");
    }

    @Test
    void anObjectWithRulesCannotBeRemovedUntilTheRulesAre() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Box");
        createField(admin, "Box__c", typedField("size", "NUMBER"));
        admin.postJson(OBJECTS + "/Box__c/validation-rules", ruleRequest("Small", "size__c > 9", "Too big"));

        Response refused = admin.request("DELETE", OBJECTS + "/Box__c/fields/size__c", null);
        Response objectRefused = admin.request("DELETE", OBJECTS + "/Box__c", null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(objectRefused.status()).as(objectRefused.body()).isEqualTo(409);
        assertThat(objectNames(admin)).contains("Box__c");
    }

    // ---- change sets and releases ----

    @Test
    void aRuleCanBeDraftedCheckedAndPublishedInAChangeSetAndNothingShowsBeforeThen() {
        TestBrowser admin = adminWithOrder();
        String set = createSet(admin, "Rules");
        Response added = addChange(admin, set, change("CREATE_VALIDATION_RULE", "Order__c", null,
                "createValidationRule", ruleRequest("NoNegative", "quantity__c < 0", "Message")));

        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(ruleNames(admin)).isEmpty();
        Response checked = validate(admin, set);
        Response previewed = admin.postJson(SETS + "/" + set + "/preview", "{}");
        assertThat(JsonPath.<Boolean>read(checked.body(), "$.data.valid")).as(checked.body()).isTrue();
        assertThat(previewed.status()).isEqualTo(200);
        assertThat(ruleNames(admin)).isEmpty();

        Response published = publish(admin, set);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(ruleNames(admin)).containsExactly("NoNegative__c");
        assertThat(JsonPath.<List<String>>read(admin.get(RELEASES).body(), "$.data[0].items[*].kind"))
                .contains("VALIDATION_RULE");
    }

    @Test
    void aSetWithABadRulePublishesNothingAndNamesTheChangeAndThePlace() {
        TestBrowser admin = adminWithOrder();
        String set = createSet(admin, "Half good");
        addChange(admin, set, change("CREATE_VALIDATION_RULE", "Order__c", null, "createValidationRule",
                ruleRequest("Good", "quantity__c < 0", "Message")));
        addChange(admin, set, change("CREATE_VALIDATION_RULE", "Order__c", null, "createValidationRule",
                ruleRequest("Bad", "quantity__c +", "Message")));

        Response report = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isFalse();
        assertThat(problems(report)).singleElement().asString().contains("The change breaks a rule");
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.problems[*].fields[*]")).singleElement()
                .asString().contains("expression: Line 1, column ");
        assertThat(published.status()).isEqualTo(409);
        assertThat(ruleNames(admin)).isEmpty();
    }

    @Test
    void aSetThatRemovesAFieldARuleUsesIsRefusedUnlessItRemovesTheRuleToo() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "NoNegative", "quantity__c < 0", "Message");
        String refusedSet = createSet(admin, "Only the field");
        addChange(admin, refusedSet, change("DELETE_FIELD", "Order__c", "quantity__c", null, null));
        String goodSet = createSet(admin, "Both");
        addChange(admin, goodSet, change("DELETE_VALIDATION_RULE", "Order__c", "NoNegative__c", null, null));
        addChange(admin, goodSet, change("DELETE_FIELD", "Order__c", "quantity__c", null, null));

        Response refused = validate(admin, refusedSet);
        Response published = publish(admin, goodSet);

        assertThat(JsonPath.<Boolean>read(refused.body(), "$.data.valid")).isFalse();
        assertThat(problems(refused)).singleElement().asString().contains("validation rule NoNegative__c of Order__c "
                + "uses field Order__c.quantity__c, which does not exist after this change");
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(ruleNames(admin)).isEmpty();
        assertThat(fieldNames(admin, "Order__c")).doesNotContain("quantity__c");
    }

    @Test
    void theRollbackOfASetThatRemovedAFieldAndItsRuleMakesThemAgainInTheRightOrder() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "NoNegative", "quantity__c < 0", "Message");
        String set = createSet(admin, "Both");
        addChange(admin, set, change("DELETE_VALIDATION_RULE", "Order__c", "NoNegative__c", null, null));
        addChange(admin, set, change("DELETE_FIELD", "Order__c", "quantity__c", null, null));
        assertThat(publish(admin, set).status()).isEqualTo(200);

        Response check = admin.postJson(RELEASES + "/latest/rollback-check", "{}");
        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).as(check.body()).isTrue();
        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Order__c")).contains("quantity__c");
        assertThat(ruleNames(admin)).containsExactly("NoNegative__c");
        assertThat(json(admin.get(RULES + "/NoNegative__c"), "$.data.expression")).isEqualTo("quantity__c < 0");
    }

    @Test
    void aSetMayUpdateAndRemoveARuleAndEveryStepIsUndone() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "NoNegative", "quantity__c < 0", "Message");
        String set = createSet(admin, "Change");
        addChange(admin, set, change("UPDATE_VALIDATION_RULE", "Order__c", "NoNegative__c", "updateValidationRule",
                ruleUpdate("NoNegative", "quantity__c < -1", "Newer message", true, 0)));
        assertThat(publish(admin, set).status()).isEqualTo(200);
        assertThat(json(admin.get(RULES + "/NoNegative__c"), "$.data.errorMessage")).isEqualTo("Newer message");

        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(json(admin.get(RULES + "/NoNegative__c"), "$.data.errorMessage")).isEqualTo("Message");
        assertThat(json(admin.get(RULES + "/NoNegative__c"), "$.data.expression")).isEqualTo("quantity__c < 0");
    }

    // ---- an inactive rule is still a rule ----

    @Test
    void anInactiveRuleIsStoredAndStillCheckedLikeAnyOther() {
        TestBrowser admin = adminWithOrder();

        Response created = admin.postJson(RULES, ruleRequest("Sleeping", "quantity__c < 0", "Message", null, false));
        Response broken = admin.postJson(RULES, ruleRequest("SleepingBroken", "ghost__c < 0", "Message", null,
                false));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(created.body(), "$.data.active")).isFalse();
        assertThat(broken.status()).isEqualTo(409);
    }
}
