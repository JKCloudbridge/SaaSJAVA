package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Formula fields are real since Sprint 13 (ADR-0077): checked when saved, with the place; the fields they use are edges
 * of the dependency graph, so a field a formula uses cannot be removed; a circle and a stack that is too high are
 * refused naming the fields; a formula stored before the language existed keeps working as text, is reported, and is
 * checked for good when it is saved again.
 */
@PlatformIntegrationTest
class FormulaFieldsIT extends ExpressionTestSupport {

    // ---- saved under the checks ----

    @Test
    void aGoodFormulaIsStoredWithItsTextAndItsResultType() {
        TestBrowser admin = adminWithOrder();

        Response total = createFormula(admin, "total", "quantity__c * unitPrice__c", "CURRENCY");
        Response label = createFormula(admin, "label", "CONCAT(status__c, ' - ', TEXT(quantity__c))", "TEXT");
        Response flag = createFormula(admin, "flag", "IF(active__c, 'yes', 'no')", "TEXT");
        Response due = createFormula(admin, "due", "closeDate__c + 30", "DATE");
        Response days = createFormula(admin, "days", "DAYS_BETWEEN(closeDate__c, DATE(2030, 1, 1))", "NUMBER");

        for (Response created : List.of(total, label, flag, due, days)) {
            assertThat(created.status()).as(created.body()).isEqualTo(201);
        }
        assertThat(json(total, "$.data.type")).isEqualTo("FORMULA");
        assertThat(json(total, "$.data.settings.expression")).isEqualTo("quantity__c * unitPrice__c");
        assertThat(json(total, "$.data.settings.resultType")).isEqualTo("CURRENCY");
        assertThat(fieldNames(admin, "Order__c")).contains("total__c", "label__c", "flag__c", "due__c", "days__c");
    }

    @Test
    void aFormulaThatBreaksTheGrammarIsRefusedWithThePlaceUnderTheNameOfTheSetting() {
        TestBrowser admin = adminWithOrder();

        Response refused = createFormula(admin, "broken", "quantity__c *", "NUMBER");
        Response dotted = createFormula(admin, "dotted", "status__c.length()", "NUMBER");

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.fields['settings.expression'][0]"))
                .startsWith("Line 1, column 14: ");
        assertThat(dotted.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(dotted.body(), "$.error.fields['settings.expression'][0]"))
                .contains("A dot is not allowed");
        assertThat(fieldNames(admin, "Order__c")).doesNotContain("broken__c", "dotted__c");
    }

    @Test
    void aFormulaThatNamesAFieldTheObjectDoesNotHaveIsRefusedAndNamesTheFormula() {
        TestBrowser admin = adminWithOrder();

        Response refused = createFormula(admin, "total", "ghost__c * 2", "NUMBER");

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("field Order__c.total__c uses field Order__c.ghost__c, which does "
                + "not exist after this change");
        assertThat(fieldNames(admin, "Order__c")).doesNotContain("total__c");
    }

    @Test
    void aFormulaThatDoesNotGiveItsDeclaredTypeIsRefusedWithThePlace() {
        TestBrowser admin = adminWithOrder();

        Response wrongType = createFormula(admin, "wrong", "status__c", "NUMBER");
        Response textArithmetic = createFormula(admin, "text", "status__c * 2", "NUMBER");
        Response noValue = createFormula(admin, "nothing", "NULL", "NUMBER");

        assertThat(wrongType.status()).as(wrongType.body()).isEqualTo(409);
        assertThat(firstProblem(wrongType)).contains("Formula Order__c.wrong__c is not valid. Line 1, column 1: This "
                + "gives text, but it is declared to give a number.");
        assertThat(textArithmetic.status()).isEqualTo(409);
        assertThat(firstProblem(textArithmetic)).contains("Formula Order__c.text__c is not valid.")
                .contains("needs two numbers, not text and a number");
        assertThat(noValue.status()).isEqualTo(409);
    }

    @Test
    void aFormulaMayNotAskForTheClock() {
        TestBrowser admin = adminWithOrder();

        Response refused = createFormula(admin, "stale", "closeDate__c < TODAY()", "BOOLEAN");

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.fields['settings.expression'][0]"))
                .contains("TODAY is not allowed in a formula");
    }

    @Test
    void theTypeAFormulaGivesCannotChangeButItsTextCan() {
        TestBrowser admin = adminWithOrder();
        formulaExists(admin, "total", "quantity__c * 2", "NUMBER");

        Response retyped = admin.request("PUT", ORDER + "/fields/total__c",
                formulaUpdate("total", "quantity__c * 2", "TEXT", 0));
        Response retexted = admin.request("PUT", ORDER + "/fields/total__c",
                formulaUpdate("total", "quantity__c * 3", "NUMBER", 0));

        assertThat(retyped.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(retyped.body(), "$.error.fields['settings.resultType'][0]"))
                .contains("cannot be changed");
        assertThat(retexted.status()).as(retexted.body()).isEqualTo(200);
        assertThat(json(retexted, "$.data.settings.expression")).isEqualTo("quantity__c * 3");
    }

    // ---- the fields a formula uses stay ----

    @Test
    void aFieldAFormulaUsesCannotBeRemovedLiveAndTheErrorNamesTheFormula() {
        TestBrowser admin = adminWithOrder();
        formulaExists(admin, "total", "quantity__c * unitPrice__c", "CURRENCY");

        Response refused = admin.request("DELETE", ORDER + "/fields/quantity__c", null);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("field Order__c.total__c uses field Order__c.quantity__c, which "
                + "does not exist after this change");
        assertThat(fieldNames(admin, "Order__c")).contains("quantity__c", "total__c");
    }

    @Test
    void theSameHoldsInAChangeSetAndTheFormulaMayGoWithItsField() {
        TestBrowser admin = adminWithOrder();
        formulaExists(admin, "total", "quantity__c * unitPrice__c", "CURRENCY");
        String refusedSet = createSet(admin, "Only the field");
        addChange(admin, refusedSet, change("DELETE_FIELD", "Order__c", "quantity__c", null, null));
        String goodSet = createSet(admin, "Both");
        addChange(admin, goodSet, change("DELETE_FIELD", "Order__c", "total__c", null, null));
        addChange(admin, goodSet, change("DELETE_FIELD", "Order__c", "quantity__c", null, null));

        Response refused = validate(admin, refusedSet);
        Response published = publish(admin, goodSet);

        assertThat(JsonPath.<Boolean>read(refused.body(), "$.data.valid")).isFalse();
        assertThat(problems(refused)).singleElement().asString().contains("field Order__c.total__c uses field "
                + "Order__c.quantity__c, which does not exist after this change");
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Order__c")).doesNotContain("total__c", "quantity__c");
    }

    @Test
    void theRollbackMakesTheFieldBeforeTheFormulaThatUsesIt() {
        TestBrowser admin = adminWithOrder();
        formulaExists(admin, "total", "quantity__c * unitPrice__c", "CURRENCY");
        String set = createSet(admin, "Both");
        addChange(admin, set, change("DELETE_FIELD", "Order__c", "total__c", null, null));
        addChange(admin, set, change("DELETE_FIELD", "Order__c", "quantity__c", null, null));
        assertThat(publish(admin, set).status()).isEqualTo(200);

        Response check = admin.postJson(RELEASES + "/latest/rollback-check", "{}");
        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).as(check.body()).isTrue();
        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Order__c")).contains("quantity__c", "total__c");
    }

    @Test
    void aFormulaAndTheFieldsItUsesCanBeMadeInOneSetInAnyOrderOfTheFieldsAndTheFormula() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Line");
        String set = createSet(admin, "Everything");
        addChange(admin, set, change("CREATE_FIELD", "Line__c", null, "createField",
                formulaField("total", "price__c * 2", "NUMBER")));
        addChange(admin, set, change("CREATE_FIELD", "Line__c", null, "createField",
                typedField("price", "NUMBER")));

        Response checked = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(checked.body(), "$.data.valid")).as(checked.body()).isTrue();
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Line__c")).contains("price__c", "total__c");
    }

    // ---- circles and stacks ----

    @Test
    void aFormulaThatUsesItselfIsRefusedNamingTheField() {
        TestBrowser admin = adminWithOrder();

        Response refused = createFormula(admin, "loop", "loop__c + 1", "NUMBER");

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("Formulas of Order__c use each other in a circle: "
                + "Order__c.loop__c -> Order__c.loop__c");
    }

    @Test
    void twoFormulasThatUseEachOtherInOneSetAreRefusedAndNothingIsPublished() {
        TestBrowser admin = adminWithOrder();
        String set = createSet(admin, "Circle");
        addChange(admin, set, change("CREATE_FIELD", "Order__c", null, "createField",
                formulaField("first", "second__c + 1", "NUMBER")));
        addChange(admin, set, change("CREATE_FIELD", "Order__c", null, "createField",
                formulaField("second", "first__c + 1", "NUMBER")));

        Response report = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isFalse();
        assertThat(problems(report)).singleElement().asString().contains("use each other in a circle")
                .contains("Order__c.first__c").contains("Order__c.second__c");
        assertThat(published.status()).isEqualTo(409);
        assertThat(fieldNames(admin, "Order__c")).doesNotContain("first__c", "second__c");
    }

    @Test
    void aCircleThroughSeveralStepsIsRefusedWhenAnUpdateClosesIt() {
        TestBrowser admin = adminWithOrder();
        formulaExists(admin, "one", "quantity__c * 2", "NUMBER");
        formulaExists(admin, "two", "one__c + 1", "NUMBER");
        formulaExists(admin, "three", "two__c + 1", "NUMBER");

        Response refused = admin.request("PUT", ORDER + "/fields/one__c",
                formulaUpdate("one", "three__c + 1", "NUMBER", fieldVersion(admin, "Order__c", "one__c")));

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(firstProblem(refused)).contains("use each other in a circle").contains("Order__c.one__c")
                .contains("Order__c.two__c").contains("Order__c.three__c");
        assertThat(firstOf(admin.get(ORDER), "$.data.fields[?(@.apiName=='one__c')].settings.expression"))
                .isEqualTo("quantity__c * 2");
    }

    @Test
    void formulasMayBeStackedFiveHighAndNotMoreAndTheErrorNamesTheChain() {
        TestBrowser admin = adminWithOrder();
        formulaExists(admin, "f1", "quantity__c * 2", "NUMBER");
        for (int i = 2; i <= 5; i++) {
            formulaExists(admin, "f" + i, "f" + (i - 1) + "__c + 1", "NUMBER");
        }

        Response tooHigh = createFormula(admin, "f6", "f5__c + 1", "NUMBER");

        assertThat(tooHigh.status()).as(tooHigh.body()).isEqualTo(409);
        assertThat(firstProblem(tooHigh)).contains("Formula Order__c.f6__c is stacked on 6 formulas")
                .contains("Order__c.f6__c -> Order__c.f5__c -> Order__c.f4__c").contains("at most 5");
        assertThat(fieldNames(admin, "Order__c")).contains("f5__c").doesNotContain("f6__c");
    }

    // ---- a formula stored before Sprint 13 ----

    /** Puts a formula into the state of one stored before formulas were checked: no mark, a text that may not pass. */
    private static void storedBeforeTheLanguage(Organization organization, String field, String expression,
            String resultType) throws SQLException {
        TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection,
                "update field_definition set config = cast(? as jsonb), version = version + 1 "
                        + "where tenant_id = ? and object_api_name = 'Order__c' and api_name = ?",
                "{\"expression\":\"" + expression + "\",\"resultType\":\"" + resultType + "\"}",
                organization.id().value(), field));
    }

    @Test
    void aStoredFormulaThatDoesNotPassKeepsItsTextIsReportedAndBlocksNothing() throws SQLException {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        formulaExists(admin, "legacy", "quantity__c", "NUMBER");
        storedBeforeTheLanguage(organization, "legacy__c", "quantity * unitPrice +", "NUMBER");

        Response object = admin.get(ORDER);
        Response report = admin.get(FORMULA_REPORT);
        Response unrelated = createField(admin, "Order__c", typedField("another", "TEXT"));
        Response removeUsed = admin.request("DELETE", ORDER + "/fields/quantity__c", null);

        assertThat(firstOf(object, "$.data.fields[?(@.apiName=='legacy__c')].settings.expression"))
                .isEqualTo("quantity * unitPrice +");
        assertThat(firstOf(object, "$.data.fields[?(@.apiName=='legacy__c')].problem"))
                .startsWith("Line 1, column ");
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data[*].fieldApiName")).containsExactly("legacy__c");
        assertThat(JsonPath.<String>read(report.body(), "$.data[0].objectApiName")).isEqualTo("Order__c");
        assertThat(unrelated.status()).as("an unrelated publication is not blocked").isEqualTo(201);
        assertThat(removeUsed.status()).as("the old text cannot be trusted to name its fields").isEqualTo(204);
    }

    @Test
    void aStoredFormulaCannotBeSavedAgainUntilItsTextIsFixedAndThenItIsVerified() throws SQLException {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        formulaExists(admin, "legacy", "quantity__c", "NUMBER");
        storedBeforeTheLanguage(organization, "legacy__c", "quantity * unitPrice +", "NUMBER");
        long version = fieldVersion(admin, "Order__c", "legacy__c");

        Response stillBroken = admin.request("PUT", ORDER + "/fields/legacy__c",
                formulaUpdate("legacy", "quantity * unitPrice +", "NUMBER", version));
        Response unknownField = admin.request("PUT", ORDER + "/fields/legacy__c",
                formulaUpdate("legacy", "quantity * unitPrice", "NUMBER", version));
        Response fixed = admin.request("PUT", ORDER + "/fields/legacy__c",
                formulaUpdate("legacy", "quantity__c * unitPrice__c", "NUMBER", version));

        assertThat(stillBroken.status()).isEqualTo(400);
        assertThat(unknownField.status()).as(unknownField.body()).isEqualTo(409);
        assertThat(firstProblem(unknownField)).contains("field Order__c.legacy__c uses field Order__c.quantity, "
                + "which does not exist");
        assertThat(fixed.status()).as(fixed.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(FORMULA_REPORT).body(), "$.data[*].fieldApiName")).isEmpty();
        assertThat(admin.request("DELETE", ORDER + "/fields/quantity__c", null).status())
                .as("once verified, the formula is held to what it uses").isEqualTo(409);
    }

    @Test
    void aStoredFormulaThatPassesIsTrustedFromTheStartAndHoldsItsFields() throws SQLException {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        formulaExists(admin, "legacy", "quantity__c", "NUMBER");
        storedBeforeTheLanguage(organization, "legacy__c", "quantity__c * unitPrice__c", "NUMBER");

        Response report = admin.get(FORMULA_REPORT);
        Response removeUsed = admin.request("DELETE", ORDER + "/fields/quantity__c", null);

        assertThat(JsonPath.<List<String>>read(report.body(), "$.data[*].fieldApiName")).isEmpty();
        assertThat(removeUsed.status()).as(removeUsed.body()).isEqualTo(409);
        assertThat(firstProblem(removeUsed)).contains("field Order__c.legacy__c uses field Order__c.quantity__c");
    }
}
