package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checking an expression and trying it on sample values, without records (Sprint 13, ADR-0079): the answers carry the
 * place and the kind of problem, the sample values are read by the type of their field, nothing is stored, and the list
 * of functions comes from the engine, so no screen holds a list of its own.
 */
@PlatformIntegrationTest
class ExpressionEndpointsIT extends ExpressionTestSupport {

    private static final String CHECK = EXPRESSIONS + "/check";
    private static final String TEST = EXPRESSIONS + "/test";

    // ---- check ----

    @Test
    void aValidFormulaSaysTheTypeItGivesAndTheFieldsItUses() {
        TestBrowser admin = adminWithOrder();

        Response checked = admin.postJson(CHECK, check("quantity__c * unitPrice__c", "FORMULA", "CURRENCY"));

        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(checked.body(), "$.data.valid")).isTrue();
        assertThat(json(checked, "$.data.type")).isEqualTo("NUMBER");
        assertThat(JsonPath.<List<String>>read(checked.body(), "$.data.fields")).containsExactly("quantity__c",
                "unitPrice__c");
        assertThat(JsonPath.<Boolean>read(checked.body(), "$.data.usesClock")).isFalse();
    }

    @Test
    void anInvalidExpressionSaysWhereItIsWrongAndWhatKindOfProblemItIs() {
        TestBrowser admin = adminWithOrder();

        Response syntax = admin.postJson(CHECK, check("quantity__c *", "FORMULA", null));
        Response unknown = admin.postJson(CHECK, check("ghost__c * 2", "FORMULA", null));
        Response typed = admin.postJson(CHECK, check("status__c * 2", "FORMULA", null));

        assertThat(JsonPath.<Boolean>read(syntax.body(), "$.data.valid")).isFalse();
        assertThat(json(syntax, "$.data.problem.kind")).isEqualTo("SYNTAX");
        assertThat(JsonPath.<Integer>read(syntax.body(), "$.data.problem.line")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(syntax.body(), "$.data.problem.column")).isEqualTo(14);
        assertThat(json(syntax, "$.data.problem.message")).startsWith("Line 1, column 14: ");
        assertThat(json(unknown, "$.data.problem.kind")).isEqualTo("UNKNOWN_FIELD");
        assertThat(JsonPath.<List<String>>read(unknown.body(), "$.data.fields")).containsExactly("ghost__c");
        assertThat(json(typed, "$.data.problem.kind")).isEqualTo("TYPE");
        assertThat(JsonPath.<Integer>read(typed.body(), "$.data.problem.column")).isEqualTo(11);
    }

    @Test
    void aFormulaMustGiveTheTypeItIsDeclaredToGiveAndARuleMustGiveTrueOrFalse() {
        TestBrowser admin = adminWithOrder();

        Response wrongType = admin.postJson(CHECK, check("status__c", "FORMULA", "NUMBER"));
        Response rule = admin.postJson(CHECK, check("quantity__c + 1", "VALIDATION_RULE", null));
        Response goodRule = admin.postJson(CHECK, check("quantity__c > 1", "VALIDATION_RULE", null));

        assertThat(json(wrongType, "$.data.problem.message")).contains("This gives text, but it is declared to give a "
                + "number");
        assertThat(json(rule, "$.data.problem.message")).contains("true or false");
        assertThat(JsonPath.<Boolean>read(goodRule.body(), "$.data.valid")).isTrue();
        assertThat(json(goodRule, "$.data.type")).isEqualTo("BOOLEAN");
    }

    @Test
    void theClockIsForRulesOnly() {
        TestBrowser admin = adminWithOrder();

        Response formula = admin.postJson(CHECK, check("closeDate__c < TODAY()", "FORMULA", null));
        Response rule = admin.postJson(CHECK, check("closeDate__c < TODAY()", "VALIDATION_RULE", null));

        assertThat(json(formula, "$.data.problem.kind")).isEqualTo("NOT_ALLOWED");
        assertThat(JsonPath.<Boolean>read(rule.body(), "$.data.valid")).isTrue();
        assertThat(JsonPath.<Boolean>read(rule.body(), "$.data.usesClock")).isTrue();
    }

    @Test
    void aBadRequestIsRefusedAndAnUnknownObjectIsNotFound() {
        TestBrowser admin = adminWithOrder();

        Response purpose = admin.postJson(CHECK, check("1", "WIZARD", null));
        Response blank = admin.postJson(CHECK, check(" ", "FORMULA", null));
        Response result = admin.postJson(CHECK, check("1", "FORMULA", "PICKLIST"));
        Response object = admin.postJson(CHECK, check("1", "FORMULA", null).replace("Order__c", "Ghost__c"));

        assertThat(purpose.status()).isEqualTo(400);
        assertThat(places(purpose)).contains("purpose");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(result.status()).isEqualTo(400);
        assertThat(places(result)).contains("resultType");
        assertThat(object.status()).isEqualTo(404);
    }

    // ---- try ----

    @Test
    void aFormulaIsCalculatedOnSampleValuesReadByTheTypeOfTheirField() {
        TestBrowser admin = adminWithOrder();

        Response calculated = admin.postJson(TEST, tryOut("quantity__c * unitPrice__c", "FORMULA",
                values("quantity__c", "3", "unitPrice__c", "2.50")));
        Response nothing = admin.postJson(TEST, tryOut("quantity__c * unitPrice__c", "FORMULA", values()));
        Response text = admin.postJson(TEST, tryOut("CONCAT(status__c, ' x', TEXT(quantity__c))", "FORMULA",
                values("status__c", "Open", "quantity__c", "4")));
        Response date = admin.postJson(TEST, tryOut("closeDate__c + 30", "FORMULA",
                values("closeDate__c", "2030-01-31")));

        assertThat(json(calculated, "$.data.outcome")).isEqualTo("VALUE");
        assertThat(json(calculated, "$.data.value")).isEqualTo("7.5");
        assertThat(json(calculated, "$.data.type")).isEqualTo("NUMBER");
        assertThat(json(nothing, "$.data.outcome")).isEqualTo("VALUE");
        assertThat(JsonPath.<Object>read(nothing.body(), "$.data.value")).isNull();
        assertThat(json(text, "$.data.value")).isEqualTo("Open x4");
        assertThat(json(date, "$.data.value")).isEqualTo("2030-03-02");
    }

    @Test
    void aRuleThatIsNotStoredYetRejectsOrPassesOnSampleValues() {
        TestBrowser admin = adminWithOrder();
        String condition = "status__c = 'Closed' AND ISBLANK(closeDate__c)";

        Response rejected = admin.postJson(TEST, tryOut(condition, "VALIDATION_RULE", values("status__c", "Closed")));
        Response passes = admin.postJson(TEST, tryOut(condition, "VALIDATION_RULE",
                values("status__c", "Closed", "closeDate__c", "2030-01-31")));
        Response blank = admin.postJson(TEST, tryOut("quantity__c > 1", "VALIDATION_RULE", values()));

        assertThat(json(rejected, "$.data.outcome")).isEqualTo("REJECTED");
        assertThat(json(passes, "$.data.outcome")).isEqualTo("PASSED");
        assertThat(json(blank, "$.data.outcome")).as("a condition with no value does not reject").isEqualTo("PASSED");
    }

    @Test
    void aFailureOfTheEvaluationIsAnAnswerThatNamesThePlace() {
        TestBrowser admin = adminWithOrder();

        Response divided = admin.postJson(TEST, tryOut("10 / quantity__c", "FORMULA", values("quantity__c", "0")));
        Response broken = admin.postJson(TEST, tryOut("quantity__c *", "FORMULA", values()));

        assertThat(divided.status()).as(divided.body()).isEqualTo(200);
        assertThat(json(divided, "$.data.outcome")).isEqualTo("ERROR");
        assertThat(json(divided, "$.data.problem.kind")).isEqualTo("DIVISION_BY_ZERO");
        assertThat(JsonPath.<Integer>read(divided.body(), "$.data.problem.column")).isEqualTo(4);
        assertThat(json(broken, "$.data.outcome")).isEqualTo("ERROR");
        assertThat(json(broken, "$.data.problem.kind")).isEqualTo("SYNTAX");
    }

    @Test
    void aSampleValueThatDoesNotFitItsFieldIsRefusedUnderItsName() {
        TestBrowser admin = adminWithOrder();

        Response number = admin.postJson(TEST, tryOut("1", "FORMULA", values("quantity__c", "abc")));
        Response date = admin.postJson(TEST, tryOut("1", "FORMULA", values("closeDate__c", "31/01/2030")));
        Response flag = admin.postJson(TEST, tryOut("1", "FORMULA", values("active__c", "yes")));
        Response unknown = admin.postJson(TEST, tryOut("1", "FORMULA", values("ghost__c", "1")));

        for (Response refused : List.of(number, date, flag, unknown)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        }
        assertThat(places(number)).contains("values.quantity__c");
        assertThat(places(date)).contains("values.closeDate__c");
        assertThat(places(flag)).contains("values.active__c");
        assertThat(places(unknown)).contains("values.ghost__c");
    }

    @Test
    void aStoredRuleAndAStoredFormulaCanBeTriedByName() {
        TestBrowser admin = adminWithOrder();
        ruleExists(admin, "NoNegative", "quantity__c < 0", "No negative quantities.");
        formulaExists(admin, "total", "quantity__c * unitPrice__c", "CURRENCY");

        Response rejected = admin.postJson(RULES + "/NoNegative__c/test", values("quantity__c", "-5"));
        Response passes = admin.postJson(RULES + "/NoNegative__c/test", values("quantity__c", "5"));
        Response total = admin.postJson(ORDER + "/fields/total__c/test", values("quantity__c", "4",
                "unitPrice__c", "1.25"));
        Response notAFormula = admin.postJson(ORDER + "/fields/quantity__c/test", values());
        Response missingRule = admin.postJson(RULES + "/Ghost__c/test", values());

        assertThat(json(rejected, "$.data.outcome")).isEqualTo("REJECTED");
        assertThat(json(rejected, "$.data.message")).isEqualTo("No negative quantities.");
        assertThat(json(passes, "$.data.outcome")).isEqualTo("PASSED");
        assertThat(json(total, "$.data.outcome")).isEqualTo("VALUE");
        assertThat(json(total, "$.data.value")).isEqualTo("5");
        assertThat(notAFormula.status()).isEqualTo(400);
        assertThat(missingRule.status()).isEqualTo(404);
    }

    @Test
    void nothingIsStoredByCheckingOrTrying() {
        TestBrowser admin = adminWithOrder();
        Response before = admin.get(RELEASES);

        admin.postJson(CHECK, check("quantity__c > 1", "VALIDATION_RULE", null));
        admin.postJson(TEST, tryOut("quantity__c * 2", "FORMULA", values("quantity__c", "2")));
        admin.postJson(TEST, tryOut("quantity__c > 1", "VALIDATION_RULE", values("quantity__c", "2")));

        assertThat(admin.get(RELEASES).body()).isEqualTo(before.body());
        assertThat(ruleNames(admin)).isEmpty();
    }

    // ---- the library and the report ----

    @Test
    void theFunctionsComeFromTheEngineWithTheirSignaturesAndWhichOnesAreRulesOnly() {
        TestBrowser admin = adminWithOrder();

        Response functions = admin.get(EXPRESSIONS + "/functions");

        assertThat(functions.status()).as(functions.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(functions.body(), "$.data[*].name")).contains("IF", "ISBLANK",
                "COALESCE", "ROUND", "CONCAT", "TEXT", "VALUE", "ADD_DAYS", "DAYS_BETWEEN", "TODAY", "NOW");
        assertThat(JsonPath.<List<String>>read(functions.body(), "$.data[?(@.rulesOnly==true)].name"))
                .containsExactlyInAnyOrder("TODAY", "NOW");
        assertThat(firstOf(functions, "$.data[?(@.name=='ROUND')].signature")).isEqualTo("ROUND(number, places)");
    }

    @Test
    void theReportOfStoredFormulasIsEmptyWhenEveryFormulaWasSavedUnderTheChecks() {
        TestBrowser admin = adminWithOrder();
        formulaExists(admin, "total", "quantity__c * 2", "NUMBER");

        Response report = admin.get(FORMULA_REPORT);

        assertThat(report.status()).as(report.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(report.body(), "$.data")).isEmpty();
    }
}
